package io.github.manypichr.khmeramountspeech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

class AmountSpeechTest {
    @Test
    fun khmerAmounts() {
        val cases = listOf(
            Triple("1,250,000", CurrencyCode.KHR, "មួយលានពីរសែនប្រាំម៉ឺនរៀល"),
            Triple("1250", CurrencyCode.KHR, "មួយពាន់ពីររយហាសិបរៀល"),
            Triple("25.50", CurrencyCode.USD, "ម្ភៃប្រាំដុល្លារ ហាសិបសេន"),
            Triple("12.5", CurrencyCode.USD, "ដប់ពីរដុល្លារ ហាសិបសេន"),
            Triple("0.05", CurrencyCode.USD, "ប្រាំសេន"),
            Triple("10", CurrencyCode.USD, "ដប់ដុល្លារ"),
        )
        for ((input, currency, expected) in cases) assertEquals(input, expected, AmountSpeech(input, currency).text)
    }

    @Test
    fun englishAmounts() {
        val cases = listOf(
            Triple("1,250,000", CurrencyCode.KHR, "one million two hundred fifty thousand riel"),
            Triple("1", CurrencyCode.USD, "one dollar"),
            Triple("1.01", CurrencyCode.USD, "one dollar one cent"),
            Triple("25.50", CurrencyCode.USD, "twenty-five dollars fifty cents"),
            Triple("1,000,000", CurrencyCode.USD, "one million dollars"),
        )
        for ((input, currency, expected) in cases) {
            assertEquals(input, expected, AmountSpeech(input, currency, SpeechLanguage.ENGLISH).text)
        }
    }

    @Test
    fun voiceTypes() {
        val confirm = AmountSpeech("1,000", CurrencyCode.KHR, voiceType = VoiceType.CONFIRM_PAY)
        assertEquals("ទឹកប្រាក់ចំនួនមួយពាន់រៀល សូមផ្ទៀងផ្ទាត់", confirm.text)
        assertEquals(
            listOf("km:phrase:confirm-prefix", "km:compound:1000", "km:currency:riel", "km:phrase:confirm-suffix"),
            confirm.tokens.map { it.id },
        )
        assertEquals(
            "ទទួលប្រាក់ចំនួនម្ភៃប្រាំដុល្លារ ហាសិបសេន",
            AmountSpeech("25.50", CurrencyCode.USD, voiceType = VoiceType.RECEIVED).text,
        )
        assertEquals(
            "The amount is one thousand riel, please verify",
            AmountSpeech("1,000", CurrencyCode.KHR, SpeechLanguage.ENGLISH, VoiceType.CONFIRM_PAY).text,
        )
        assertEquals(
            "Received one dollar",
            AmountSpeech("1", CurrencyCode.USD, SpeechLanguage.ENGLISH, VoiceType.RECEIVED).text,
        )
    }

    @Test
    fun parsing() {
        val parsed = ParsedAmount.parse(" 1,250,000 ", CurrencyCode.KHR)
        assertEquals(1_250_000L, parsed.major)
        assertEquals("1,250,000", parsed.formatted)
        assertEquals("25.50", ParsedAmount.parse("25.5", CurrencyCode.USD).formatted)
        assertEquals(50L, ParsedAmount.parse("0012.50", CurrencyCode.USD).minor)
        assertEquals("7.25", ParsedAmount.of(BigDecimal("7.25"), CurrencyCode.USD).formatted)
        assertEquals("0", ParsedAmount.parse("000", CurrencyCode.KHR).formatted)
        assertEquals("999,999,999,999", ParsedAmount.parse("999999999999", CurrencyCode.KHR).formatted)
    }

    @Test
    fun parsingErrors() {
        val errors = listOf<Triple<String, CurrencyCode, Class<out AmountException>>>(
            Triple("", CurrencyCode.KHR, AmountException.Empty::class.java),
            Triple("abc", CurrencyCode.KHR, AmountException.InvalidFormat::class.java),
            Triple("1,25,000", CurrencyCode.KHR, AmountException.InvalidFormat::class.java),
            Triple("-5", CurrencyCode.KHR, AmountException.Negative::class.java),
            Triple("10.5", CurrencyCode.KHR, AmountException.TooManyDecimals::class.java),
            Triple("1.234", CurrencyCode.USD, AmountException.TooManyDecimals::class.java),
            Triple("1,000,000,000,000", CurrencyCode.KHR, AmountException.TooLarge::class.java),
        )
        for ((input, currency, expected) in errors) {
            try {
                ParsedAmount.parse(input, currency)
                fail("Expected ${expected.simpleName} for '$input'")
            } catch (e: AmountException) {
                assertTrue("'$input' threw $e", expected.isInstance(e))
            }
        }
        try {
            ParsedAmount.parse("10.5", CurrencyCode.KHR)
        } catch (e: AmountException.TooManyDecimals) {
            assertEquals(CurrencyCode.KHR, e.currency)
            assertEquals("KHR amounts must be whole numbers (no decimal places).", e.message)
        }
    }
}
