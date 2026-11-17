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
        finally {
            speechRelease = scope.launch {
                delay(60_000)
                localSpeech.release()
            }
        }
    }
}
