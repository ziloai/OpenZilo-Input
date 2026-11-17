package com.openzilo.connect.api

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ConnectEvent(val topic: String, val data: Bundle)

/** One client owns one bounded event consumer; no Bluetooth permission is required. */
class ConnectClient(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Bundle>>()
    private val eventChannel = Channel<ConnectEvent>(64)
    private val closed = AtomicBoolean(false)
    private var connection: ServiceConnection? = null
    @Volatile private var remote: IConnectService? = null
    private var remoteBinder: IBinder? = null
    private val death = IBinder.DeathRecipient { disconnected(IllegalStateException("Connect service stopped")) }
    val events: Flow<ConnectEvent> = eventChannel.receiveAsFlow()

    private val callback = object : IConnectCallback.Stub() {
        override fun onResult(requestId: String, result: Bundle) {
            pending.remove(requestId)?.complete(Bundle(result))
        }
        override fun onEvent(topic: String, event: Bundle) {
            if (eventChannel.trySend(ConnectEvent(topic, Bundle(event))).isFailure) {
                val error = IllegalStateException("Connect event consumer is too slow")
                eventChannel.close(error)
                scope.launch {
                    try { runCatching { remote?.closeClient(objectCallback()) } }
                    finally { disconnected(error) }
                }
            }
        }
    }
    private fun objectCallback(): IConnectCallback = callback

    suspend fun connect(): ConnectClient = bindMutex.withLock {
        check(!closed.get()) { "Client is closed" }
        if (remote != null) return@withLock this
        withTimeout(10_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        if (!continuation.isActive || closed.get()) { releaseConnection(); return }
                        try {
                            service.linkToDeath(death, 0)
                            remoteBinder = service
                            remote = IConnectService.Stub.asInterface(service)
                            continuation.resume(Unit)
                        } catch (error: Exception) {
                            disconnected(error)
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName) {
                        disconnected(IllegalStateException("Connect service disconnected"))
                    }
                    override fun onBindingDied(name: ComponentName) {
                        disconnected(IllegalStateException("Connect service binding died"))
                    }
                    override fun onNullBinding(name: ComponentName) {
                        val error = IllegalStateException("Connect service rejected binding")
                        disconnected(error)
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
                connection = serviceConnection
                continuation.invokeOnCancellation { releaseConnection() }
                try {
                    val intent = Intent().setComponent(ComponentName(HOST_PACKAGE, HOST_SERVICE))
                    if (!context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                        releaseConnection()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Install OpenZilo Connect before binding"))
                    }
                } catch (error: Exception) {
                    releaseConnection()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
        this
    }

    suspend fun requestAuthorization(scopes: Set<String>): PendingIntent = withContext(Dispatchers.IO) {
        service().requestAuthorization(scopes.toTypedArray())
    }

    suspend fun status(): Bundle = withContext(Dispatchers.IO) { service().status }

    suspend fun execute(operation: String, arguments: Bundle = Bundle(), timeoutMs: Long = 30_000): Bundle {
        require(timeoutMs in 1..600_000)
        val requestId = UUID.randomUUID().toString()
        val result = CompletableDeferred<Bundle>()
        synchronized(pending) {
            check(pending.size < 4) { "Too many requests" }
            pending[requestId] = result
        }
        var delivered = false
        return try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) { service().execute(requestId, operation, arguments, callback) }
                result.await().also { delivered = true }
            }
        } finally {
            pending.remove(requestId)
            result.cancel()
            if (!delivered) withContext(NonCancellable + Dispatchers.IO) { runCatching { remote?.cancel(requestId, callback) } }
        }
    }

    suspend fun subscribe(topics: Set<String>) = withContext(Dispatchers.IO) {
        service().subscribe(topics.toTypedArray(), callback)
    }

    suspend fun unsubscribe() = withContext(Dispatchers.IO) { service().unsubscribe(callback) }

    private fun service(): IConnectService {
        check(!closed.get()) { "Client is closed" }
        return remote ?: throw IllegalStateException("Call connect() before using ConnectClient")
    }

    @Synchronized
    private fun disconnected(error: Throwable) {
        closed.set(true)
        remote = null
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        eventChannel.close(error)
        releaseConnection()
        scope.cancel()
    }

    @Synchronized
    private fun releaseConnection() {
        remoteBinder?.let { binder -> runCatching { binder.unlinkToDeath(death, 0) } }
        remoteBinder = null
        remote = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { runCatching { remote?.closeClient(callback) } }
        finally { disconnected(IllegalStateException("Client is closed")) }
    }

    companion object {
        const val API_VERSION = 1
        const val HOST_PACKAGE = "com.openzilo.connect"
        const val HOST_SERVICE = "com.openzilo.connect.ConnectService"
    }
}
