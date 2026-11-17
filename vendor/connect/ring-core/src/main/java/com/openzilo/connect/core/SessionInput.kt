package com.openzilo.connect.core

internal sealed interface SessionInput {
    data class Bytes(val value: ByteArray) : SessionInput
    data class Closed(val error: Throwable) : SessionInput
}
