package com.openzilo.connect.core

internal class ConnectionSlot<T : Any> {
    private var connection: T? = null
    private var closed = false

    @Synchronized fun adopt(candidate: T): Boolean {
        if (closed || (connection != null && connection !== candidate)) return false
        connection = candidate
        return true
    }

    @Synchronized fun get(): T? = connection

    @Synchronized fun close(): T? {
        closed = true
        return connection.also { connection = null }
    }
}
