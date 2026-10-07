package io.github.manypichr.khmeramountspeech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Renders amounts to WAV audio without playing them — e.g. to send to a payment terminal,
 * cache per transaction, attach to a notification, or play with your own audio stack.
 *
 * The output is exactly what [KhmerAmountSpeaker] plays: mono, 44.1 kHz, with the same trimming,
 * fades, gaps and compound fallback. Safe to use from any thread or coroutine.
 *
 * ```
 * val exporter = AmountAudioExporter(context)
 * val wav: ByteArray = exporter.wavData("1,250,000", CurrencyCode.KHR)
 * exporter.writeWav("50,000", CurrencyCode.KHR, voiceType = VoiceType.RECEIVED, file = file)
 * ```
 */
public class AmountAudioExporter internal constructor(
    public val render: RenderOptions,
    public val missingAssetPolicy: MissingAssetPolicy,
    public val sampleFormat: WavSampleFormat,
    private val resolver: AudioResolver,
    private val loader: ClipLoader,
    /** Default folder for [speechWav]: `<cacheDir>/KhmerAmountSpeech`. */
    public val defaultDirectory: File,
) {
    @JvmOverloads
    public constructor(
        context: Context,
        render: RenderOptions = RenderOptions(),
        missingAssetPolicy: MissingAssetPolicy = MissingAssetPolicy.FAIL,
        sampleFormat: WavSampleFormat = WavSampleFormat.PCM16,
        resolver: AudioResolver = AudioResources.bundled(context),
    ) : this(
        render, missingAssetPolicy, sampleFormat, resolver,
        ClipLoader(OUTPUT_SAMPLE_RATE, MediaCodecClipDecoder(context)),
        File(context.cacheDir, "KhmerAmountSpeech"),
    )

    // WAV data

    /** Converts [input] and returns the spoken amount as a WAV file in memory. */
    public suspend fun wavData(
        input: String,
        currency: CurrencyCode,
        language: SpeechLanguage = SpeechLanguage.KHMER,
        voiceType: VoiceType = VoiceType.AMOUNT,
    ): ByteArray = wavData(AmountSpeech(input, currency, language, voiceType))

    public suspend fun wavData(speech: AmountSpeech): ByteArray = wavData(speech.tokens)

    public suspend fun wavData(tokens: List<AudioToken>): ByteArray {
        val sequence = renderSequence(tokens)
        return withContext(Dispatchers.Default) { WavEncoder.encode(sequence.samples, sequence.sampleRate, sampleFormat) }
    }

    // WAV files

    /** Converts [input] and writes the spoken amount to a `.wav` file (overwriting it). */
    public suspend fun writeWav(
        input: String,
        currency: CurrencyCode,
        language: SpeechLanguage = SpeechLanguage.KHMER,
        voiceType: VoiceType = VoiceType.AMOUNT,
        file: File,
    ) {
        writeWav(AmountSpeech(input, currency, language, voiceType), file)
    }

    public suspend fun writeWav(speech: AmountSpeech, file: File) {
        writeWav(speech.tokens, file)
    }

    public suspend fun writeWav(tokens: List<AudioToken>, file: File) {
        val data = wavData(tokens)
        withContext(Dispatchers.IO) { writeAtomically(file, data) }
    }

    // Text to speech file

    /**
     * Converts [input] to speech, saves it as a `.wav` file and returns the file.
     *
     * ```
     * val file = exporter.speechWav("50,000", CurrencyCode.KHR, voiceType = VoiceType.RECEIVED)
     * file.absolutePath   // ".../cache/KhmerAmountSpeech/<UUID>.wav"
     * ```
     *
     * @param directory Folder to write into, created if needed. Defaults to [defaultDirectory]
     *   (`<cacheDir>/KhmerAmountSpeech`); the system may purge it, so move the file if you keep it.
     * @param fileName File name, with or without the `.wav` extension. Defaults to a new UUID so calls
     *   never overwrite each other. An existing file with the same name is replaced.
     */
    public suspend fun speechWav(
        input: String,
        currency: CurrencyCode,
        language: SpeechLanguage = SpeechLanguage.KHMER,
        voiceType: VoiceType = VoiceType.AMOUNT,
        directory: File? = null,
        fileName: String? = null,
    ): File = speechWav(AmountSpeech(input, currency, language, voiceType), directory, fileName)

    public suspend fun speechWav(speech: AmountSpeech, directory: File? = null, fileName: String? = null): File =
        speechWav(speech.tokens, directory, fileName)

    public suspend fun speechWav(tokens: List<AudioToken>, directory: File? = null, fileName: String? = null): File {
        val folder = directory ?: defaultDirectory
        val name = fileName ?: UUID.randomUUID().toString()
        val file = File(folder, if (name.lowercase().endsWith(".wav")) name else "$name.wav")
        withContext(Dispatchers.IO) {
            if (!folder.isDirectory && !folder.mkdirs()) throw java.io.IOException("Cannot create $folder")
        }
        writeWav(tokens, file)
        return file
    }

    // Raw samples

    /** The rendered utterance as mono Float samples, with the sample range of each token. */
    public suspend fun renderSequence(tokens: List<AudioToken>): RenderedSequence =
        composeUtterance(tokens, loader, render, missingAssetPolicy, resolver).sequence

    private fun writeAtomically(file: File, data: ByteArray) {
        val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeBytes(data)
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) throw java.io.IOException("Cannot write $file")
            }
        } finally {
            temp.delete()
        }
    }
}
