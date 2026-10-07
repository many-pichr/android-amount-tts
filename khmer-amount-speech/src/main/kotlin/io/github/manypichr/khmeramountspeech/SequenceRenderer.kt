package io.github.manypichr.khmeramountspeech

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How segments are joined into one utterance. */
public data class RenderOptions @JvmOverloads constructor(
    /** Silence between words in ms. Negative values overlap neighbouring words (crossfade). Default 30. */
    val gapMs: Double = 30.0,
    /** Fade-in/out applied to each segment in ms (anti-click). Default 8. */
    val fadeMs: Double = 8.0,
    /** Trim leading/trailing silence in each clip. Default true. */
    val trimSilence: Boolean = true,
    /** Linear amplitude treated as silence when trimming. Default 0.01 (≈ -40 dBFS). */
    val silenceThreshold: Float = 0.01f,
    /** Audio kept before/after the detected speech when trimming, in ms. Default 12. */
    val trimPaddingMs: Double = 12.0,
)

/** A mono utterance plus where each token sits in it (for highlighting). */
public class RenderedSequence(
    public val samples: FloatArray,
    public val sampleRate: Int,
    public val segments: List<Segment>,
) {
    /** Token [tokenIndex] is audible in samples [start] (inclusive) until [end] (exclusive). */
    public data class Segment(val tokenIndex: Int, val start: Int, val end: Int) {
        val range: IntRange get() = start until end
    }

    /** Duration in seconds. */
    public val duration: Double get() = samples.size.toDouble() / sampleRate

    /** Index of the token audible at [sample], if any. */
    public fun tokenIndex(atSample: Int): Int? =
        segments.lastOrNull { atSample >= it.start && atSample < it.end }?.tokenIndex
}

/**
 * Joins mono clips into one buffer, sample-accurately. Pure and platform-independent, so the
 * timing rules can be unit-tested without an audio device.
 */
public object SequenceRenderer {
    /**
     * One decoded clip belonging to the token at [tokenIndex]. A compound that fell back to its
     * parts contributes several clips with the same index.
     */
    public class Clip(public val tokenIndex: Int, public val samples: FloatArray)

    /** Region of [samples] that contains speech, padded by [paddingSamples]. End is exclusive. */
    @JvmStatic
    public fun speechRange(samples: FloatArray, threshold: Float, paddingSamples: Int): IntRange {
        val first = samples.indexOfFirst { abs(it) >= threshold }
        val last = samples.indexOfLast { abs(it) >= threshold }
        if (first < 0 || last < 0) return samples.indices
        return max(0, first - paddingSamples) until min(samples.size, last + 1 + paddingSamples)
    }

    @JvmStatic
    @JvmOverloads
    public fun render(clips: List<Clip>, sampleRate: Int, options: RenderOptions = RenderOptions()): RenderedSequence {
        fun ms(value: Double) = (value / 1000 * sampleRate).roundToInt()
        val gap = ms(options.gapMs)
        var output = FloatArray(0)
        var outputSize = 0
        val segments = mutableListOf<RenderedSequence.Segment>()
        var cursor = 0

        for (clip in clips) {
            val range = if (options.trimSilence) {
                speechRange(clip.samples, options.silenceThreshold, ms(options.trimPaddingMs))
            } else {
                clip.samples.indices
            }
            val length = range.last - range.first + 1
            if (length <= 0) continue
            val fade = min(ms(options.fadeMs), length / 4)
            val start = cursor
            if (outputSize < start + length) {
                if (output.size < start + length) output = output.copyOf(max(start + length, output.size * 2))
                outputSize = start + length
            }

            for (i in 0 until length) {
                var gain = 1f
                if (fade > 0) {
                    if (i < fade) gain = i.toFloat() / fade
                    else if (i >= length - fade) gain = (length - 1 - i).toFloat() / fade
                }
                // Mix (not overwrite) so negative gaps crossfade.
                output[start + i] += clip.samples[range.first + i] * gain
            }

            segments += RenderedSequence.Segment(clip.tokenIndex, start, start + length)
            // Negative gaps overlap words but never by more than half a word.
            cursor = start + max(length + gap, length / 2)
        }
        return RenderedSequence(output.copyOf(outputSize), sampleRate, segments)
    }
}
