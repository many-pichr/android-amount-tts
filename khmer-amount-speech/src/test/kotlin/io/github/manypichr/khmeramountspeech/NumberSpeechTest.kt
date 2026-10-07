package io.github.manypichr.khmeramountspeech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NumberSpeechTest {
    private fun khmer(n: Long) = NumberSpeech.speak(n, SpeechLanguage.KHMER).text
    private fun english(n: Long) = NumberSpeech.speak(n, SpeechLanguage.ENGLISH).text
    private fun ids(n: Long, language: SpeechLanguage, compounds: Boolean = true) =
        NumberSpeech.tokens(n, language, compounds).map { "${it.type.key}:${it.value}" }

    @Test
    fun khmerFormalReading() {
        val cases = listOf(
            0L to "សូន្យ", 1L to "មួយ", 6L to "ប្រាំមួយ", 11L to "ដប់មួយ", 25L to "ម្ភៃប្រាំ", 99L to "កៅសិបប្រាំបួន",
            100L to "មួយរយ", 101L to "មួយរយមួយ", 1_000L to "មួយពាន់", 1_250L to "មួយពាន់ពីររយហាសិប",
            10_000L to "មួយម៉ឺន", 25_500L to "ពីរម៉ឺនប្រាំពាន់ប្រាំរយ", 125_000L to "មួយសែនពីរម៉ឺនប្រាំពាន់",
            999_999L to "ប្រាំបួនសែនប្រាំបួនម៉ឺនប្រាំបួនពាន់ប្រាំបួនរយកៅសិបប្រាំបួន", 1_000_000L to "មួយលាន",
            1_250_000L to "មួយលានពីរសែនប្រាំម៉ឺន", 10_500_000L to "ដប់លានប្រាំសែន", 100_000_000L to "មួយរយលាន",
            1_250_000_000L to "មួយពាន់ពីររយហាសិបលាន",
        )
        for ((n, expected) in cases) assertEquals("$n", expected, khmer(n))
    }

    @Test
    fun khmerCompounds() {
        for (d in 1L..9L) {
            for (value in listOf(d * 100, d * 1_000, d * 10_000, d * 100_000)) {
                assertEquals(listOf("compound:$value"), ids(value, SpeechLanguage.KHMER))
            }
        }
        assertEquals(listOf("compound:1000000"), ids(1_000_000, SpeechLanguage.KHMER))
        assertEquals(listOf("compound:1000000", "compound:200000", "compound:50000"), ids(1_250_000, SpeechLanguage.KHMER))
        assertEquals(listOf("number:20", "unit:million"), ids(20_000_000, SpeechLanguage.KHMER))
        assertEquals(
            listOf("number:1", "unit:million", "number:2", "unit:hundred-thousand", "number:5", "unit:ten-thousand"),
            ids(1_250_000, SpeechLanguage.KHMER, compounds = false),
        )
        val compound = NumberSpeech.tokens(3_000, SpeechLanguage.KHMER)[0]
        assertEquals(listOf("3", "thousand"), compound.parts?.map { it.value })
    }

    @Test
    fun englishReading() {
        val cases = listOf(
            0L to "zero", 13L to "thirteen", 25L to "twenty-five", 101L to "one hundred one",
            1_250L to "one thousand two hundred fifty", 15_000L to "fifteen thousand",
            125_000L to "one hundred twenty-five thousand", 1_250_000L to "one million two hundred fifty thousand",
            1_250_000_000L to "one billion two hundred fifty million",
        )
        for ((n, expected) in cases) assertEquals("$n", expected, english(n))
    }

    @Test
    fun englishCompounds() {
        for (d in 1L..9L) {
            for (value in listOf(d * 100, d * 1_000, d * 10_000, d * 100_000)) {
                assertEquals(listOf("compound:$value"), ids(value, SpeechLanguage.ENGLISH))
            }
        }
        assertEquals(
            listOf("compound:1000000", "compound:200", "number:50", "unit:thousand"),
            ids(1_250_000, SpeechLanguage.ENGLISH),
        )
        assertEquals(listOf("number:2", "unit:hundred", "unit:thousand"), ids(200_000, SpeechLanguage.ENGLISH, compounds = false))
    }

    @Test
    fun textIsConcatenationOfTokens() {
        for (n in listOf(0L, 7L, 1_001L, 25_500L, 1_250_000L, 999_999_999_999L)) {
            val spoken = NumberSpeech.speak(n, SpeechLanguage.KHMER)
            assertEquals(spoken.text, spoken.tokens.joinToString("") { it.text })
        }
    }

    @Test
    fun range() {
        assertThrows(NumberOutOfRangeException::class.java) { NumberSpeech.speak(-1, SpeechLanguage.KHMER) }
        assertThrows(NumberOutOfRangeException::class.java) {
            NumberSpeech.speak(NumberSpeech.MAXIMUM + 1, SpeechLanguage.ENGLISH)
        }
        NumberSpeech.speak(NumberSpeech.MAXIMUM, SpeechLanguage.KHMER)
    }

    @Test
    fun khmerDigits() {
        assertEquals("១,២៥០,០០០", toKhmerDigits("1,250,000"))
    }
}
