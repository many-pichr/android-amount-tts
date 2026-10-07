package io.github.manypichr.khmeramountspeech

import java.math.BigDecimal

/** Supported currencies. */
public enum class CurrencyCode(
    public val code: String,
    public val symbol: String,
    /** Maximum number of fraction digits accepted in input. */
    public val decimals: Int,
    /** Catalog value (type [AudioTokenType.CURRENCY]) for the major unit. */
    internal val majorUnit: String,
    /** Catalog value for the spoken minor unit, if any. */
    internal val minorUnit: String?,
) {
    KHR("KHR", "៛", 0, "riel", null),
    USD("USD", "$", 2, "dollar", "cent"),
}

/**
 * Phrases spoken around the amount.
 *
 * | Voice type    | Khmer                                  | English                                 |
 * | ------------- | -------------------------------------- | --------------------------------------- |
 * | `AMOUNT`      | {{amount}}                             | {{amount}}                              |
 * | `CONFIRM_PAY` | ទឹកប្រាក់ចំនួន{{amount}} សូមផ្ទៀងផ្ទាត់   | The amount is {{amount}}, please verify |
 * | `RECEIVED`    | ទទួលប្រាក់ចំនួន{{amount}}                | Received {{amount}}                     |
 */
public enum class VoiceType(public val id: String, internal val prefix: String?, internal val suffix: String?) {
    AMOUNT("amount", null, null),
    CONFIRM_PAY("confirm-pay", "confirm-prefix", "confirm-suffix"),
    RECEIVED("received", "received-prefix", null),
}

/** Invalid amount input. Each subclass is one reason; [message] is user-presentable. */
public sealed class AmountException(message: String) : IllegalArgumentException(message) {
    public class Empty : AmountException("Please enter an amount.")
    public class InvalidFormat : AmountException("Please enter a valid number, for example 1,250,000 or 25.50.")
    public class Negative : AmountException("The amount cannot be negative.")
    public class TooManyDecimals(public val currency: CurrencyCode) : AmountException(
        if (currency.decimals == 0) {
            "${currency.code} amounts must be whole numbers (no decimal places)."
        } else {
            "${currency.code} amounts allow at most ${currency.decimals} decimal places."
        },
    )
    public class TooLarge : AmountException("The amount is too large. The maximum is 999,999,999,999.")
}

/** A validated amount. Create one with [ParsedAmount.parse] or [ParsedAmount.of]. */
@ConsistentCopyVisibility
public data class ParsedAmount internal constructor(
    val currency: CurrencyCode,
    /** Whole major units (riel / dollars). */
    val major: Long,
    /** Minor units (cents), 0 when the currency has none. */
    val minor: Long,
    /** Canonical display string, e.g. "1,250,000" or "25.50". */
    val formatted: String,
) {
    public companion object {
        private val plain = Regex("""^(\d+)(?:\.(\d+))?$""")
        private val grouped = Regex("""^(\d{1,3}(?:,\d{3})+)(?:\.(\d+))?$""")

        /**
         * Parses user input such as "1,250,000", "25.5" or " 12.50 ". Parsing is entirely local.
         *
         * @throws AmountException if the input is not a valid amount for [currency].
         */
        @JvmStatic
        public fun parse(input: String, currency: CurrencyCode): ParsedAmount {
            val raw = input.filterNot { it.isWhitespace() }
            if (raw.isEmpty()) throw AmountException.Empty()
            if (raw.startsWith("-") || raw.startsWith("−")) throw AmountException.Negative()

            val match = plain.matchEntire(raw) ?: grouped.matchEntire(raw) ?: throw AmountException.InvalidFormat()
            val integerDigits = match.groupValues[1].replace(",", "").trimStart('0').ifEmpty { "0" }
            val fractionDigits = match.groupValues[2].trimEnd('0')

            if (fractionDigits.length > currency.decimals) throw AmountException.TooManyDecimals(currency)
            val major = if (integerDigits.length <= 12) integerDigits.toLongOrNull() else null
            if (major == null || major > NumberSpeech.MAXIMUM) throw AmountException.TooLarge()

            val minor = if (currency.decimals > 0) fractionDigits.padEnd(currency.decimals, '0').toLong() else 0L
            val integerText = "%,d".format(java.util.Locale.ROOT, major)
            val formatted = if (currency.decimals > 0) {
                integerText + "." + minor.toString().padStart(currency.decimals, '0')
            } else {
                integerText
            }
            return ParsedAmount(currency, major, minor, formatted)
        }

        /** Parses a decimal value, e.g. `BigDecimal("7.25")`. */
        @JvmStatic
        public fun of(value: BigDecimal, currency: CurrencyCode): ParsedAmount =
            parse(value.toPlainString(), currency)
    }
}

