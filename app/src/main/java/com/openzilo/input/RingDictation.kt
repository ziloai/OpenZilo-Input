package com.openzilo.input

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.openzilo.connect.api.ConnectClient
import com.zilo.libopenzilo.LiveEvent
import com.zilo.libopenzilo.LiveValidator
import com.zilo.libopenzilo.Packet
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class DictationState(
    val message: String,
    val preview: String = "",
    val recording: Boolean = false,
    val processing: Boolean = false,
    val seconds: Int = 0,
    val failed: Boolean = false,
)

internal class RingDictation(
    private val context: Context,
    private val update: (DictationState) -> Unit,
    private val insert: (String) -> Boolean,
) {
    suspend fun run(): Unit = coroutineScope {
        val host = context.applicationContext as InputApplication
        val model = kotlinx.coroutines.withTimeout(10_000) { host.speechModels.state.first { it.stage != "checking" } }
        check(model.stage == "ready") { context.getString(R.string.local_unavailable) }
        val client = ConnectClient(context)
        var validator: LiveValidator? = null
        var packets = mutableListOf<ByteArray>()
        var channels = 1
        var recognition: Job? = null
        var subscribed = false
        val ready = { update(DictationState(context.getString(R.string.hold_ring))) }
        try {
            update(DictationState(context.getString(R.string.connecting)))
            client.connect()
            val status = client.status()
            check(status.getString("phase") == "connected") { context.getString(R.string.connect_ring_first) }
            launch {
                client.events.collect { event ->
                    if (event.topic != "live") return@collect
                    val command = event.data.getInt("command")
                    val body = event.data.getByteArray("body") ?: throw IOException(context.getString(R.string.incomplete_audio))
                    if (command == 0x50f) {
                        validator?.close()
                        validator = null
                        packets = mutableListOf()
                        if (recognition?.isActive == true) return@collect
                        validator = LiveValidator()
                    }
                    val stream = validator ?: return@collect
                    try {
                        when (val value = stream.accept(Packet(command.toUShort(), body, 4u, 0u))) {
                            is LiveEvent.Start -> {
                                channels = value.value.channels.toInt()
                                Log.i("OpenZiloInput", "Ring audio START: channels=$channels")
                                update(DictationState(context.getString(R.string.listening), recording = true))
                            }
                            is LiveEvent.Data -> {
                                packets.add(value.value.data)
                                update(DictationState(context.getString(R.string.listening), recording = true, seconds = packets.size / 50))
                            }
                            is LiveEvent.End -> {
                                val audioPackets = packets.toList()
                                Log.i("OpenZiloInput", "Ring audio END: validated packets=${audioPackets.size}")
                                val audioChannels = channels
                                packets.clear()
                                stream.close()
                                validator = null
                                if (audioPackets.isEmpty()) { ready(); return@collect }
                                recognition = launch {
                                    try {
                                        update(DictationState(context.getString(R.string.recognizing), processing = true))
                                        val audio = withContext(Dispatchers.Default) { OpusPcmDecoder.decode(audioPackets, audioChannels) }
                                        val text = kotlinx.coroutines.withTimeout(75_000) { host.recognize(audio) }.trim()
                                        if (text.isNotEmpty()) {
                                            val accepted = insert(text)
                                            update(DictationState(context.getString(if (accepted) R.string.inserted else R.string.input_rejected), preview = text, failed = !accepted))
                                        } else update(DictationState(context.getString(R.string.speech_no_match), failed = true))
                                    } catch (error: TimeoutCancellationException) {
                                        update(DictationState(context.getString(R.string.speech_failed), failed = true))
                                    } catch (error: CancellationException) { throw error }
                                    catch (error: Exception) {
                                        Log.e("OpenZiloInput", "Audio decoding or recognition failed", error)
                                        update(DictationState(error.message ?: context.getString(R.string.speech_failed), failed = true))
                                    }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        Log.e("OpenZiloInput", "Ring audio validation failed", error)
                        validator?.close()
                        validator = null
                        packets.clear()
                        update(DictationState(context.getString(R.string.incomplete_audio), failed = true))
                    }
                }
                throw IOException(context.getString(R.string.connect_stopped))
            }
            client.subscribe(setOf("live"))
            val result = client.execute("live.subscribe", Bundle().apply { putBoolean("enabled", true) })
            if (!result.getBoolean("ok")) throw IOException(result.getString("message") ?: context.getString(R.string.live_unavailable))
            subscribed = true
            ready()
            awaitCancellation()
        } finally {
            recognition?.cancel()
            validator?.close()
            packets.clear()
            withContext(NonCancellable) {
                if (subscribed) withTimeoutOrNull(2_000) {
                    runCatching { client.execute("live.subscribe", Bundle().apply { putBoolean("enabled", false) }, 1_500) }
                }
                client.close()
            }
        }
    }
}
