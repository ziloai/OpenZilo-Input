package com.openzilo.input

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun speechThreads(cores: Int, memoryBytes: Long, lowRam: Boolean, thermalSevere: Boolean): Int =
    minOf(cores.coerceAtLeast(1), if (lowRam || memoryBytes < 4L * 1024 * 1024 * 1024 || thermalSevere) 2 else 4)

internal class LocalSpeech(private val context: Context, private val models: SpeechModels) {
    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var configuredThreads = 0
    private var streaming: OnlineRecognizer? = null
    private var streamingStream: OnlineStream? = null
    private var streamingThreads = 0

    private fun threads(): Int {
        val memory = ActivityManager.MemoryInfo()
        val activity = context.getSystemService(ActivityManager::class.java)
        activity.getMemoryInfo(memory)
        val thermal = Build.VERSION.SDK_INT >= 29 && context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        return speechThreads(Runtime.getRuntime().availableProcessors(), memory.totalMem, activity.isLowRamDevice || memory.lowMemory, thermal)
    }

    fun accelerationDescription(): String = context.getString(R.string.speech_acceleration_cpu, threads())

    suspend fun beginStreaming() = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureActive()
            check(models.state.value.stage == "ready") { "Local speech model is not installed" }
            val count = threads()
            if (streaming == null || streamingThreads != count) {
                streamingStream?.release()
                streamingStream = null
                streaming?.release()
                streaming = null
                streaming = OnlineRecognizer(config = OnlineRecognizerConfig(
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = File(models.modelDirectory, streamingEncoder).absolutePath,
                            decoder = File(models.modelDirectory, streamingDecoder).absolutePath,
                            joiner = File(models.modelDirectory, streamingJoiner).absolutePath,
                        ),
                        tokens = File(models.modelDirectory, streamingTokens).absolutePath,
                        numThreads = count,
                        provider = "cpu",
                        modelType = "zipformer",
                    ),
                    enableEndpoint = false,
                    decodingMethod = "greedy_search",
                ))
                streamingThreads = count
            }
            streamingStream?.release()
            streamingStream = checkNotNull(streaming).createStream("")
        }
    }

    suspend fun acceptStreaming(pcm: ByteArray, sampleRate: Int): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureActive()
            require(sampleRate in setOf(16000, 24000, 32000, 44100, 48000))
            require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= sampleRate * 2 * 60)
            val engine = checkNotNull(streaming) { "Streaming model is not loaded" }
            val stream = checkNotNull(streamingStream) { "Streaming session has not started" }
            val startedAt = SystemClock.elapsedRealtime()
            val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            val samples = FloatArray(pcm.size / 2) { input.short / 32768f }
            stream.acceptWaveform(samples, sampleRate)
            while (engine.isReady(stream)) engine.decode(stream)
            val text = engine.getResult(stream).text.trim()
            Log.d("OpenZiloSpeech", "Streaming decode: audioMs=${samples.size.toLong() * 1000 / sampleRate}, elapsedMs=${SystemClock.elapsedRealtime() - startedAt}, length=${text.length}")
            text
        }
    }

    suspend fun finishStreaming(): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val engine = checkNotNull(streaming) { "Streaming model is not loaded" }
            val stream = checkNotNull(streamingStream) { "Streaming session has not started" }
            stream.inputFinished()
            while (engine.isReady(stream)) engine.decode(stream)
            val text = engine.getResult(stream).text.trim()
            stream.release()
            streamingStream = null
            text
        }
    }

    suspend fun cancelStreaming() = withContext(Dispatchers.Default) {
        mutex.withLock {
            streamingStream?.release()
            streamingStream = null
        }
    }

    suspend fun recognize(pcm: ByteArray, sampleRate: Int): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureActive()
            check(models.state.value.stage == "ready") { "Local speech model is not installed" }
            require(sampleRate in setOf(16000, 24000, 32000, 44100, 48000))
            require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= sampleRate * 2 * 60)
            val count = threads()
            val startedAt = SystemClock.elapsedRealtime()
            if (recognizer == null || configuredThreads != count) {
                recognizer?.release()
                recognizer = null
                recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = File(models.modelDirectory, "model.int8.onnx").absolutePath,
                            language = "auto",
                            useInverseTextNormalization = true,
                        ),
                        tokens = File(models.modelDirectory, "tokens.txt").absolutePath,
                        numThreads = count,
                        provider = "cpu",
                    ),
                ))
                configuredThreads = count
            }
            ensureActive()
            val engine = checkNotNull(recognizer)
            val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            val samples = FloatArray(pcm.size / 2) { input.short / 32768f }
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                engine.decode(stream)
                ensureActive()
                val text = engine.getResult(stream).text.trim()
                Log.i("OpenZiloSpeech", "Local inference complete: backend=cpu, threads=$count, audioMs=${samples.size.toLong() * 1000 / sampleRate}, elapsedMs=${SystemClock.elapsedRealtime() - startedAt}, empty=${text.isEmpty()}")
                text
            } finally {
                stream.release()
            }
        }
    }

    suspend fun release() = withContext(Dispatchers.Default) {
        mutex.withLock {
            streamingStream?.release()
            streamingStream = null
            streaming?.release()
            streaming = null
            streamingThreads = 0
            recognizer?.release()
            recognizer = null
            configuredThreads = 0
        }
    }
}
