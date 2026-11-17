package com.openzilo.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPolicyTest {
    @Test fun passwordVariantsNeverPermitAudioCapture() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            assertFalse(permitsDictation(InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0))
        }
        assertFalse(permitsDictation(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0))
    }

    @Test fun privateAndNonEditorFieldsNeverSubscribeToAudio() {
        assertFalse(permitsDictation(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertFalse(permitsDictation(InputType.TYPE_NULL, 0))
        assertFalse(permitsDictation(InputType.TYPE_CLASS_DATETIME, 0))
    }

    @Test fun ordinaryTextNumbersAndMultilineAcceptDictation() {
        assertTrue(permitsDictation(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_DONE))
        assertTrue(permitsDictation(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0))
        assertTrue(permitsDictation(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 0))
    }
}
