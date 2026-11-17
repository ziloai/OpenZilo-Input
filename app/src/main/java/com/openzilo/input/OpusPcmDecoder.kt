package com.openzilo.input

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class DecodedAudio(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

internal object OpusPcmDecoder {
    fun decode(packets: List<ByteArray>, channels: Int): DecodedAudio {
        require(channels in 1..2 && packets.size in 1..3000) { "Unsupported Opus recording" }
        require(packets.all { it.size in 1..100 }) { "Invalid Opus packet size" }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, channels).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setByteBuffer("csd-0", ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray(Charsets.US_ASCII))
                put(1.toByte())
                put(channels.toByte())
                putShort(0.toShort())
                putInt(16_000)
                putShort(0.toShort())
                put(0.toByte())
                flip()
            })
            setByteBuffer("csd-1", nanoseconds(0))
            setByteBuffer("csd-2", nanoseconds(80_000_000))
        }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        var started = false
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputIndex = 0
            var inputEnded = false
            var outputEnded = false
            var sampleRate = 0
            var outputChannels = 0
            val energy = DoubleArray(channels)
            var frames = 0L
            val deadline = SystemClock.elapsedRealtime() + 30_000
            fun readOutputFormat(value: MediaFormat) {
                val rate = value.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val count = value.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                require(rate in 8_000..48_000 && count == channels) { "Unsupported decoded audio format" }
                require(value.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_16BIT) {
                    "Opus decoder did not produce PCM16"
                }
                check(pcm.size() == 0 || (sampleRate == rate && outputChannels == count)) { "Decoded audio format changed" }
                sampleRate = rate
                outputChannels = count
            }
            while (!outputEnded) {
                check(!Thread.currentThread().isInterrupted) { "Opus decoding interrupted" }
                check(SystemClock.elapsedRealtime() < deadline) { "Opus decoding timed out" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(1_000)
                    if (index >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(index))
                        buffer.clear()
                        if (inputIndex == packets.size) {
                            codec.queueInputBuffer(index, 0, 0, inputIndex * 20_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val packet = packets[inputIndex]
                            check(buffer.remaining() >= packet.size) { "Opus decoder input buffer is too small" }
                            buffer.put(packet)
                            codec.queueInputBuffer(index, 0, packet.size, inputIndex * 20_000L, 0)
                            inputIndex++
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, if (inputEnded) 10_000 else 1_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readOutputFormat(codec.outputFormat)
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                readOutputFormat(codec.getOutputFormat(index))
                                check(pcm.size().toLong() + info.size <= 12_000_000) { "Decoded audio exceeds the recording limit" }
                                require(info.size % (outputChannels * 2) == 0) { "Unaligned PCM16 output" }
                                val buffer = checkNotNull(codec.getOutputBuffer(index))
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                val samples = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                                while (samples.hasRemaining()) {
                                    for (channel in 0 until channels) {
                                        val value = samples.short.toDouble()
                                        energy[channel] += value * value
                                    }
                                    frames++
                                }
                                pcm.write(if (outputChannels == 2) downmix(bytes) else bytes)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            check(pcm.size() > 0 && sampleRate != 0 && outputChannels != 0) { "Opus recording contains no decoded audio" }
            val decoded = pcm.toByteArray()
            val samples = ByteBuffer.wrap(decoded).order(ByteOrder.nativeOrder())
            var monoEnergy = 0.0
            var peak = 0
            var nonzero = 0L
            while (samples.hasRemaining()) {
                val value = samples.short.toInt()
                monoEnergy += value.toDouble() * value
                peak = maxOf(peak, kotlin.math.abs(value))
                if (value != 0) nonzero++
            }
            val channelRms = energy.map { kotlin.math.sqrt(it / frames).toInt() }
            android.util.Log.i("OpenZiloInput", "PCM signal: durationMs=${frames * 1000 / sampleRate}, channelRms=$channelRms, monoRms=${kotlin.math.sqrt(monoEnergy / frames).toInt()}, monoPeak=$peak, nonzeroPercent=${nonzero * 100 / frames}")
            return DecodedAudio(decoded, sampleRate, 1)
        } finally {
            try {
                if (started) codec.stop()
            } finally {
                codec.release()
            }
        }
    }

    private fun downmix(bytes: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val output = ByteBuffer.allocate(bytes.size / 2).order(ByteOrder.nativeOrder())
        while (input.hasRemaining()) {
            val left = input.short.toInt()
            val right = input.short.toInt()
            output.putShort(((left + right) / 2).toShort())
        }
        return output.array()
    }

    private fun nanoseconds(value: Long): ByteBuffer =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(value); flip() }
}
