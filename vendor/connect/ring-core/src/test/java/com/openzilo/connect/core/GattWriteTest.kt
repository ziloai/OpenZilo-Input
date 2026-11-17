package com.openzilo.connect.core

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GattWriteTest {
    @Test fun usesNegotiatedAttPayloadWithinRingLimit() {
        assertEquals(20, gattPayloadSize(23))
        assertEquals(244, gattPayloadSize(247))
        assertEquals(495, gattPayloadSize(498))
        assertEquals(495, gattPayloadSize(517))
        assertEquals(495, gattPayloadSize(Int.MAX_VALUE))
        assertEquals(20, gattPayloadSize(0))
        assertEquals(20, gattPayloadSize(Int.MIN_VALUE))
    }

    @Test fun chunksWithoutLosingOrReorderingFrameBytes() {
        for (mtu in listOf(23, 247, 498, 517)) {
            for (size in listOf(0, 1, 19, 20, 21, 244, 245, 495, 496, 4112, 494880)) {
                val frame = ByteArray(size) { (it % 251).toByte() }
                val output = ByteArrayOutputStream()
                var count = 0
                for (chunk in gattChunks(frame, mtu)) {
                    assertTrue(chunk.size in 1..gattPayloadSize(mtu))
                    output.write(chunk)
                    count++
                }
                assertEquals((size + gattPayloadSize(mtu) - 1) / gattPayloadSize(mtu), count)
                assertArrayEquals(frame, output.toByteArray())
            }
        }
    }

    @Test fun keepsTwentyByteFallbackUntilMtuIsNegotiated() {
        val frame = ByteArray(41) { it.toByte() }
        assertEquals(listOf(20, 20, 1), gattChunks(frame, 23).map { it.size }.toList())
        assertEquals(listOf(41), gattChunks(frame, 498).map { it.size }.toList())
    }

    @Test fun reducesWritesForAFirmwareWindowWithoutChangingPayload() {
        val frame = ByteArray(4112) { it.toByte() }
        assertEquals(206, gattChunks(frame, 23).count())
        assertEquals(9, gattChunks(frame, 498).count())
    }
}
