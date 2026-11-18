package com.openzilo.input

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
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

@Composable
private fun AudioWave(level: Float, modifier: Modifier = Modifier) {
    val amplitude = animateFloatAsState(level, label = "Ring audio level")
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val wave = Path()
        val center = size.height / 2
        for (step in 0..48) {
            val t = step / 48.0
            val envelope = sin(PI * t)
            val offset = sin(6 * PI * t) * envelope * amplitude.value * center * 0.8
            val y = center + offset.toFloat()
            val x = size.width * t.toFloat()
            if (step == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        drawPath(wave, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

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
    private var selectionStart = -1
    private var expectedUndoCursor = -1
    private data class Preview(val connection: InputConnection, val start: Int, val end: Int, val confirmed: Boolean = false)
    private var composing: Preview? = null
    private var inlineSupported = true

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
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                state.message,
                                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                            if (state.recording) {
                                AudioWave(state.audioLevel, Modifier.width(64.dp).height(24.dp))
                            } else if (state.failed) {
                                TextButton(onClick = ::startDictation) { Text(stringResource(R.string.retry)) }
                            }
                        }
                        if (state.processing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        val canEdit = !state.recording && !state.processing
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { if (!switchToNextInputMethod(false)) getSystemService(InputMethodManager::class.java).showInputMethodPicker() }) {
                                Text(stringResource(R.string.switch_keyboard))
                            }
                            TextButton(onClick = ::undo, enabled = canEdit && undoText.isNotEmpty()) { Text(stringResource(R.string.undo)) }
                            TextButton(onClick = {
                                currentInputConnection?.commitText(" ", 1)
                                undoText = ""
                            }, enabled = canEdit) { Text(stringResource(R.string.space)) }
                            TextButton(onClick = ::delete, enabled = canEdit) { Text(stringResource(R.string.delete)) }
                            TextButton(onClick = { sendKeyChar('\n') }, enabled = canEdit) { Text(stringResource(R.string.enter_key)) }
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
        selectionStart = attribute?.initialSelStart ?: -1
        inlineSupported = true
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
        selectionStart = newSelStart
        composing?.let { preview ->
            if (candidatesStart == preview.start && candidatesEnd == preview.end &&
                newSelStart == preview.end && newSelEnd == preview.end
            ) {
                composing = preview.copy(confirmed = true)
            } else if (preview.confirmed || newSelEnd == preview.end) {
                inlineSupported = false
                composing = preview.copy(confirmed = false)
            }
        }
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
                    update = { next ->
                        if (token == generation) {
                            if (next.recording && next.preview != state.preview && visible) {
                                if (next.preview.isNotEmpty()) showPreview(next.preview)
                                else if (composing?.confirmed == true && inlineSupported) clearPreview()
                            } else if (!next.recording && !next.processing) {
                                clearPreview()
                            }
                            state = next
                        }
                    },
                    commit = { text -> token == generation && visible && commit(text) },
                ).run()
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException && token == generation)
                    state = DictationState(getString(R.string.speech_failed), failed = true)
                else throw error
            } catch (error: Exception) {
                Log.e("OpenZiloInput", "Dictation connection or subscription failed", error)
                if (token == generation) state = DictationState(error.message ?: getString(R.string.connect_stopped), failed = true)
            } finally {
                if (token == generation) clearPreview()
            }
        }
    }

    private fun stopDictation() {
        generation++
        running?.cancel()
        state = DictationState("")
        clearPreview()
    }

    private fun showPreview(text: String) {
        val info = currentInputEditorInfo ?: return
        if (!permitsDictation(info.inputType, info.imeOptions)) return
        val connection = currentInputConnection ?: return
        val previous = composing
        if (!inlineSupported || (previous != null && (!previous.confirmed || previous.connection !== connection))) return
        if (previous == null && (cursor < 0 || selectionStart != cursor)) return
        val start = previous?.start ?: cursor
        val expected = Preview(connection, start, start + text.length, previous?.end == start + text.length)
        if (connection.setComposingText(text, 1)) composing = expected
        else inlineSupported = false
    }

    private fun clearPreview() {
        val preview = composing ?: return
        composing = null
        if (preview.confirmed && currentInputConnection === preview.connection) {
            preview.connection.setComposingText("", 1)
            preview.connection.finishComposingText()
        } else inlineSupported = false
    }

    private fun commit(text: String): Boolean {
        val info = currentInputEditorInfo ?: return false
        if (!permitsDictation(info.inputType, info.imeOptions)) return false
        val connection = currentInputConnection ?: return false
        val preview = composing
        if (preview != null && (!preview.confirmed || preview.connection !== connection)) {
            inlineSupported = false
            return false
        }
        val start = preview?.start ?: minOf(selectionStart, cursor)
        if (!connection.commitText(text, 1)) return false
        composing = null
        expectedUndoCursor = if (start >= 0) start + text.length else -1
        undoText = if (start >= 0) text else ""
        cursor = expectedUndoCursor
        return true
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
