package com.openzilo.input

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class RingInputMethodService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null
    private var generation = 0L
    private var visible = false
    private var state by mutableStateOf(DictationState(""))
    private var undoText by mutableStateOf("")
    private var cursor = -1
    private var expectedUndoCursor = -1

    override fun onCreate() {
        super.onCreate()
        saved.performAttach()
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        window?.window?.decorView?.apply {
            setViewTreeLifecycleOwner(this@RingInputMethodService)
            setViewTreeViewModelStoreOwner(this@RingInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@RingInputMethodService)
        }
    }

    override fun onCreateInputView(): View = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@RingInputMethodService)
        setViewTreeViewModelStoreOwner(this@RingInputMethodService)
        setViewTreeSavedStateRegistryOwner(this@RingInputMethodService)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            InputTheme {
                Surface {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 228.dp).navigationBarsPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { startActivity(Intent(this@RingInputMethodService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                                Text(stringResource(R.string.settings))
                            }
                        }
                        Text(state.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium,
                            color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        if (state.recording) Text(stringResource(R.string.recording_duration, state.seconds), style = MaterialTheme.typography.bodyMedium)
                        if (state.processing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.preview.isNotEmpty()) Text(state.preview, maxLines = 3, style = MaterialTheme.typography.bodyLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { if (!switchToNextInputMethod(false)) getSystemService(InputMethodManager::class.java).showInputMethodPicker() }) { Text(stringResource(R.string.switch_keyboard)) }
                            TextButton(onClick = { undo() }, enabled = undoText.isNotEmpty()) { Text(stringResource(R.string.undo)) }
                            TextButton(onClick = { currentInputConnection?.commitText(" ", 1); undoText = "" }) { Text(stringResource(R.string.space)) }
                            TextButton(onClick = { delete() }) { Text(stringResource(R.string.delete)) }
                            if (state.failed) TextButton(onClick = { startDictation() }) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        stopDictation()
        cursor = attribute?.initialSelEnd ?: -1
        undoText = ""
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        visible = true
        if (registry.currentState == Lifecycle.State.CREATED) registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        if (registry.currentState == Lifecycle.State.STARTED) registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        startDictation()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        visible = false
        stopDictation()
        stopViewLifecycle()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        visible = false
        stopDictation()
        stopViewLifecycle()
        super.onWindowHidden()
    }

    private fun stopViewLifecycle() {
        if (registry.currentState == Lifecycle.State.RESUMED) registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        if (registry.currentState == Lifecycle.State.STARTED) registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onFinishInput() {
        stopDictation()
        undoText = ""
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        cursor = newSelEnd
        if (newSelStart != newSelEnd || newSelEnd != expectedUndoCursor) undoText = ""
    }

    private fun startDictation() {
        val previous = running
        previous?.cancel()
        val token = ++generation
        val info = currentInputEditorInfo
        if (!visible || info == null || !permitsDictation(info.inputType, info.imeOptions)) {
            state = DictationState(getString(R.string.private_field))
            return
        }
        running = scope.launch {
            previous?.cancelAndJoin()
            if (token != generation) return@launch
            try {
                RingDictation(this@RingInputMethodService,
                    update = { if (token == generation) state = it },
                    insert = { text -> token == generation && visible && insert(text) },
                ).run()
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException && token == generation)
                    state = DictationState(getString(R.string.speech_failed), failed = true)
                else throw error
            } catch (error: Exception) {
                Log.e("OpenZiloInput", "Dictation connection or subscription failed", error)
                if (token == generation) state = DictationState(error.message ?: getString(R.string.connect_stopped), failed = true)
            }
        }
    }

    private fun stopDictation() {
        generation++
        running?.cancel()
        state = DictationState("")
    }

    private fun insert(text: String): Boolean {
        val info = currentInputEditorInfo ?: return false
        if (!permitsDictation(info.inputType, info.imeOptions)) return false
        val connection = currentInputConnection ?: return false
        expectedUndoCursor = if (cursor >= 0) cursor + text.length else -1
        val committed = connection.commitText(text, 1)
        if (committed) {
            undoText = text
            cursor = expectedUndoCursor
        }
        return committed
    }

    private fun undo() {
        val text = undoText
        val connection = currentInputConnection ?: return
        if (text.isNotEmpty() && cursor == expectedUndoCursor && connection.getTextBeforeCursor(text.length, 0)?.toString() == text) {
            connection.deleteSurroundingText(text.length, 0)
        }
        undoText = ""
    }

    private fun delete() {
        val connection = currentInputConnection ?: return
        if (connection.getSelectedText(0).isNullOrEmpty()) connection.deleteSurroundingTextInCodePoints(1, 0)
        else connection.commitText("", 1)
        undoText = ""
    }

    override fun onDestroy() {
        stopDictation()
        scope.cancel()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }
}
