package com.openzilo.connect.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirmwareProgressTest {
    @Test fun progressIsBoundedAndDoesNotOverflow() {
        assertEquals(0, transferPercent(0u, 100u))
        assertEquals(33, transferPercent(1u, 3u))
        assertEquals(100, transferPercent(UInt.MAX_VALUE, UInt.MAX_VALUE))
        assertEquals(99, transferPercent(UInt.MAX_VALUE - 1u, UInt.MAX_VALUE))
    }

    @Test fun invalidProgressNeverPretendsToComplete() {
        assertThrows(IllegalArgumentException::class.java) { transferPercent(1u, 0u) }
        assertThrows(IllegalArgumentException::class.java) { transferPercent(2u, 1u) }
    }
}