/**
 * An amount converted into text and the audio tokens that speak it.
 *
 * ```
 * AmountSpeech("1,250,000", CurrencyCode.KHR).text
 * // "មួយលានពីរសែនប្រាំម៉ឺនរៀល"
 * AmountSpeech("25.50", CurrencyCode.USD, SpeechLanguage.ENGLISH).text
 * // "twenty-five dollars fifty cents"
 * AmountSpeech("1,000", CurrencyCode.KHR, voiceType = VoiceType.RECEIVED).text
 * // "ទទួលប្រាក់ចំនួនមួយពាន់រៀល"
 * ```
 */
public class AmountSpeech @JvmOverloads constructor(
    public val amount: ParsedAmount,
    public val language: SpeechLanguage = SpeechLanguage.KHMER,
    public val voiceType: VoiceType = VoiceType.AMOUNT,
    useCompounds: Boolean = true,
) {
    public val text: String
    public val tokens: List<AudioToken>

    /** @throws AmountException if [input] is not a valid amount for [currency]. */
    @JvmOverloads
    public constructor(
        input: String,
        currency: CurrencyCode,
        language: SpeechLanguage = SpeechLanguage.KHMER,
        voiceType: VoiceType = VoiceType.AMOUNT,
        useCompounds: Boolean = true,
    ) : this(ParsedAmount.parse(input, currency), language, voiceType, useCompounds)

    init {
        val currency = amount.currency
        fun words(n: Long) = NumberSpeech.tokens(n, language, useCompounds)

        // English currency words take a plural form unless the count is 1; riel does not change.
        fun currencyToken(value: String, count: Long): AudioToken {
            if (language == SpeechLanguage.ENGLISH && count != 1L &&
                AudioCatalog.asset(AudioTokenType.CURRENCY, value + "s", language) != null
            ) {
                return AudioCatalog.token(AudioTokenType.CURRENCY, value + "s", language)
            }
            return AudioCatalog.token(AudioTokenType.CURRENCY, value, language)
        }

        fun join(tokens: List<AudioToken>) = AudioCatalog.joinText(tokens, language)

        val majorTokens = words(amount.major) + currencyToken(currency.majorUnit, amount.major)
        val minorUnit = currency.minorUnit
        var spokenText: String
        var spokenTokens: List<AudioToken>
        if (minorUnit != null && amount.minor > 0) {
            val minorTokens = words(amount.minor) + currencyToken(minorUnit, amount.minor)
            if (amount.major == 0L) {
                spokenText = join(minorTokens)
                spokenTokens = minorTokens
            } else {
                spokenText = "${join(majorTokens)} ${join(minorTokens)}"
                spokenTokens = majorTokens + minorTokens
            }
        } else {
            spokenText = join(majorTokens)
            spokenTokens = majorTokens
        }

        val before = voiceType.prefix?.let { AudioCatalog.token(AudioTokenType.PHRASE, it, language) }
        val after = voiceType.suffix?.let { AudioCatalog.token(AudioTokenType.PHRASE, it, language) }
        // Khmer is written without a space after the prefix; English separates words (and the
        // suffix with a comma).
        spokenText = when (language) {
            SpeechLanguage.KHMER -> (before?.text ?: "") + spokenText + (after?.let { " ${it.text}" } ?: "")
            SpeechLanguage.ENGLISH -> (before?.let { "${it.text} " } ?: "") + spokenText + (after?.let { ", ${it.text}" } ?: "")
        }
        spokenTokens = listOfNotNull(before) + spokenTokens + listOfNotNull(after)

        text = spokenText
        tokens = spokenTokens
    }

    override fun equals(other: Any?): Boolean =
        other is AmountSpeech && text == other.text && tokens == other.tokens && amount == other.amount &&
            language == other.language && voiceType == other.voiceType

    override fun hashCode(): Int = listOf(text, tokens, amount, language, voiceType).hashCode()

    override fun toString(): String = "AmountSpeech($text)"
}
