package com.openzilo.connect.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionLifecycleTest {
    @Test fun closeBeforeConnectionReturnsRejectsLatePublication() {
        val slot = ConnectionSlot<Any>()
        assertNull(slot.close())
        assertFalse(slot.adopt(Any()))
        assertNull(slot.get())
    }

    @Test fun earlyCallbackCanAdoptTheSameConnectionReturnedLater() {
        val slot = ConnectionSlot<Any>()
        val connection = Any()
        assertTrue(slot.adopt(connection))
        assertTrue(slot.adopt(connection))
        assertFalse(slot.adopt(Any()))
        assertSame(connection, slot.close())
        assertNull(slot.close())
        assertFalse(slot.adopt(connection))
    }

    @Test fun concurrentCloseAndPublicationAlwaysReleaseTheConnection() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(250) {
                val slot = ConnectionSlot<Any>()
                val connection = Any()
                val gate = CountDownLatch(1)
                val releases = AtomicInteger()
                val publisher = executor.submit {
                    gate.await()
                    if (!slot.adopt(connection)) releases.incrementAndGet()
                }
                val closer = executor.submit {
                    gate.await()
                    if (slot.close() != null) releases.incrementAndGet()
                }
                gate.countDown()
                publisher.get(3, TimeUnit.SECONDS)
                closer.get(3, TimeUnit.SECONDS)
                assertEquals(1, releases.get())
                assertNull(slot.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun queuedFinalFirmwareReplyRemainsDrainableAfterRemoteClose() = runTest {
        val input = Channel<SessionInput>(2)
        val ota = Channel<ByteArray>(1)
        val finalReply = byteArrayOf(0x22, 0x01, 0)
        val closed = IOException("Ring rebooted")
        assertTrue(input.trySend(SessionInput.Bytes(finalReply)).isSuccess)
        assertTrue(input.trySend(SessionInput.Closed(closed)).isSuccess)
        for (message in input) {
            when (message) {
                is SessionInput.Bytes -> assertTrue(ota.trySend(message.value).isSuccess)
                is SessionInput.Closed -> {
                    ota.close(message.error)
                    break
                }
            }
        }
        assertArrayEquals(finalReply, ota.receive())
        assertSame(closed, ota.receiveCatching().exceptionOrNull())
        input.close()
    }
}
