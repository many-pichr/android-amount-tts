package io.github.manypichr.khmeramountspeech

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExporterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun le(data: ByteArray) = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private fun ascii(data: ByteArray, from: Int) = String(data, from, 4, Charsets.US_ASCII)

    /** Exporter whose "clips" are 0.2 s tones, so tests run on the JVM without MediaCodec. */
    private fun exporter(
        resolver: AudioResolver = AudioResolver { AudioSource.Asset(it.resourcePath) },
        format: WavSampleFormat = WavSampleFormat.PCM16,
    ) = AmountAudioExporter(
        RenderOptions(), MissingAssetPolicy.FAIL, format, resolver,
        ClipLoader(OUTPUT_SAMPLE_RATE) { _, _ -> FloatArray(8_820) { 0.5f } },
        temp.root.resolve("default"),
    )

    @Test
    fun encoderHeaderPcm16() {
        val data = WavEncoder.encode(floatArrayOf(0f, 0.5f, -1f, 2f), 44_100)
        val b = le(data)
        assertEquals("RIFF", ascii(data, 0))
        assertEquals("WAVE", ascii(data, 8))
        assertEquals(1, b.getShort(20).toInt())    // PCM
        assertEquals(1, b.getShort(22).toInt())    // mono
        assertEquals(44_100, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(8, b.getInt(40))               // 4 samples × 2 bytes
        assertEquals(44 + 8, data.size)
        assertEquals(data.size - 8, b.getInt(4))
        assertEquals(Short.MAX_VALUE, b.getShort(50)) // 2 is clipped to full scale.
    }

    @Test
    fun encoderFloat32() {
        val data = WavEncoder.encode(floatArrayOf(0.25f), 44_100, WavSampleFormat.FLOAT32)
        val b = le(data)
        assertEquals(3, b.getShort(20).toInt())    // IEEE float
        assertEquals(32, b.getShort(34).toInt())
        assertEquals(0.25f, b.getFloat(44))
    }

    @Test
    fun exportsEveryToken(): Unit = runBlocking {
        val speech = AmountSpeech("50,000", CurrencyCode.KHR, voiceType = VoiceType.RECEIVED)
        val sequence = exporter().renderSequence(speech.tokens)
        assertEquals(setOf(0, 1, 2), sequence.segments.map { it.tokenIndex }.toSet())
        val data = exporter().wavData(speech)
        assertEquals(44 + sequence.samples.size * 2, data.size)
    }

    @Test
    fun speechWavReturnsFile(): Unit = runBlocking {
        val exporter = exporter()
        val file = exporter.speechWav("50,000", CurrencyCode.KHR, voiceType = VoiceType.RECEIVED)
        assertEquals("wav", file.extension)
        assertEquals(exporter.defaultDirectory, file.parentFile)
        assertArrayEquals(exporter.wavData("50,000", CurrencyCode.KHR, voiceType = VoiceType.RECEIVED), file.readBytes())

        // A second call gets its own file.
        val other = exporter.speechWav("50,000", CurrencyCode.KHR, voiceType = VoiceType.RECEIVED)
        assertNotEquals(file, other)
    }

    @Test
    fun speechWavCustomDirectoryAndName(): Unit = runBlocking {
        val folder = temp.root.resolve("export/nested")
        val speech = AmountSpeech("25.50", CurrencyCode.USD, SpeechLanguage.ENGLISH)
        val file = exporter().speechWav(speech, folder, "order-42")
        assertEquals("order-42.wav", file.name)
        assertTrue(file.isFile)
        assertEquals(file, exporter().speechWav(speech, folder, "order-42.wav"))
        assertEquals(listOf("order-42.wav"), folder.list()!!.toList())
    }

    @Test
    fun missingAudioFailsByDefault(): Unit = runBlocking {
        try {
            exporter(resolver = { null }).wavData("1,000", CurrencyCode.KHR)
            fail("Expected MissingAudioException")
        } catch (e: MissingAudioException) {
            assertEquals(listOf("km:compound:1000", "km:currency:riel"), e.missing)
        }
    }

    @Test
    fun invalidInputThrowsAmountException(): Unit = runBlocking {
        try {
            exporter().wavData("abc", CurrencyCode.KHR)
            fail("Expected AmountException")
        } catch (e: AmountException) {
            assertTrue(e is AmountException.InvalidFormat)
        }
    }
}
