package com.openzilo.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun SpeechSettings(host: InputApplication) {
    val state by host.speechModels.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val status = when (state.stage) {
        "checking" -> R.string.speech_model_checking
        "downloading" -> R.string.speech_model_downloading
        "verifying" -> R.string.speech_model_verifying
        "ready" -> R.string.speech_model_ready
        "failed" -> R.string.speech_model_failed
        else -> R.string.speech_model_missing
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.speech_model_name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.speech_model_languages), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(status), color = if (state.stage == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            when (state.stage) {
                "checking", "verifying" -> LinearProgressIndicator(Modifier.fillMaxWidth())
                "downloading" -> {
                    LinearProgressIndicator(progress = { (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.speech_model_progress, state.downloaded / 1_048_576.0, state.total / 1_048_576.0))
                }
                "ready" -> Text(host.localSpeech.accelerationDescription(), style = MaterialTheme.typography.bodySmall)
                "failed" -> Text(stringResource(R.string.speech_model_retry_detail), style = MaterialTheme.typography.bodySmall)
            }
            if (state.stage in setOf("missing", "failed")) {
                val missing = state.total.takeIf { it > 0 } ?: speechAssets.sumOf { it.size }
                Text(stringResource(R.string.speech_model_size, ((missing + 1_048_575) / 1_048_576).toInt()), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.speech_model_privacy), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    uriHandler.openUri("https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/blob/2365baeacb507f821a0c8120fcee3d484dba7a07/LICENSE")
                }) { Text(stringResource(R.string.speech_model_license)) }
                when (state.stage) {
                    "downloading" -> TextButton(onClick = host.speechModels::cancelDownload) { Text(stringResource(android.R.string.cancel)) }
                    "missing", "failed" -> TextButton(onClick = host.speechModels::download) {
                        Text(stringResource(if (state.stage == "failed") R.string.speech_model_retry else R.string.speech_model_download))
                    }
                }
            }
        }
    }
}
