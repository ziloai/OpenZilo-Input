package com.openzilo.connect.core

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.zilo.libopenzilo.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class RingSession(
    context: Context,
    peer: BluetoothDevice,
    private val outgoingEvents: MutableSharedFlow<Packet>,
    private val onFailure: (RingSession, Throwable) -> Unit,
) : AutoCloseable {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val engine = DeviceEngine(256u)
    private val generation = engine.open()
    private val ended = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<ULong, CompletableDeferred<Packet>>()
    private val input = Channel<SessionInput>(256)
    private val eventQueue = Channel<Packet>(256)
    val otaPackets = Channel<Packet>(32)
    private var otaOwner: String? = null
    private val transport = GattTransport(context, peer,
        onBytes = { bytes ->
            if (!ended.get() && input.trySend(SessionInput.Bytes(bytes)).isFailure) fail(IOException("Ring notification queue overflow"))
        },
        onClosed = { error ->
            if (!ended.get() && input.trySend(SessionInput.Closed(error)).isFailure) fail(error)
        },
    )

    init {
        scope.launch {
            for (message in input) {
                if (message is SessionInput.Closed) {
                    fail(message.error)
                    break
                }
                if (ended.get()) break
                try {
                    dispatch(engine.receive((message as SessionInput.Bytes).value, generation, now()))
                    val discarded = engine.takeDroppedCrcPackets()
                    if (discarded > 0u) Log.w("OpenZilo", "Discarded $discarded ring frames with invalid CRC")
                } catch (error: Throwable) { fail(error) }
            }
        }
        scope.launch {
            while (isActive && !ended.get()) {
                val deadline = engine.nextDeadline()
                val wait = if (deadline == null) 100L else (deadline.toLong() - now().toLong()).coerceIn(1, 100)
                delay(wait)
                dispatch(engine.tick(now()))
            }
        }
        scope.launch { for (packet in eventQueue) outgoingEvents.emit(packet) }
    }

    suspend fun connect() {
        try { transport.connect() } catch (error: Throwable) { fail(error); throw error }
    }

    suspend fun request(request: DeviceRequest, timeoutMs: Long = 10_000, owner: String? = null): Packet = withContext(dispatcher) {
        check(!ended.get()) { "Ring disconnected" }
        require(timeoutMs in 1..120_000) { "Invalid request timeout" }
        val spec = prepareRequest(request)
        engine.checkAccess(spec.command, false)
        val response = spec.response
        if (response == null) {
            try {
                transport.send(engine.prepareSend(spec.command, spec.body, owner))
                Packet(spec.command, byteArrayOf(), 4u, 0u)
            } catch (error: Throwable) {
                if (!ended.get()) engine.interruptWrite(generation)
                fail(error)
                throw error
            }
        } else {
            val ticket = engine.begin(spec.command, response, spec.body, now(), timeoutMs.toULong(), owner)
            val result = CompletableDeferred<Packet>()
            pending[ticket.id] = result
            try {
                withTimeout(timeoutMs) {
                    try { transport.send(ticket.frame) }
                    catch (error: Throwable) { if (!ended.get()) engine.interruptWrite(generation); fail(error); throw error }
                    result.await()
                }
            } finally {
                if (!result.isCompleted || result.isCancelled) {
                    if (!ended.get()) engine.cancel(ticket.id)
                    result.cancel()
                }
                pending.remove(ticket.id)
            }
        }
    }

    suspend fun info(): DeviceInfo = parseSystemInfo(request(DeviceRequest.SystemInfo).body)

    suspend fun negotiate(): Capabilities? = withContext(dispatcher) {
        val before = info()
        try {
            val caps = parseCapabilities(request(DeviceRequest.Capabilities, 3_000).body)
            check(!ended.get()) { "Ring disconnected during capability discovery" }
            engine.setCapabilities(before, caps)
            caps
        } catch (error: Throwable) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            val missing = error is kotlinx.coroutines.TimeoutCancellationException ||
                error is CoreException.Timeout || (error is CoreException.Device && error.code.toInt() == 5) ||
                (error is RingRequestException && (error.code == ErrorCode.TIMEOUT || error.deviceCode == 5))
            if (!missing || ended.get()) throw error
            engine.useLegacyProfile(before, "Capability query unavailable; exact verified legacy profiles only").capabilities
        }
    }

    suspend fun acquireFirmware(owner: String) = withContext(dispatcher) {
        check(!ended.get()) { "Ring disconnected" }
        engine.checkAccess(1005u, false)
        engine.acquireOperation(ExclusiveOperation.FIRMWARE_UPDATE, owner)
        otaOwner = owner
        transport.prioritizeFirmware()
    }

    suspend fun sendFirmware(frame: ByteArray, owner: String) = withContext(dispatcher) {
        check(otaOwner == owner && !ended.get()) { "Firmware operation does not own this connection" }
        try { transport.send(frame) }
        catch (error: Throwable) { if (!ended.get()) engine.interruptWrite(generation); fail(error); throw error }
    }

    private fun dispatch(deliveries: List<Delivery>) {
        for (delivery in deliveries) when (delivery) {
            is Delivery.Response -> pending.remove(delivery.requestId)?.complete(delivery.packet)
            is Delivery.Failure -> pending.remove(delivery.requestId)?.completeExceptionally(
                RingRequestException(delivery.message, delivery.code, delivery.recovery, delivery.deviceCode?.toInt()),
            )
            is Delivery.Fault -> { fail(RingRequestException(delivery.message, delivery.code, delivery.recovery)); return }
            is Delivery.Event -> {
                val packet = delivery.packet
                if (otaOwner != null && packet.command.toInt() in setOf(1105, 2001, 2201)) {
                    if (otaPackets.trySend(packet).isFailure) { fail(IOException("Firmware response queue overflow")); return }
                }
                if (packet.command.toInt() == 0x401) {
                    val receivedAt = (System.currentTimeMillis() / 1000).toUInt()
                    scope.launch {
                        try {
                            val requestedAt = parseTimeRequest(packet.body)
                            transport.sendPrepared {
                                check(!ended.get()) { "Ring disconnected before time reply" }
                                val reply = prepareRequest(DeviceRequest.TimeReply(requestedAt, receivedAt, (System.currentTimeMillis() / 1000).toUInt()))
                                engine.prepareSend(reply.command, reply.body, null)
                            }
                        } catch (error: Throwable) { fail(error) }
                    }
                }
                if (eventQueue.trySend(packet).isFailure) { fail(IOException("Ring event consumers are too slow")); return }
            }
        }
    }

    private fun fail(error: Throwable) {
        if (ended.get()) return
        terminate(error)
        onFailure(this, error)
    }

    override fun close() = terminate(IOException("Ring disconnected"))

    private fun terminate(error: Throwable) {
        if (!ended.compareAndSet(false, true)) return
        transport.close()
        input.close()
        eventQueue.close()
        otaPackets.close(error)
        for (future in pending.values) future.completeExceptionally(error)
        pending.clear()
        scope.launch {
            engine.disconnect(generation)
            engine.close()
            scope.cancel()
        }
    }

    companion object { private fun now(): ULong = SystemClock.elapsedRealtime().toULong() }
}

class RingRequestException(
    message: String,
    val code: ErrorCode,
    val recovery: Recovery,
    val deviceCode: Int? = null,
) : IOException(message)
