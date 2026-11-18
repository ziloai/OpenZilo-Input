package com.openzilo.input

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.openzilo.connect.api.ConnectClient
import com.openzilo.connect.api.LiveAudioEvent
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal data class DictationState(
    val message: String,
    val audioLevel: Float = 0f,
    val preview: String = "",
    val recording: Boolean = false,
    val processing: Boolean = false,
    val seconds: Int = 0,
    val failed: Boolean = false,
)

internal fun pcmAudioLevel(pcm: ByteArray): Float {
    val samples = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder()).asShortBuffer()
    val count = samples.remaining()
    if (count == 0) return 0f
    var energy = 0.0
    while (samples.hasRemaining()) {
        val sample = samples.get() / 32768.0
        energy += sample * sample
    }
    return (sqrt(energy / count) * 5).toFloat().coerceIn(0f, 1f)
}

private data class CapturedAudio(val pcm: ByteArray, val sampleRate: Int, val streamingText: String)

internal class RingDictation(
    private val context: Context,
    private val update: (DictationState) -> Unit,
    private val commit: (String) -> Boolean,
) {
    private suspend fun finalize(audio: CapturedAudio) {
        val whole = withTimeout(75_000) {
            runCatching { (context.applicationContext as InputApplication).recognize(DecodedAudio(audio.pcm, audio.sampleRate, 1)) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    Log.w("OpenZiloInput", "Full recognition failed, keeping the live transcript", error)
                    ""
                }
        }.trim()
        val text = polishTranscript(whole.ifEmpty { audio.streamingText })
        if (text.isEmpty()) {
            update(DictationState(context.getString(R.string.speech_no_match), failed = true))
            return
        }
        val accepted = commit(text)
        update(DictationState(context.getString(if (accepted) R.string.inserted else R.string.input_rejected), failed = !accepted))
    }

    suspend fun run(): Unit = coroutineScope {
        val host = context.applicationContext as InputApplication
        val model = withTimeout(10_000) { host.speechModels.state.first { it.stage != "checking" } }
        check(model.stage == "ready") { context.getString(R.string.local_unavailable) }
        val client = ConnectClient(context)
        var session: LiveSession? = null
        var recognition: Job? = null
        var subscribed = false
        val ready = { update(DictationState(context.getString(R.string.hold_ring))) }
        try {
            update(DictationState(context.getString(R.string.connecting)))
            client.connect()
            val status = client.status()
            check(status.getInt("apiVersion") == ConnectClient.API_VERSION) { context.getString(R.string.connect_update_required) }
            check(status.getString("phase") == "connected") { context.getString(R.string.connect_ring_first) }
            launch {
                client.events.collect { event ->
                    try {
                        when (val value = event.liveAudio() ?: return@collect) {
                            is LiveAudioEvent.Started -> {
                                session?.abort()
                                session = null
                                if (recognition?.isActive == true) return@collect
                                val live = LiveSession(host, value.sessionId, value.channels)
                                session = live
                                var liveState = DictationState(context.getString(R.string.listening), recording = true)
                                update(liveState)
                                live.start(this, onPreview = { text ->
                                    if (session === live) {
                                        liveState = liveState.copy(preview = text)
                                        update(liveState)
                                    }
                                }, onLevel = { level ->
                                    if (session === live) {
                                        liveState = liveState.copy(audioLevel = level, seconds = live.count / 50)
                                        update(liveState)
                                    }
                                })
                            }
                            is LiveAudioEvent.Frame -> {
                                val live = session ?: return@collect
                                live.add(value)
                            }
                            is LiveAudioEvent.Ended -> {
                                val live = session ?: return@collect
                                live.checkEnd(value)
                                session = null
                                if (live.count == 0) {
                                    live.abort()
                                    ready()
                                    return@collect
                                }
                                Log.i("OpenZiloInput", "Ring audio END: validated packets=${live.count}")
                                recognition = launch {
                                    try {
                                        update(DictationState(context.getString(R.string.recognizing), processing = true))
                                        val audio = withTimeout(30_000) { live.finish() }
                                        finalize(audio)
                                    } catch (error: TimeoutCancellationException) {
                                        live.abort()
                                        update(DictationState(context.getString(R.string.speech_failed), failed = true))
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        Log.e("OpenZiloInput", "Audio decoding or recognition failed", error)
                                        live.abort()
                                        update(DictationState(error.message ?: context.getString(R.string.speech_failed), failed = true))
                                    }
                                }
                            }
                            LiveAudioEvent.Interrupted -> {
                                if (session == null) return@collect
                                throw IOException(context.getString(R.string.incomplete_audio))
                            }
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        Log.e("OpenZiloInput", "Ring audio delivery failed", error)
                        session?.abort()
                        session = null
                        update(DictationState(context.getString(R.string.incomplete_audio), failed = true))
                    }
                }
                throw IOException(context.getString(R.string.connect_stopped))
            }
            client.subscribe(setOf("live"))
            val result = client.execute("live.subscribe", Bundle().apply { putBoolean("enabled", true) })
            if (!result.getBoolean("ok")) throw IOException(result.getString("message") ?: context.getString(R.string.live_unavailable))
            subscribed = true
            ready()
            awaitCancellation()
        } finally {
            session?.abort()
            recognition?.cancel()
            withContext(NonCancellable) {
                if (subscribed) withTimeoutOrNull(2_000) {
                    runCatching { client.execute("live.subscribe", Bundle().apply { putBoolean("enabled", false) }, 1_500) }
                }
                client.close()
            }
        }
    }
}

private class LiveSession(
    private val host: InputApplication,
    private val sessionId: Long,
    channels: Int,
) {
    private var received = 0
    private var receivedBytes = 0L
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private val result = CompletableDeferred<CapturedAudio>()
    private val decoder = OpusStreamDecoder(channels)
    private var worker: Job? = null

    val count: Int get() = received

    fun start(scope: CoroutineScope, onPreview: (String) -> Unit, onLevel: (Float) -> Unit) {
        worker = scope.launch(Dispatchers.Default) {
            try {
                result.complete(capture(
                    publishPreview = { text -> withContext(Dispatchers.Main.immediate) { onPreview(text) } },
                    publishLevel = { level -> withContext(Dispatchers.Main.immediate) { onLevel(level) } },
                ))
            } catch (error: CancellationException) {
                result.cancel(error)
                throw error
            } catch (error: Exception) {
                result.completeExceptionally(error)
            }
        }
    }

    fun add(frame: LiveAudioEvent.Frame) {
        check(frame.sessionId == sessionId && frame.sequence == received.toLong() && received < 3000)
        check(channel.trySend(frame.data).isSuccess)
        received++
        receivedBytes += frame.data.size
    }

    fun checkEnd(end: LiveAudioEvent.Ended) {
        check(end.sessionId == sessionId && end.byteCount == receivedBytes)
    }

    suspend fun finish(): CapturedAudio {
        channel.close()
        return result.await()
    }

    fun abort() {
        channel.close()
        worker?.cancel()
    }

    private suspend fun capture(
        publishPreview: suspend (String) -> Unit,
        publishLevel: suspend (Float) -> Unit,
    ): CapturedAudio {
        var streamingReady = false
        try {
            host.beginStreaming()
            streamingReady = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("OpenZiloInput", "Live transcription is unavailable", error)
        }
        var streamingText = ""
        var lastPreview = ""
        val pcm = ByteArrayOutputStream()
        try {
            val pending = ByteArrayOutputStream()
            var pendingSamples = 0
            var lastLevelAt = -50L
            for (packet in channel) {
                val chunk = decoder.feed(packet)
                if (chunk.isEmpty()) continue
                pcm.write(chunk)
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevelAt >= 50) {
                    publishLevel(pcmAudioLevel(chunk))
                    lastLevelAt = now
                }
                if (!streamingReady) continue
                pending.write(chunk)
                pendingSamples += chunk.size / 2
                val rate = decoder.sampleRate
                if (rate <= 0 || pendingSamples < rate * 12 / 100) continue
                val audio = pending.toByteArray()
                pending.reset()
                pendingSamples = 0
                val text = runCatching { host.acceptStreaming(audio, rate) }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    Log.w("OpenZiloInput", "Live transcription failed", error)
                    streamingReady = false
                    ""
                }
                if (text != lastPreview) {
                    lastPreview = text
                    publishPreview(text)
                }
            }
            pcm.write(decoder.finish())
            if (streamingReady) {
                streamingText = runCatching { host.finishStreaming() }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    Log.w("OpenZiloInput", "Final live transcription failed", error)
                    ""
                }
            }
            check(decoder.sampleRate > 0) { "Opus recording contains no decoded audio" }
            return CapturedAudio(pcm.toByteArray(), decoder.sampleRate, streamingText)
        } finally {
            decoder.close()
            runCatching { host.cancelStreaming() }
        }
    }
}
