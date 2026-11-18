package com.openzilo.input

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptPolishTest {
    @Test fun removesStandaloneFillersAndClosesTheSentence() {
        assertEquals("我觉得这个方案可以。", polishTranscript("嗯，我觉得这个方案可以"))
        assertEquals("我们明天再聊。", polishTranscript("呃，我们明天再聊。"))
        assertEquals("Hello world. This is fine.", polishTranscript("Um, hello world. this is fine"))
    }

    @Test fun keepsMeaningfulWordsThatOnlyLookLikeFillers() {
        assertEquals("嗯这个字在这里有意义。", polishTranscript("嗯这个字在这里有意义"))
        assertEquals("Email me the file.", polishTranscript("email me the file"))
    }

    @Test fun normalizesSpacesAndRepeatedPunctuation() {
        assertEquals("你好，世界！", polishTranscript("你好 ， 世界 ！"))
        assertEquals("好的，我马上处理。", polishTranscript("好的，，我马上处理。。。"))
        assertEquals("", polishTranscript("   "))
        assertEquals("", polishTranscript("嗯……"))
    }

    @Test fun preservesExistingTerminalPunctuation() {
        assertEquals("这样可以吗？", polishTranscript("这样可以吗？"))
        assertEquals("完成。", polishTranscript("完成。"))
    }
}
