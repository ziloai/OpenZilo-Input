package com.openzilo.input

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zilo.libopenzilo.LiveEvent
import com.zilo.libopenzilo.LiveValidator
import com.zilo.libopenzilo.Packet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioPipelineTest {
    @Test fun validatedStereoOpusBecomesMonoPcmOnTheDevice() {
        val packets = listOf(hex("4c07fd873d831aeb3c3b306a801e4e"), hex("4c07fd32cc09616e63ca3db8f5ca51b578")) +
            List(18) { hex("4c07fd32cca63a250523805d476f894a80") }
        LiveValidator().use { validator ->
            val start = ByteBuffer.allocate(20).putInt(1).putInt(0).putInt(0).putInt(0).putShort(16000).put(2).put(3).array()
            assertTrue(validator.accept(Packet(0x50fu, start, 4u, 0u)) is LiveEvent.Start)
            var offset = 0
            packets.forEachIndexed { index, bytes ->
                val body = ByteBuffer.allocate(14 + bytes.size).putInt(1).putInt(index).putInt(offset).putShort(bytes.size.toShort()).put(bytes).array()
                assertTrue(validator.accept(Packet(0x510u, body, 4u, 0u)) is LiveEvent.Data)
                offset += bytes.size
            }
            val end = ByteBuffer.allocate(22).putShort(0).putInt(1).putInt(0).putInt(offset).putInt(offset).putInt(0).array()
            assertTrue(validator.accept(Packet(0x511u, end, 4u, 0u)) is LiveEvent.End)
        }
        val audio = OpusPcmDecoder.decode(packets, 2)
        assertEquals(1, audio.channels)
        val duration = audio.pcm.size.toDouble() / (audio.sampleRate * 2)
        assertTrue("Decoded duration was $duration", duration in 0.38..0.42)
        val samples = ByteBuffer.wrap(audio.pcm).order(ByteOrder.nativeOrder()).asShortBuffer()
        while (samples.hasRemaining()) assertTrue(kotlin.math.abs(samples.get().toInt()) < 2000)
    }

    @Test fun monoOpusUsesTheActualDecoderSampleRate() {
        val packets = listOf(hex("480be4c136ecc580"), hex("4807c97227e144ea50")) + List(18) { hex("4807c979c8c957c0") }
        val audio = OpusPcmDecoder.decode(packets, 1)
        assertTrue(audio.sampleRate in listOf(16000, 48000))
        assertEquals(1, audio.channels)
        assertTrue(audio.pcm.size.toDouble() / (audio.sampleRate * 2) in 0.38..0.42)
    }

    @Test fun droppedAudioIsRejectedBeforeTranscription() {
        LiveValidator().use { validator ->
            val start = ByteBuffer.allocate(20).putInt(1).putInt(0).putInt(0).putInt(0).putShort(16000).put(1).put(3).array()
            validator.accept(Packet(0x50fu, start, 4u, 0u))
            val end = ByteBuffer.allocate(22).putShort(0).putInt(1).putInt(0).putInt(100).putInt(0).putInt(1).array()
            assertThrows(Exception::class.java) { validator.accept(Packet(0x511u, end, 4u, 0u)) }
        }
    }

    @Test fun keyboardIsSystemProtectedAndDoesNotOwnBluetooth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getServiceInfo(ComponentName(context, RingInputMethodService::class.java), PackageManager.ComponentInfoFlags.of(0))
        assertEquals(Manifest.permission.BIND_INPUT_METHOD, info.permission)
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())).requestedPermissions.orEmpty()
        assertFalse(permissions.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertFalse(permissions.contains(Manifest.permission.BLUETOOTH_SCAN))
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
