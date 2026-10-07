package io.github.manypichr.khmeramountspeech

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class AudioTest {
    @Test
    fun everyCatalogAssetIsBundled() {
        // Unit tests run with the module directory as the working directory.
        val root = File("src/main/assets/${AudioResources.ASSET_ROOT}")
        val missing = AudioCatalog.assets.filter { !File(root, it.token.resourcePath + ".mp3").isFile }.map { it.id }
        assertEquals(emptyList<String>(), missing)
        assertEquals(68, AudioCatalog.assets.count { it.language == SpeechLanguage.KHMER })
        assertEquals(77, AudioCatalog.assets.count { it.language == SpeechLanguage.ENGLISH })
    }

    @Test
    fun catalogIdsAreUnique() {
        assertEquals(AudioCatalog.assets.size, AudioCatalog.assets.map { it.id }.toSet().size)
    }

    @Test
    fun compoundFallsBackToParts(): Unit = runBlocking {
        val loader = ClipLoader(44_100) { _, _ -> FloatArray(100) { 0.5f } }
        val compound = NumberSpeech.tokens(2_000, SpeechLanguage.KHMER)[0]
        val withoutCompounds = AudioResolver { token ->
            if (token.type == AudioTokenType.COMPOUND) null else AudioSource.Asset(token.resourcePath)
        }
        assertEquals(2, loader.load(listOf(compound), withoutCompounds)[0].clips.size)
        assertEquals(0, loader.load(listOf(compound)) { null }[0].clips.size)
    }

    @Test
    fun loaderCachesDecodedClips(): Unit = runBlocking {
        var decodes = 0
        val loader = ClipLoader(44_100) { _, _ -> synchronized(this) { decodes++ }; FloatArray(10) }
        val token = AudioCatalog.token(AudioTokenType.NUMBER, "1", SpeechLanguage.KHMER)
        val resolver = AudioResolver { AudioSource.Asset(it.resourcePath) }
        loader.load(listOf(token, token), resolver)
        loader.load(listOf(token), resolver)
        assertEquals(1, decodes)
    }

    @Test
    fun resampleChangesLength() {
        assertEquals(44_100, resample(FloatArray(22_050) { 0.1f }, 22_050, 44_100).size)
    }

    // SequenceRenderer (sample rate 1000 → 1 sample per ms)

    private fun clip(index: Int, lead: Int = 20, speech: Int = 200, tail: Int = 30) =
        SequenceRenderer.Clip(index, FloatArray(lead) + FloatArray(speech) { 0.5f } + FloatArray(tail))

    @Test
    fun trimsSilenceAndInsertsGap() {
        val options = RenderOptions(gapMs = 30.0, fadeMs = 0.0, trimPaddingMs = 0.0)
        val out = SequenceRenderer.render(listOf(clip(0), clip(1)), 1000, options)
        assertEquals(listOf(0 until 200, 230 until 430), out.segments.map { it.range })
        assertEquals(430, out.samples.size)
        assertEquals(0f, out.samples[215])
        assertEquals(1, out.tokenIndex(300))
        assertNull(out.tokenIndex(215))
    }

    @Test
    fun negativeGapCrossfades() {
        val options = RenderOptions(gapMs = -20.0, fadeMs = 0.0, trimPaddingMs = 0.0)
        val out = SequenceRenderer.render(listOf(clip(0), clip(1)), 1000, options)
        assertEquals(180, out.segments[1].start)
        assertEquals(1.0f, out.samples[190], 0.0001f) // both words mixed
    }

    @Test
    fun fadeEnvelope() {
        val options = RenderOptions(gapMs = 0.0, fadeMs = 8.0, trimPaddingMs = 0.0)
        val out = SequenceRenderer.render(listOf(clip(0)), 1000, options)
        assertEquals(0f, out.samples[0])
        assertEquals(0.25f, out.samples[4], 0.0001f)
        assertEquals(0.5f, out.samples[100])
        assertEquals(0f, out.samples[199])
    }

    @Test
    fun fallbackClipsShareTokenIndex() {
        val out = SequenceRenderer.render(listOf(clip(0), clip(0), clip(1)), 1000)
        assertEquals(listOf(0, 0, 1), out.segments.map { it.tokenIndex })
    }
}
