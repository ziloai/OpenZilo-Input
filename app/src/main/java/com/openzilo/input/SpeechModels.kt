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
    val total: Long = 0,
    val error: String? = null,
)

internal class SpeechModels(private val context: Context, private val scope: CoroutineScope) {
    val modelDirectory = File(context.noBackupFilesDir, "sensevoice-int8-v1")
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences("speech-model", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(SpeechModelState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var downloads = savedDownloads()

    init {
        job = scope.launch(Dispatchers.IO) {
            val missing = missingAssets()
            if (missing.isEmpty()) {
                clearDownloads()
                mutableState.value = SpeechModelState("ready")
            } else if (downloads.isNotEmpty()) {
                receiveDownloads()
            } else {
                mutableState.value = SpeechModelState("missing", total = missing.sumOf { it.size })
            }
        }
    }

    fun download() {
        if (job?.isActive == true || state.value.stage == "ready") return
        val missing = mutableState.value
        mutableState.value = missing.copy(stage = "downloading", downloaded = 0)
        job = scope.launch(Dispatchers.IO) {
            try {
                clearDownloads()
                val targets = missingAssets()
                check(targets.isNotEmpty()) { "All model files are installed" }
                mutableState.value = SpeechModelState("downloading", 0, targets.sumOf { it.size })
                val enqueued = mutableListOf<Pair<String, Long>>()
                targets.forEach { asset ->
                    val target = File(context.getExternalFilesDir("speech-download"), asset.name)
                    check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                    target.delete()
                    val request = DownloadManager.Request(Uri.parse(asset.url))
                        .setTitle("OpenZilo · ${asset.name}")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverRoaming(false)
                        .setDestinationUri(Uri.fromFile(target))
                    enqueued += asset.name to manager.enqueue(request)
                    downloads = enqueued.toList()
                    check(preferences.edit().putString("downloads", encoded(downloads)).commit())
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
            mutableState.value = SpeechModelState("missing", total = missingAssets().sumOf { it.size })
        }
    }

    private suspend fun receiveDownloads() {
        try {
            val expected = downloads.mapNotNull { (name, id) -> speechAssets.find { it.name == name }?.let { it to id } }
            check(expected.isNotEmpty()) { "No model download to resume" }
            val total = expected.sumOf { it.first.size }
            while (true) {
                var downloaded = 0L
                var complete = 0
                for ((_, id) in expected) {
                    manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        check(cursor.moveToFirst()) { "Model download was removed" }
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        check(status != DownloadManager.STATUS_FAILED) { "Model download failed (${cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})" }
                        downloaded += cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                        if (status == DownloadManager.STATUS_SUCCESSFUL) complete++
                    }
                }
                mutableState.value = SpeechModelState("downloading", downloaded, total)
                if (complete == expected.size) break
                delay(500)
            }
            mutableState.value = SpeechModelState("verifying", total, total)
            check(modelDirectory.isDirectory || modelDirectory.mkdirs())
            expected.forEach { (asset, id) ->
                kotlin.coroutines.coroutineContext.ensureActive()
                val pending = File(modelDirectory, "${asset.name}.pending")
                ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id)).use { input ->
                    verifySpeechArtifact(input, asset, pending)
                }
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            expected.forEach { (asset, _) ->
                Files.move(File(modelDirectory, "${asset.name}.pending").toPath(), File(modelDirectory, asset.name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            clearDownloads()
            check(installed()) { "A model file is still missing" }
            mutableState.value = SpeechModelState("ready")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            clearDownloads()
            mutableState.value = SpeechModelState("failed", error = error.message)
        }
    }

    private fun installed(): Boolean = speechAssets.all(::verified)

    private fun missingAssets(): List<SpeechAsset> = speechAssets.filterNot(::verified)

    private fun verified(asset: SpeechAsset): Boolean = try {
        val file = File(modelDirectory, asset.name)
        if (!file.isFile || file.length() != asset.size) false
        else { file.inputStream().use { verifySpeechArtifact(it, asset) }; true }
    } catch (_: Exception) { false }

    private fun savedDownloads(): List<Pair<String, Long>> =
        preferences.getString("downloads", "").orEmpty().split(',').mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size != 2) null else parts[1].toLongOrNull()?.let { parts[0] to it }
        }

    private fun encoded(pairs: List<Pair<String, Long>>) = pairs.joinToString(",") { "${it.first}|${it.second}" }

    private fun clearDownloads() {
        val ids = downloads.map { it.second }
        if (ids.isNotEmpty()) manager.remove(*ids.toLongArray())
        downloads = emptyList()
        preferences.edit().remove("downloads").commit()
        speechAssets.forEach { File(modelDirectory, "${it.name}.pending").delete() }
    }
}
