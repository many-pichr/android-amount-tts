package io.github.manypichr.khmeramountspeech

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Sample format of exported WAV files. */
public enum class WavSampleFormat {
    /** 16-bit signed integer PCM — the most compatible choice. Default. */
    PCM16,

    /** 32-bit IEEE float PCM — lossless copy of the rendered samples. */
    FLOAT32,
}

/** Minimal RIFF/WAVE writer for mono audio. */
public object WavEncoder {
    @JvmStatic
    @JvmOverloads
    public fun encode(samples: FloatArray, sampleRate: Int, format: WavSampleFormat = WavSampleFormat.PCM16): ByteArray {
        val channels = 1
        val bitsPerSample = if (format == WavSampleFormat.PCM16) 16 else 32
        val formatTag = if (format == WavSampleFormat.PCM16) 1 else 3 // 1 = PCM, 3 = IEEE float
        val bytesPerFrame = channels * bitsPerSample / 8
        val dataSize = samples.size * bytesPerFrame

        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(formatTag.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * bytesPerFrame)
        buffer.putShort(bytesPerFrame.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)

        when (format) {
            WavSampleFormat.PCM16 -> for (sample in samples) buffer.putShort(toPcm16(sample))
            WavSampleFormat.FLOAT32 -> for (sample in samples) buffer.putFloat(sample)
        }
        return buffer.array()
    }

    internal fun toPcm16(sample: Float): Short =
        (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
}
