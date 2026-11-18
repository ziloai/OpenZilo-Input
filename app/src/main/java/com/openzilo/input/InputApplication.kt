package com.openzilo.input

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class InputApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val speechModels by lazy { SpeechModels(this, scope) }
    internal val localSpeech by lazy { LocalSpeech(this, speechModels) }
    private var speechRelease: Job? = null

    internal suspend fun recognize(audio: DecodedAudio): String {
        speechRelease?.cancel()
        try { return localSpeech.recognize(audio.pcm, audio.sampleRate) }
        finally { scheduleRelease(60_000) }
    }

    internal suspend fun beginStreaming() {
        speechRelease?.cancel()
        try { localSpeech.beginStreaming() }
        finally { scheduleRelease(120_000) }
    }

    internal suspend fun acceptStreaming(pcm: ByteArray, sampleRate: Int): String =
        localSpeech.acceptStreaming(pcm, sampleRate)

    internal suspend fun finishStreaming(): String {
        try { return localSpeech.finishStreaming() }
        finally { scheduleRelease(60_000) }
    }

    internal suspend fun cancelStreaming() {
        localSpeech.cancelStreaming()
        scheduleRelease(60_000)
    }

    private fun scheduleRelease(delayMillis: Long) {
        speechRelease?.cancel()
        speechRelease = scope.launch {
            delay(delayMillis)
            localSpeech.release()
        }
    }
}
