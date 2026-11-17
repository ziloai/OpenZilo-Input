package com.openzilo.input

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal data class SpeechAsset(val name: String, val size: Long, val sha256: String) {
    val url: String get() = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/$name"
}

internal val speechAssets = listOf(
    SpeechAsset("model.int8.onnx", 239233841, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
    SpeechAsset("tokens.txt", 315894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
)

internal fun verifySpeechArtifact(input: InputStream, asset: SpeechAsset, destination: File? = null) {
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    val output = destination?.outputStream()
    try {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            check(size <= asset.size) { "Model file exceeds expected size" }
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
        }
        check(size == asset.size) { "Model file is incomplete" }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == asset.sha256) { "Model checksum mismatch" }
    } finally {
        output?.close()
    }
}
