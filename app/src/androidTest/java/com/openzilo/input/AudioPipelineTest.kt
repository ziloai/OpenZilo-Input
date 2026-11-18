package com.openzilo.input

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import com.openzilo.connect.api.ConnectClient
import com.openzilo.connect.api.ConnectEvent
import com.openzilo.connect.api.LiveAudioEvent
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
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
        val start = live("started") {
            putInt("sampleRate", 16000)
            putInt("channels", 2)
        }.liveAudio() as LiveAudioEvent.Started
        val frames = packets.mapIndexed { index, bytes ->
            val frame = live("frame") {
                putLong("sequence", index.toLong())
                putByteArray("data", bytes)
            }.liveAudio() as LiveAudioEvent.Frame
            assertEquals(index.toLong(), frame.sequence)
            frame.data
        }
        val end = live("ended") { putLong("byteCount", packets.sumOf { it.size }.toLong()) }
            .liveAudio() as LiveAudioEvent.Ended
        assertEquals(start.sessionId, end.sessionId)
        assertEquals(frames.sumOf { it.size }.toLong(), end.byteCount)
        val audio = OpusPcmDecoder.decode(frames, start.channels)
        assertEquals(1, audio.channels)
        val duration = audio.pcm.size.toDouble() / (audio.sampleRate * 2)
        assertTrue("Decoded duration was $duration", duration in 0.38..0.42)
        val samples = ByteBuffer.wrap(audio.pcm).order(ByteOrder.nativeOrder()).asShortBuffer()
        while (samples.hasRemaining()) assertTrue(kotlin.math.abs(samples.get().toInt()) < 2000)
    }

    @Test fun incrementalOpusDecodingMatchesTheBatchDecoder() {
        val packets = listOf(hex("4c07fd873d831aeb3c3b306a801e4e"), hex("4c07fd32cc09616e63ca3db8f5ca51b578")) +
            List(18) { hex("4c07fd32cca63a250523805d476f894a80") }
        val batch = OpusPcmDecoder.decode(packets, 2)
        OpusStreamDecoder(2).use { decoder ->
            val pcm = ByteArrayOutputStream()
            packets.forEach { pcm.write(decoder.feed(it)) }
            pcm.write(decoder.finish())
            assertEquals(batch.sampleRate, decoder.sampleRate)
            assertArrayEquals(batch.pcm, pcm.toByteArray())
        }
    }

    @Test fun monoOpusUsesTheActualDecoderSampleRate() {
        val packets = listOf(hex("480be4c136ecc580"), hex("4807c97227e144ea50")) + List(18) { hex("4807c979c8c957c0") }
        val audio = OpusPcmDecoder.decode(packets, 1)
        assertTrue(audio.sampleRate in listOf(16000, 48000))
        assertEquals(1, audio.channels)
        assertTrue(audio.pcm.size.toDouble() / (audio.sampleRate * 2) in 0.38..0.42)
    }

    @Test fun legacyAudioAndMalformedFramesAreRejected() {
        val legacy = ConnectEvent("live", Bundle().apply {
            putInt("apiVersion", 1)
            putInt("command", 0x510)
            putByteArray("body", byteArrayOf(8))
        })
        assertThrows(IllegalStateException::class.java) { legacy.liveAudio() }
        val missingSequence = live("frame") { putByteArray("data", byteArrayOf(8)) }
        assertThrows(IllegalArgumentException::class.java) { missingSequence.liveAudio() }
        assertEquals(LiveAudioEvent.Interrupted, live("interrupted") {}.liveAudio())
    }

    private fun live(type: String, fields: Bundle.() -> Unit): ConnectEvent = ConnectEvent("live", Bundle().apply {
        putInt("apiVersion", ConnectClient.API_VERSION)
        putString("type", type)
        putLong("sessionId", 1)
        fields()
    })

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
