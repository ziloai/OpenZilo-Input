package com.openzilo.connect.core

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal class GattTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onBytes: (ByteArray) -> Unit,
    private val onClosed: (Throwable) -> Unit,
) : AutoCloseable {
    private val ended = AtomicBoolean(false)
    private val link = ConnectionSlot<BluetoothGatt>()
    private val ready = CompletableDeferred<Unit>()
    private val notificationsReady = CompletableDeferred<Unit>()
    private val mtuNegotiated = CompletableDeferred<Unit>()
    private val negotiatedMtu = AtomicInteger(23)
    private val acknowledgement = AtomicReference<CompletableDeferred<Unit>?>()
    private val writes = Mutex()
    private var outbound: BluetoothGattCharacteristic? = null

    private fun current(gatt: BluetoothGatt): Boolean =
        !ended.get() && link.adopt(gatt)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!current(gatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail(IOException("Bluetooth connection ended (status $status)"))
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    requireConnectPermission(context)
                    check(gatt.discoverServices()) { "Service discovery did not start" }
                } catch (error: SecurityException) { fail(error) }
                catch (error: Exception) { fail(error) }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!current(gatt)) return
            try {
                requireConnectPermission(context)
                check(status == BluetoothGatt.GATT_SUCCESS) { "Service discovery failed ($status)" }
                val service = checkNotNull(gatt.getService(SERVICE)) { "The device does not expose the OpenZilo service" }
                outbound = checkNotNull(service.getCharacteristic(WRITE)) { "Missing ring write characteristic" }
                val notifications = checkNotNull(service.getCharacteristic(NOTIFY)) { "Missing ring notification characteristic" }
                check(outbound!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) { "Ring requires acknowledged writes" }
                check(gatt.setCharacteristicNotification(notifications, true)) { "Could not enable ring notifications" }
                val descriptor = checkNotNull(notifications.getDescriptor(CCCD)) { "Missing notification descriptor" }
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                check(started) { "Notification setup did not start" }
            } catch (error: SecurityException) { fail(error) }
            catch (error: Exception) { fail(error) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!current(gatt) || descriptor.uuid != CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) notificationsReady.complete(Unit)
            else fail(IOException("Notification setup failed ($status)"))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!current(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu.set(mtu)
            Log.i("OpenZiloGatt", "MTU ${negotiatedMtu.get()}, write payload ${gattPayloadSize(negotiatedMtu.get())} bytes (status $status)")
            mtuNegotiated.complete(Unit)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!current(gatt) || characteristic.uuid != WRITE) return
            val waiter = acknowledgement.getAndSet(null) ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) waiter.complete(Unit)
            else {
                val error = IOException("Ring write failed ($status)")
                waiter.completeExceptionally(error)
                fail(error)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (current(gatt) && characteristic.uuid == NOTIFY) onBytes(value.clone())
        }

        @Deprecated("Used by Android 12 and earlier")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && current(gatt) && characteristic.uuid == NOTIFY) {
                @Suppress("DEPRECATION")
                val bytes = characteristic.value?.clone() ?: return
                onBytes(bytes)
            }
        }
    }

    suspend fun connect() {
        requireConnectPermission(context)
        try {
            withTimeout(30_000) {
                val gatt = checkNotNull(device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)) {
                    "Bluetooth could not create a connection"
                }
                if (!current(gatt)) {
                    gatt.close()
                    throw IOException("Bluetooth connection was closed before it became ready")
                }
                notificationsReady.await()
                if (!mtuNegotiated.isCompleted && gatt.requestMtu(498)) {
                    if (withTimeoutOrNull(5_000) { mtuNegotiated.await() } == null) {
                        Log.w("OpenZiloGatt", "MTU negotiation timed out; using ${gattPayloadSize(negotiatedMtu.get())}-byte writes")
                    }
                }
                check(!ended.get()) { "Bluetooth connection ended during setup" }
                ready.complete(Unit)
            }
        } catch (error: SecurityException) {
            close()
            throw error
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun prioritizeFirmware() {
        try {
            requireConnectPermission(context)
            val gatt = checkNotNull(link.get())
            val accepted = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            Log.i("OpenZiloGatt", "Firmware connection priority requested: $accepted")
        } catch (error: SecurityException) {
            fail(error)
            throw error
        }
    }

    suspend fun send(frame: ByteArray) = sendPrepared { frame }

    suspend fun sendPrepared(frame: () -> ByteArray) = writes.withLock {
        ready.await()
        requireConnectPermission(context)
        check(!ended.get()) { "The ring is not connected" }
        val gatt = checkNotNull(link.get())
        val characteristic = checkNotNull(outbound)
        try {
            for (bytes in gattChunks(frame(), negotiatedMtu.get())) {
                val waiter = CompletableDeferred<Unit>()
                check(acknowledgement.compareAndSet(null, waiter)) { "A ring write is already pending" }
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = bytes
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
                check(started) { "Ring write did not start" }
                withTimeout(10_000) { waiter.await() }
            }
        } catch (error: SecurityException) {
            fail(error)
            throw error
        } catch (error: Throwable) {
            fail(error)
            throw error
        }
    }

    private fun fail(error: Throwable) {
        if (!ended.compareAndSet(false, true)) return
        ready.completeExceptionally(error)
        notificationsReady.completeExceptionally(error)
        mtuNegotiated.completeExceptionally(error)
        acknowledgement.getAndSet(null)?.completeExceptionally(error)
        dispose()
        onClosed(error)
    }

    override fun close() {
        if (!ended.compareAndSet(false, true)) return
        val error = IOException("Ring disconnected")
        ready.completeExceptionally(error)
        notificationsReady.completeExceptionally(error)
        mtuNegotiated.completeExceptionally(error)
        acknowledgement.getAndSet(null)?.completeExceptionally(error)
        dispose()
    }

    private fun dispose() {
        link.close()?.let { gatt ->
            try { gatt.disconnect() } catch (_: SecurityException) { }
            try { gatt.close() } catch (_: SecurityException) { }
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

internal fun gattPayloadSize(mtu: Int): Int = mtu.coerceIn(23, 498) - 3

internal fun gattChunks(frame: ByteArray, mtu: Int): Sequence<ByteArray> = sequence {
    val payloadSize = gattPayloadSize(mtu)
    var offset = 0
    while (offset < frame.size) {
        val end = (offset + payloadSize).coerceAtMost(frame.size)
        yield(frame.copyOfRange(offset, end))
        offset = end
    }
}

internal fun requireConnectPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        throw SecurityException("Allow Nearby devices access before connecting")
    }
}
