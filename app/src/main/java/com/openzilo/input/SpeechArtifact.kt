package com.openzilo.input

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal data class SpeechAsset(val name: String, val size: Long, val sha256: String, val url: String = "")

private const val senseVoiceRevision = "2365baeacb507f821a0c8120fcee3d484dba7a07"
private const val streamingRevision = "98590b7ed6443e77b714204da2757d75e1a642f4"
private const val senseVoiceBase = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/$senseVoiceRevision"
private const val streamingBase = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/$streamingRevision"

internal val speechAssets = listOf(
    SpeechAsset("model.int8.onnx", 239233841, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51", "$senseVoiceBase/model.int8.onnx"),
    SpeechAsset("tokens.txt", 315894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc", "$senseVoiceBase/tokens.txt"),
    SpeechAsset("encoder-epoch-99-avg-1.int8.onnx", 181895032, "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b", "$streamingBase/encoder-epoch-99-avg-1.int8.onnx"),
    SpeechAsset("decoder-epoch-99-avg-1.int8.onnx", 13091040, "1a70c593d71e53f023f5f55b0b4cfff5055abb786ee3992e5f63dc2e273cc4fa", "$streamingBase/decoder-epoch-99-avg-1.int8.onnx"),
    SpeechAsset("joiner-epoch-99-avg-1.int8.onnx", 3228404, "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0", "$streamingBase/joiner-epoch-99-avg-1.int8.onnx"),
    SpeechAsset("streaming-tokens.txt", 56317, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3", "$streamingBase/tokens.txt"),
)

internal const val streamingEncoder = "encoder-epoch-99-avg-1.int8.onnx"
internal const val streamingDecoder = "decoder-epoch-99-avg-1.int8.onnx"
internal const val streamingJoiner = "joiner-epoch-99-avg-1.int8.onnx"
internal const val streamingTokens = "streaming-tokens.txt"

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
