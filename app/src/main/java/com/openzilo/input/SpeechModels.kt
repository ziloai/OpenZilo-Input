package com.openzilo.input

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SpeechModelState(
    val stage: String = "checking",
    val downloaded: Long = 0,
    val total: Long = speechAssets.sumOf { it.size },
    val error: String? = null,
)

internal class SpeechModels(private val context: Context, private val scope: CoroutineScope) {
    val modelDirectory = File(context.noBackupFilesDir, "sensevoice-int8-v1")
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences("speech-model", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(SpeechModelState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var ids = savedIds()

    init {
        job = scope.launch(Dispatchers.IO) {
            if (installed()) {
                clearDownloads()
                mutableState.value = SpeechModelState("ready")
            } else if (ids.size == speechAssets.size) {
                receiveDownloads()
            } else {
                clearDownloads()
                mutableState.value = SpeechModelState("missing")
            }
        }
    }

    fun download() {
        if (job?.isActive == true || state.value.stage == "ready") return
        mutableState.value = SpeechModelState("downloading")
        job = scope.launch(Dispatchers.IO) {
            try {
                clearDownloads()
                speechAssets.forEach { asset ->
                    val target = File(context.getExternalFilesDir("speech-download"), asset.name)
                    check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                    target.delete()
                    val request = DownloadManager.Request(Uri.parse(asset.url))
                        .setTitle("OpenZilo · SenseVoice · ${asset.name}")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverRoaming(false)
                        .setDestinationUri(Uri.fromFile(target))
                    ids = ids + manager.enqueue(request)
                    check(preferences.edit().putString("ids", ids.joinToString(",")).commit())
                }
                receiveDownloads()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                clearDownloads()
                mutableState.value = SpeechModelState("failed", error = error.message)
            }
        }
    }

    fun cancelDownload() {
        if (state.value.stage != "downloading") return
        val previous = job
        previous?.cancel()
        job = scope.launch(Dispatchers.IO) {
            previous?.join()
            clearDownloads()
            mutableState.value = SpeechModelState("missing")
        }
    }

    private suspend fun receiveDownloads() {
        try {
            while (true) {
                var downloaded = 0L
                var complete = 0
                for (id in ids) {
                    manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        check(cursor.moveToFirst()) { "Model download was removed" }
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        check(status != DownloadManager.STATUS_FAILED) { "Model download failed (${cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})" }
                        downloaded += cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                        if (status == DownloadManager.STATUS_SUCCESSFUL) complete++
                    }
                }
                mutableState.value = SpeechModelState("downloading", downloaded)
                if (complete == speechAssets.size) break
                delay(500)
            }
            mutableState.value = SpeechModelState("verifying", speechAssets.sumOf { it.size })
            check(modelDirectory.isDirectory || modelDirectory.mkdirs())
            speechAssets.zip(ids).forEach { (asset, id) ->
                kotlin.coroutines.coroutineContext.ensureActive()
                val pending = File(modelDirectory, "${asset.name}.pending")
                ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id)).use { input ->
                    verifySpeechArtifact(input, asset, pending)
                }
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            speechAssets.forEach { asset ->
                Files.move(File(modelDirectory, "${asset.name}.pending").toPath(), File(modelDirectory, asset.name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            clearDownloads()
            mutableState.value = SpeechModelState("ready")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            clearDownloads()
            mutableState.value = SpeechModelState("failed", error = error.message)
        }
    }

    private fun installed(): Boolean = try {
        speechAssets.all { asset ->
            val file = File(modelDirectory, asset.name)
            if (!file.isFile || file.length() != asset.size) false
            else { file.inputStream().use { verifySpeechArtifact(it, asset) }; true }
        }
    } catch (_: Exception) { false }

    private fun savedIds(): List<Long> = preferences.getString("ids", "").orEmpty().split(',').mapNotNull(String::toLongOrNull)

    private fun clearDownloads() {
        if (ids.isNotEmpty()) manager.remove(*ids.toLongArray())
        ids = emptyList()
        preferences.edit().remove("ids").commit()
        speechAssets.forEach { File(modelDirectory, "${it.name}.pending").delete() }
    }
}
