package com.openzilo.input

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputPanelTest {
    @Test fun panelRendersAndPasswordFieldsDisableDictation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == context.packageName })
        val device = UiDevice.getInstance(instrumentation)
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText().trim() }
        val original = shell("settings get secure default_input_method")
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 5_000))
            repeat(6) {
                if (device.findObjects(By.clazz("android.widget.EditText").pkg(context.packageName)).size < 2)
                    device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 30)
            }
            val editors = device.findObjects(By.clazz("android.widget.EditText").pkg(context.packageName))
            assertTrue("Test fields were not visible", editors.size == 2)
            editors.first().click()
            shell("ime set com.openzilo.input/.RingInputMethodService")
            assertTrue("Compose IME did not render", device.wait(Until.hasObject(By.text(context.getString(R.string.switch_keyboard)).pkg(context.packageName)), 8_000))
            device.pressBack()
            repeat(3) {
                if (device.findObjects(By.clazz("android.widget.EditText").pkg(context.packageName)).size < 2)
                    device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 30)
            }
            device.findObjects(By.clazz("android.widget.EditText").pkg(context.packageName)).last().click()
            assertTrue("Password field did not disable dictation", device.wait(Until.hasObject(By.text(context.getString(R.string.private_field)).pkg(context.packageName)), 8_000))
        } finally {
            device.pressBack()
            if (original.startsWith("com.") && original.contains('/')) shell("ime set $original")
        }
    }
}
