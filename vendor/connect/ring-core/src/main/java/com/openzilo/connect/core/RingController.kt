package com.openzilo.connect.core

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.zilo.libopenzilo.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class FirmwareUpdateException(val installationStarted: Boolean, cause: Throwable) : IOException(cause.message, cause)

fun legacyFirmwareErasesRecordings(info: DeviceInfo?): Boolean =
    info?.model == "Open Zilo" && info.firmwareVersion == "V2.001.0002.0000-prod-bccb2ffc"

data class RingCandidate(val address: String, val name: String, val rssi: Int)

data class RingSnapshot(
    val phase: String = "disconnected",
    val deviceName: String = "",
    val address: String = "",
    val info: DeviceInfo? = null,
    val capabilities: Capabilities? = null,
    val message: String? = null,
    val firmwareProgress: Int? = null,
)

class RingController(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    private val epoch = AtomicLong()
    val connectionEpoch: Long get() = epoch.get()
    private val mutableState = MutableStateFlow(RingSnapshot())
    private val mutableScanResults = MutableStateFlow<List<RingCandidate>>(emptyList())
    private val mutableEvents = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    val state: StateFlow<RingSnapshot> = mutableState.asStateFlow()
    val scanResults: StateFlow<List<RingCandidate>> = mutableScanResults.asStateFlow()
    val events: SharedFlow<Packet> = mutableEvents.asSharedFlow()
    @Volatile private var connection: RingSession? = null
    private var scannerCallback: ScanCallback? = null
    private var scanTimeout: Job? = null
    private var closed = false

    private fun adapter(): BluetoothAdapter {
        requireConnectPermission(context)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("This phone does not support Bluetooth")
        check(adapter.isEnabled) { "Turn on Bluetooth before using the ring" }
        return adapter
    }

    suspend fun scan() = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "Ring controller is closed" }
        if (connection != null) return@withContext
        val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Allow Nearby devices access before searching for a ring")
        }
        stopScan()
        val scanner = checkNotNull(adapter().bluetoothLeScanner) { "Bluetooth scanner is unavailable" }
        mutableScanResults.value = emptyList()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (scannerCallback !== this) return
                try {
                    val candidate = RingCandidate(result.device.address,
                        result.scanRecord?.deviceName?.takeIf { it.isNotBlank() } ?: "OpenZilo Ring", result.rssi)
                    mutableScanResults.update { current ->
                        (current.filterNot { it.address == candidate.address } + candidate).sortedByDescending { it.rssi }.take(64)
                    }
                } catch (error: SecurityException) { stopScan(); mutableState.update { it.copy(message = error.message) } }
            }
            override fun onScanFailed(errorCode: Int) {
                if (scannerCallback !== this) return
                stopScan()
                mutableState.update { it.copy(phase = "disconnected", message = "Bluetooth search failed ($errorCode). Try again.") }
            }
        }
        scannerCallback = callback
        try {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(GattTransport.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            mutableState.update { it.copy(phase = "scanning", message = null) }
            scanTimeout = scope.launch { delay(15_000); stopScan() }
        } catch (error: Throwable) {
            scannerCallback = null
            mutableState.update { it.copy(phase = "disconnected", message = error.message) }
            throw error
        }
    }

    fun stopScan() {
        scanTimeout?.cancel()
        scanTimeout = null
        val callback = scannerCallback
        scannerCallback = null
        if (callback != null) {
            try { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback) }
            catch (_: SecurityException) { }
        }
        mutableState.update { if (it.phase == "scanning") it.copy(phase = "disconnected") else it }
    }

    suspend fun connect(address: String) = operations.withLock {
        check(!closed) { "Ring controller is closed" }
        require(BluetoothAdapter.checkBluetoothAddress(address)) { "Invalid Bluetooth address" }
        stopScan()
        disconnectInternal()
        val name = mutableScanResults.value.firstOrNull { it.address == address }?.name ?: "OpenZilo Ring"
        mutableState.value = RingSnapshot(phase = "connecting", deviceName = name, address = address)
        try {
            val active = newSession(address)
            val info = active.info()
            val capabilities = active.negotiate()
            mutableState.value = RingSnapshot("connected", name, address, info, capabilities)
        } catch (error: Throwable) {
            disconnectInternal()
            mutableState.value = RingSnapshot(message = error.message ?: "Could not connect to the ring")
            throw error
        }
    }

    suspend fun disconnect(expectedEpoch: Long? = null) = operations.withLock {
        check(expectedEpoch == null || expectedEpoch == connectionEpoch) { "The ring connection has changed" }
        stopScan()
        disconnectInternal()
        mutableState.value = RingSnapshot()
    }

    suspend fun refresh() = operations.withLock {
        val info = requireConnection().info()
        mutableState.update { it.copy(info = info, message = null) }
    }

    suspend fun request(request: DeviceRequest, expectedEpoch: Long? = null): Packet = operations.withLock {
        check(expectedEpoch == null || expectedEpoch == connectionEpoch) { "The ring connection has changed" }
        requireConnection().request(request)
    }

    suspend fun upgrade(bundle: ByteArray, trustedPublicKey: String) = operations.withLock {
        var installationStarted = false
        try {
        val verified = withContext(Dispatchers.Default) { inspectFirmwareBundle(bundle, trustedPublicKey) }
        require(verified.profile == "production") { "Only signed production firmware can be installed" }
        val current = requireConnection()
        val previous = current.info()
        val capabilities = checkNotNull(mutableState.value.capabilities) { "Verified ring capabilities are required for a firmware update" }
        verifyCapabilityIdentity(previous, capabilities)
        if (!legacyFirmwareErasesRecordings(previous)) {
            val status = parseRecordingStatus(current.request(DeviceRequest.RecordingStatus).body)
            check(status.state.toInt() !in 1..3) { "Stop recording and wait for it to finish before updating firmware" }
        }
        if (capabilities.features.any { it.name == "live_audio" && it.major.toInt() == 1 }) {
            current.request(DeviceRequest.LiveSubscribe(false))
        }
        if (capabilities.features.any { it.name == "imu" && it.major.toInt() == 2 }) {
            current.request(DeviceRequest.StopImu)
        }
        val ota = OtaTransfer(bundle, trustedPublicKey, previous, capabilities.boardTarget, false)
        val address = mutableState.value.address
        val owner = "firmware-update"
        var complete = false
        try {
            current.acquireFirmware(owner)
            mutableState.update { it.copy(phase = "upgrading", firmwareProgress = 0, message = null) }
            withTimeout(1_200_000) {
                val startFrame = ota.begin()
                installationStarted = true
                current.sendFirmware(startFrame, owner)
                while (true) {
                    val packet = withTimeout(30_000) { current.otaPackets.receive() }
                    val step = ota.handle(packet)
                    step.frame?.let { current.sendFirmware(it, owner) }
                    mutableState.update { it.copy(firmwareProgress = transferPercent(step.sent, step.total)) }
                    if (step.transferComplete) break
                }
            }
            disconnectInternal()
            mutableState.update { it.copy(phase = "reconnecting", firmwareProgress = 100, message = "Waiting for the ring to restart") }
            withTimeout(90_000) {
                while (true) {
                    delay(1_000)
                    val candidate = try { newSession(address) }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        continue
                    }
                    val after = try { candidate.info() }
                    catch (error: Exception) {
                        candidate.close()
                        if (connection === candidate) connection = null
                        if (error is CancellationException) throw error
                        continue
                    }
                    if (after.firmwareVersion != verified.version) {
                        candidate.close()
                        if (connection === candidate) connection = null
                        continue
                    }
                    ota.verifyRunning(after)
                    val caps = candidate.negotiate()
                    mutableState.update { it.copy(phase = "connected", info = after, capabilities = caps,
                        firmwareProgress = null, message = "Firmware update verified") }
                    break
                }
            }
            complete = true
        } catch (error: Throwable) {
            disconnectInternal()
            mutableState.update { it.copy(phase = "disconnected", info = null, capabilities = null,
                firmwareProgress = null, message = "Firmware installation is unconfirmed: ${error.message ?: "connection interrupted"}") }
            throw error
        } finally {
            if (!complete) ota.cancel()
            ota.close()
        }
        } catch (error: Throwable) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            Log.e("OpenZiloOta", "Update failed; installationStarted=$installationStarted", error)
            throw FirmwareUpdateException(installationStarted, error)
        }
    }

    private suspend fun newSession(address: String): RingSession {
        epoch.incrementAndGet()
        val peer = adapter().getRemoteDevice(address)
        val session = RingSession(context, peer, mutableEvents) { failed, error ->
            if (connection === failed) {
                connection = null
                mutableState.update { it.copy(phase = "disconnected", info = null, capabilities = null,
                    message = error.message ?: "Ring connection interrupted") }
            }
        }
        connection = session
        try { session.connect() }
        catch (error: Throwable) {
            session.close()
            if (connection === session) connection = null
            throw error
        }
        return session
    }

    private fun requireConnection(): RingSession = checkNotNull(connection) { "Connect a ring first" }

    private fun disconnectInternal() {
        epoch.incrementAndGet()
        val current = connection
        connection = null
        current?.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        stopScan()
        disconnectInternal()
        scope.cancel()
    }
}

internal fun transferPercent(sent: UInt, total: UInt): Int {
    require(total > 0u && sent <= total) { "Invalid firmware transfer progress" }
    return ((sent.toULong() * 100u) / total.toULong()).toInt()
}
