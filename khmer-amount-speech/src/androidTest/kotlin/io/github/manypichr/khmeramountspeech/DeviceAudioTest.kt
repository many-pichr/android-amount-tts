package io.github.manypichr.khmeramountspeech

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceAudioTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyCatalogAssetResolves() {
        val resolver = AudioResources.bundled(context)
        assertEquals(emptyList<String>(), AudioCatalog.assets.filter { resolver.resolve(it.token) == null }.map { it.id })
    }

    @Test
    fun decodesBundledClips(): Unit = runBlocking {
        val loader = ClipLoader(44_100, MediaCodecClipDecoder(context))
        val tokens = listOf(
            AudioCatalog.token(AudioTokenType.COMPOUND, "1000", SpeechLanguage.KHMER),
            AudioCatalog.token(AudioTokenType.CURRENCY, "dollars", SpeechLanguage.ENGLISH),
        )
        for (item in loader.load(tokens, AudioResources.bundled(context))) {
            assertEquals(1, item.clips.size)
            // Every bundled word is between 0.1 s and 2 s of audio.
            assertTrue(item.clips[0].size in 4_411 until 88_200)
        }
    }

    @Test
    fun preloadDecodesEveryCommonClip(): Unit = runBlocking {
        val result = KhmerAmountSpeaker(context).preload()
        assertEquals(emptyList<String>(), result.failed)
        assertEquals(AudioCatalog.assets.count { it.preload }, result.loaded)
    }

    @Test
    fun exportsAmountAsWav(): Unit = runBlocking {
        val exporter = AmountAudioExporter(context)
        val speech = AmountSpeech("50,000", CurrencyCode.KHR, voiceType = VoiceType.RECEIVED)
        val sequence = exporter.renderSequence(speech.tokens)
        assertEquals(setOf(0, 1, 2), sequence.segments.map { it.tokenIndex }.toSet())
        assertTrue(sequence.duration > 1 && sequence.duration < 6) // ទទួលប្រាក់ចំនួន + ប្រាំម៉ឺន + រៀល
        val file = exporter.speechWav(speech)
        assertEquals(44L + sequence.samples.size * 2, file.length())

        // The platform reads it back as mono 44.1 kHz audio.
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(44_100, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        } finally {
            extractor.release()
            file.delete()
        }
    }

    @Test
    fun speaksToCompletion(): Unit = runBlocking {
        val speaker = KhmerAmountSpeaker(context, KhmerAmountSpeaker.Configuration(volume = 0f))
        val indexes = mutableListOf<Int>()
        val watcher = async {
            speaker.tokenIndex.collect { if (it != null && indexes.lastOrNull() != it) indexes += it }
        }
        val result = speaker.speak("1,000", CurrencyCode.KHR, voiceType = VoiceType.RECEIVED)
        watcher.cancel()
        assertEquals(PlaybackStatus.COMPLETED, result.status)
        assertEquals(PlaybackState.IDLE, speaker.state.value)
        assertEquals(0, indexes.first())
        assertEquals(2, indexes.last()) // phrase, compound:1000, riel
    }

    @Test
    fun stopResolvesAsStopped(): Unit = runBlocking {
        val speaker = KhmerAmountSpeaker(context, KhmerAmountSpeaker.Configuration(volume = 0f))
        val playing = async { speaker.speak("1,250,000", CurrencyCode.KHR) }
        repeat(400) { if (speaker.state.value != PlaybackState.PLAYING) delay(5) }
        assertEquals(PlaybackState.PLAYING, speaker.state.value)
        speaker.stop()
        assertEquals(PlaybackStatus.STOPPED, playing.await().status)
        assertEquals(PlaybackState.IDLE, speaker.state.value)
    }

    @Test
    fun missingAudioFailsByDefault(): Unit = runBlocking {
        val speaker = KhmerAmountSpeaker(context, KhmerAmountSpeaker.Configuration(volume = 0f, resolver = { null }))
        try {
            speaker.speak("1,000", CurrencyCode.KHR)
            fail("Expected MissingAudioException")
        } catch (e: MissingAudioException) {
            assertEquals(listOf("km:compound:1000", "km:currency:riel"), e.missing)
        }
        assertEquals(PlaybackState.IDLE, speaker.state.value)
    }
}
