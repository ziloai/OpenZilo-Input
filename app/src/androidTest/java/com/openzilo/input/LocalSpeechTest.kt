package com.openzilo.input

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSpeechTest {
    private suspend fun installedHost(): InputApplication {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = context.applicationContext as InputApplication
        val state = withTimeout(10_000) { host.speechModels.state.first { it.stage != "checking" } }
        assumeTrue("Download the model in input settings before running device inference tests", state.stage == "ready")
        return host
    }

    @Test fun installedModelIsVerifiedInsideKeyboardPrivateStorage() = runBlocking {
        val host = installedHost()
        val directory = host.speechModels.modelDirectory
        assertTrue(directory.canonicalPath.startsWith(host.noBackupFilesDir.canonicalPath + File.separator))
        speechAssets.forEach { asset ->
            File(directory, asset.name).inputStream().use { verifySpeechArtifact(it, asset) }
        }
    }

    @Test fun localEngineRejectsInvalidPcmAndStillDecodesTheRingSampleRate(): Unit = runBlocking {
        val host = installedHost()
        suspend fun reject(pcm: ByteArray, rate: Int) {
            var rejected = false
            try { host.localSpeech.recognize(pcm, rate) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Invalid PCM was accepted", rejected)
        }
        try {
            reject(byteArrayOf(), 48000)
            reject(byteArrayOf(0), 48000)
            reject(ByteArray(32000), 8000)
            reject(ByteArray(16000 * 2 * 61), 16000)
            withTimeout(30_000) { host.recognize(DecodedAudio(ByteArray(48000 * 2), 48000, 1)) }
        } finally { host.localSpeech.release() }
    }
}
