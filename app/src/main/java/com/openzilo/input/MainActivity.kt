package com.openzilo.input

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openzilo.connect.api.ConnectClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var notice by mutableStateOf("")
    private var enabled by mutableStateOf(false)
    private var selected by mutableStateOf(false)
    private val authorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        notice = getString(R.string.authorization_returned)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scope = rememberCoroutineScope()
            var authorizing by remember { mutableStateOf(false) }
            var text by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            InputTheme {
                Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(stringResource(R.string.setup), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.usage), style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = {
                                val intent = packageManager.getLaunchIntentForPackage(ConnectClient.HOST_PACKAGE)
                                if (intent != null) startActivity(intent) else notice = getString(R.string.install_connect)
                            }) { Text(stringResource(R.string.open_connect)) }
                            Button(enabled = !authorizing, onClick = {
                                scope.launch {
                                    authorizing = true
                                    val client = ConnectClient(this@MainActivity)
                                    try {
                                        client.connect()
                                        val pending = client.requestAuthorization(setOf("info", "audio"))
                                        authorization.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                    } catch (error: CancellationException) { throw error }
                                    catch (error: Exception) { notice = error.message ?: getString(R.string.install_connect) }
                                    finally { client.close(); authorizing = false }
                                }
                            }) { Text(stringResource(R.string.authorize_connect)) }
                        }
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.enable_keyboard)) },
                            supportingContent = { Text(stringResource(if (enabled) R.string.enabled else R.string.disabled)) },
                            trailingContent = { OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text(stringResource(R.string.open)) } },
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.select_keyboard)) },
                            supportingContent = { Text(stringResource(if (selected) R.string.selected else R.string.not_selected)) },
                            trailingContent = { OutlinedButton(onClick = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }) { Text(stringResource(R.string.select)) } },
                        )
                        Text(stringResource(R.string.recognition), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.local_description), style = MaterialTheme.typography.bodyMedium)
                        SpeechSettings(application as InputApplication)
                        Text(stringResource(R.string.test_input), style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = text, onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(), minLines = 3,
                            label = { Text(stringResource(R.string.test_field)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        )
                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.password_test)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        Text(stringResource(R.string.privacy), style = MaterialTheme.typography.bodySmall)
                        if (notice.isNotEmpty()) Text(notice, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
        selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true
    }
}
