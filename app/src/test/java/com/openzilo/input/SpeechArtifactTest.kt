package com.openzilo.input

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeechArtifactTest {
    @get:Rule val folder = TemporaryFolder()
    private val bytes = "model fixture".toByteArray()
    private fun asset(size: Long = bytes.size.toLong(), hash: String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) =
        SpeechAsset("fixture.onnx", size, hash)

    @Test fun downloadedModelMustMatchBothPinnedSizeAndDigest() {
        val output = File(folder.root, "model.pending")
        verifySpeechArtifact(ByteArrayInputStream(bytes), asset(), output)
        assertArrayEquals(bytes, output.readBytes())
        assertThrows(IllegalStateException::class.java) {
            verifySpeechArtifact(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)), asset(), output)
        }
        assertThrows(IllegalStateException::class.java) {
            verifySpeechArtifact(ByteArrayInputStream(bytes + byteArrayOf(0)), asset(), output)
        }
        val changed = bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertThrows(IllegalStateException::class.java) {
            verifySpeechArtifact(ByteArrayInputStream(changed), asset(), output)
        }
    }

    @Test fun inferenceBoundsThreadsAndReducesLoadForHeatAndMemory() {
        val gb = 1024L * 1024 * 1024
        assertEquals(4, speechThreads(8, 16 * gb, false, false))
        assertEquals(2, speechThreads(8, 16 * gb, false, true))
        assertEquals(2, speechThreads(8, 3 * gb, false, false))
        assertEquals(2, speechThreads(8, 16 * gb, true, false))
        assertEquals(1, speechThreads(1, 16 * gb, false, false))
    }
}
