package io.github.manypichr.khmeramountspeech

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/** Decodes one clip to mono Float samples at [sampleRate]. Returns null if it cannot be read. */
internal fun interface ClipDecoder {
    fun decode(source: AudioSource, sampleRate: Int): FloatArray?
}

/**
 * Decodes clips to mono Float samples at a fixed rate, with an in-memory cache. Thread-safe.
 * Uncached clips are decoded in parallel.
 */
internal class ClipLoader(private val sampleRate: Int, private val decoder: ClipDecoder) {
    class Loaded(
        val token: AudioToken,
        /** One clip, or a compound's parts when its own clip is unavailable. Empty if missing. */
        val clips: List<FloatArray>,
    )

    private val mutex = Mutex()
    private val cache = HashMap<AudioSource, FloatArray>()

    suspend fun load(tokens: List<AudioToken>, resolver: AudioResolver): List<Loaded> = withContext(Dispatchers.IO) {
        val sources = tokens.map { resolver.resolve(it) }
        val clips = decodeAll(sources.filterNotNull())
        // Compound clip unavailable: speak its parts separately instead.
        val fallbacks = tokens.indices.filter { clips[sources[it]] == null && !tokens[it].parts.isNullOrEmpty() }
        val partSources = fallbacks.associateWith { i -> tokens[i].parts!!.map { resolver.resolve(it) } }
        val partClips = decodeAll(partSources.values.flatten().filterNotNull())

        tokens.mapIndexed { i, token ->
            clips[sources[i]]?.let { return@mapIndexed Loaded(token, listOf(it)) }
            val parts = partSources[i]?.map { partClips[it] } ?: return@mapIndexed Loaded(token, emptyList())
            Loaded(token, if (parts.all { it != null }) parts.filterNotNull() else emptyList())
        }
    }

    private suspend fun decodeAll(sources: List<AudioSource>): Map<AudioSource, FloatArray> {
        if (sources.isEmpty()) return emptyMap()
        val uncached = mutex.withLock { sources.distinct().filter { it !in cache } }
        val decoded = coroutineScope {
            uncached.map { source -> async(decodeDispatcher) { source to decode(source) } }.awaitAll()
        }
        return mutex.withLock {
            for ((source, samples) in decoded) if (samples != null) cache[source] = samples
            sources.mapNotNull { source -> cache[source]?.let { source to it } }.toMap()
        }
    }

    private fun decode(source: AudioSource): FloatArray? = try {
        decoder.decode(source, sampleRate)
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** A few codecs at a time: faster than one, without exhausting the device's codec instances. */
        val decodeDispatcher = Dispatchers.IO.limitedParallelism(4)
    }
}

/** Decodes MP3 (or any format the device supports) with MediaExtractor + MediaCodec. */
internal class MediaCodecClipDecoder(context: Context) : ClipDecoder {
    private val assets = context.applicationContext.assets

    override fun decode(source: AudioSource, sampleRate: Int): FloatArray? {
        val extractor = MediaExtractor()
        try {
            when (source) {
                is AudioSource.Asset -> assets.openFd(source.path).use {
                    extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                is AudioSource.FileSource -> extractor.setDataSource(source.file.absolutePath)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                return drain(extractor, codec, inputFormat, sampleRate)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec, inputFormat: MediaFormat, targetRate: Int): FloatArray {
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var floatOutput = false
        var out = FloatArray(rate) // grows as needed
        var count = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idleAfterInput = 0 // guards against decoders that never signal end of stream

        while (true) {
            // Fill every free input buffer, so the codec never waits on us.
            while (!inputDone) {
                val index = codec.dequeueInputBuffer(0)
                if (index < 0) break
                val buffer = codec.getInputBuffer(index)!!
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++idleAfterInput > MAX_IDLE_POLLS) break
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    floatOutput = Build.VERSION.SDK_INT >= 24 && format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytesPerSample = if (floatOutput) 4 else 2
                    val frames = info.size / (bytesPerSample * channels)
                    if (out.size < count + frames) out = out.copyOf(maxOf(count + frames, out.size * 2))
                    // Downmix to mono.
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) {
                            sum += if (floatOutput) buffer.getFloat() else buffer.getShort() / 32768f
                        }
                        out[count++] = sum / channels
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        val mono = out.copyOf(count)
        return if (rate == targetRate) mono else resample(mono, rate, targetRate)
    }

    private companion object {
        const val TIMEOUT_US = 2_000L
        const val MAX_IDLE_POLLS = 500 // 1 s
    }
}

/** Linear-interpolation resampler, used only when a custom recording isn't at the output rate. */
internal fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
    if (input.isEmpty() || from == to) return input
    val length = (input.size.toLong() * to / from).toInt()
    val step = from.toDouble() / to
    return FloatArray(length) { i ->
        val position = i * step
        val index = position.toInt()
        val next = minOf(index + 1, input.size - 1)
        val t = (position - index).toFloat()
        input[index] * (1 - t) + input[next] * t
    }
}

/** Thrown when segments cannot be loaded and the policy is [MissingAssetPolicy.FAIL]. */
public class MissingAudioException(
    /** Asset ids, e.g. "km:unit:thousand". */
    public val missing: List<String>,
) : Exception("Missing audio for: ${missing.joinToString(", ")}")

public enum class MissingAssetPolicy {
    /**
     * Refuse to speak a partial amount — in banking, skipping "thousand" would announce the wrong
     * number. Default.
     */
    FAIL,

    /** Skip unavailable segments. Only useful during development. */
    SKIP,
}

/** Output sample rate of composed utterances (the bundled clips are 44.1 kHz mono). */
public const val OUTPUT_SAMPLE_RATE: Int = 44_100

internal class ComposedUtterance(val sequence: RenderedSequence, val skipped: List<String>)

/**
 * Loads [tokens] (falling back from compounds to their parts) and joins them into one utterance.
 * Shared by playback and export so both produce identical audio.
 */
internal suspend fun composeUtterance(
    tokens: List<AudioToken>,
    loader: ClipLoader,
    options: RenderOptions,
    missingAssetPolicy: MissingAssetPolicy,
    resolver: AudioResolver,
): ComposedUtterance {
    val loaded = loader.load(tokens, resolver)
    val missing = loaded.filter { it.clips.isEmpty() }.map { it.token.id }.distinct()
    if (missing.isNotEmpty() && missingAssetPolicy == MissingAssetPolicy.FAIL) throw MissingAudioException(missing)
    val clips = loaded.flatMapIndexed { index, item -> item.clips.map { SequenceRenderer.Clip(index, it) } }
    return withContext(Dispatchers.Default) {
        ComposedUtterance(SequenceRenderer.render(clips, OUTPUT_SAMPLE_RATE, options), missing)
    }
}
