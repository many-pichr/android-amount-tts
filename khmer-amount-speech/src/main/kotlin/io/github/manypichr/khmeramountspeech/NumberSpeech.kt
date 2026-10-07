package io.github.manypichr.khmeramountspeech

/** Text plus the audio tokens that speak it. */
public data class SpokenText(val text: String, val tokens: List<AudioToken>)

/** Thrown by [NumberSpeech] for numbers outside 0…[NumberSpeech.MAXIMUM]. */
public class NumberOutOfRangeException(message: String) : IllegalArgumentException(message)

/** Number-to-words converters for Khmer and English. */
public object NumberSpeech {
    /** Largest supported value: 999,999,999,999 (999,999 លាន). */
    public const val MAXIMUM: Long = 999_999_999_999L

    /**
     * Converts a non-negative integer into words and audio tokens.
     *
     * @param useCompounds Use pre-recorded round amounts (មួយពាន់, "two hundred thousand", …)
     *   instead of separate digit and unit clips. Default `true`.
     */
    @JvmStatic
    @JvmOverloads
    public fun speak(n: Long, language: SpeechLanguage, useCompounds: Boolean = true): SpokenText {
        val tokens = tokens(n, language, useCompounds)
        return SpokenText(AudioCatalog.joinText(tokens, language), tokens)
    }

    @JvmStatic
    @JvmOverloads
    public fun tokens(n: Long, language: SpeechLanguage, useCompounds: Boolean = true): List<AudioToken> {
        if (n < 0) throw NumberOutOfRangeException("Expected a non-negative integer.")
        if (n > MAXIMUM) throw NumberOutOfRangeException("Numbers above $MAXIMUM are not supported.")
        return when (language) {
            SpeechLanguage.KHMER -> khmerTokens(n, useCompounds)
            SpeechLanguage.ENGLISH -> englishTokens(n, useCompounds)
        }
    }

    // Khmer

    /**
     * Khmer counts with native place-value words, read formally (every place uses its own word):
     * 1,250,000 → មួយលានពីរសែនប្រាំម៉ឺន. Values ≥ 1,000,000 are "<n> លាន", where <n> is itself a
     * Khmer number (1,250,000,000 → មួយពាន់ពីររយហាសិបលាន).
     */
    private fun khmerTokens(n: Long, useCompounds: Boolean): List<AudioToken> {
        fun num(v: Long) = AudioCatalog.token(AudioTokenType.NUMBER, v.toString(), SpeechLanguage.KHMER)
        fun unit(v: String) = AudioCatalog.token(AudioTokenType.UNIT, v, SpeechLanguage.KHMER)
        if (n == 0L) return listOf(num(0))

        // 1..99. Digits 6–9 are single recordings (ប្រាំមួយ…), which sound more natural.
        fun belowHundred(n: Long): List<AudioToken> = buildList {
            if (n / 10 > 0) add(num(n / 10 * 10))
            if (n % 10 > 0) add(num(n % 10))
        }

        // 1..999,999
        fun belowMillion(n: Long): List<AudioToken> = buildList {
            val places = listOf(
                n / 100_000 to "hundred-thousand", n / 10_000 % 10 to "ten-thousand",
                n / 1_000 % 10 to "thousand", n / 100 % 10 to "hundred",
            )
            for ((digit, name) in places) if (digit > 0) addAll(listOf(num(digit), unit(name)))
            addAll(belowHundred(n % 100))
        }

        val out = buildList {
            if (n / 1_000_000 > 0) addAll(belowMillion(n / 1_000_000) + unit("million"))
            if (n % 1_000_000 > 0) addAll(belowMillion(n % 1_000_000))
        }
        return if (useCompounds) mergeKhmerCompounds(out) else out
    }

    /** Replaces each single-digit number followed by a unit with its compound, when one exists. */
    private fun mergeKhmerCompounds(tokens: List<AudioToken>): List<AudioToken> {
        val out = mutableListOf<AudioToken>()
        var i = 0
        while (i < tokens.size) {
            val compound = if (i + 1 < tokens.size && tokens[i].type == AudioTokenType.NUMBER &&
                tokens[i + 1].type == AudioTokenType.UNIT
            ) {
                AudioCatalog.compound(listOf(tokens[i], tokens[i + 1]))
            } else {
                null
            }
            if (compound != null) {
                out += compound
                i += 2
            } else {
                out += tokens[i]
                i += 1
            }
        }
        return out
    }

    // English

    /** Short scale in groups of three digits: 1,250,000 → "one million two hundred fifty thousand". */
    private fun englishTokens(n: Long, useCompounds: Boolean): List<AudioToken> {
        fun num(v: Long) = AudioCatalog.token(AudioTokenType.NUMBER, v.toString(), SpeechLanguage.ENGLISH)
        fun unit(v: String) = AudioCatalog.token(AudioTokenType.UNIT, v, SpeechLanguage.ENGLISH)
        if (n == 0L) return listOf(num(0))

        fun maybeCompound(parts: List<AudioToken>, allowed: Boolean): List<AudioToken> =
            (if (allowed) AudioCatalog.compound(parts) else null)?.let { listOf(it) } ?: parts

        // 1..99: 1–19 and the tens are single words; others are "<tens> <ones>".
        fun belowHundred(n: Long): List<AudioToken> = when {
            n < 20 -> listOf(num(n))
            n % 10 > 0 -> listOf(num(n - n % 10), num(n % 10))
            else -> listOf(num(n))
        }

        // 1..999
        fun belowThousand(n: Long, compounds: Boolean): List<AudioToken> = buildList {
            if (n / 100 > 0) addAll(maybeCompound(listOf(num(n / 100), unit("hundred")), compounds))
            if (n % 100 > 0) addAll(belowHundred(n % 100))
        }

        val out = mutableListOf<AudioToken>()
        for ((scale, name) in listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")) {
            val group = n / scale % 1000
            if (group == 0L) continue
            // Prefer one compound for the whole group ("two hundred thousand"), else per word.
            val whole = if (useCompounds) AudioCatalog.compound(belowThousand(group, false) + unit(name)) else null
            out += whole?.let { listOf(it) } ?: (belowThousand(group, useCompounds) + unit(name))
        }
        if (n % 1000 > 0) out += belowThousand(n % 1000, useCompounds)
        return out
    }
}

private val khmerDigits = charArrayOf('០', '១', '២', '៣', '៤', '៥', '៦', '៧', '៨', '៩')

/** "1,250,000" → "១,២៥០,០០០" */
public fun toKhmerDigits(value: String): String =
    value.map { if (it in '0'..'9') khmerDigits[it - '0'] else it }.joinToString("")
