package io.github.manypichr.khmeramountspeech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public enum class PlaybackState { IDLE, LOADING, PLAYING, PAUSED }

public enum class PlaybackStatus { COMPLETED, STOPPED }

public data class PlaybackResult(
    val status: PlaybackStatus,
    /** Asset ids skipped because they were unavailable ([MissingAssetPolicy.SKIP] only). */
    val skipped: List<String>,
)

public data class PreloadResult(val loaded: Int, val failed: List<String>)

/**
 * Speaks amounts by composing bundled, pre-recorded Khmer/English segments.
 *
 * Everything is local: no amount or text ever leaves the device, and no TTS service is called.
 * The segments are trimmed, faded and joined into one buffer, so gaps are sample-accurate.
 * All methods are thread-safe; [speak] suspends until playback completes or is stopped.
 *
 * ```
 * val speaker = KhmerAmountSpeaker(context)
 * speaker.speak("1,250,000", CurrencyCode.KHR)                                   // Khmer
 * speaker.speak("25.50", CurrencyCode.USD, SpeechLanguage.ENGLISH)
 * speaker.speak("50,000", CurrencyCode.KHR, voiceType = VoiceType.RECEIVED)      // ទទួលប្រាក់ចំនួន…
 * ```
 */
public class KhmerAmountSpeaker @JvmOverloads constructor(
    context: Context,
    configuration: Configuration = Configuration(),
) {
    public data class Configuration @JvmOverloads constructor(
        val render: RenderOptions = RenderOptions(),
        /** Output volume 0…1. Default 1. */
        val volume: Float = 1f,
        val missingAssetPolicy: MissingAssetPolicy = MissingAssetPolicy.FAIL,
        /**
         * Request transient audio focus (ducking other audio) while speaking, and stop when focus
         * is lost. Disable if your app manages audio focus itself. Default true.
         */
        val managesAudioFocus: Boolean = true,
        /** Audio attributes of the output. Default: media usage, speech content. */
        val audioAttributes: AudioAttributes = defaultAudioAttributes(),
        /**
         * Where each segment's audio lives. Default (null): the clips bundled with this library.
         * Use [AudioResources.assetFolder] or [AudioResources.directory] to ship studio recordings.
         */
        val resolver: AudioResolver? = null,
    )

    private val appContext = context.applicationContext
    private val bundled = AudioResources.bundled(appContext)
    private val loader = ClipLoader(OUTPUT_SAMPLE_RATE, MediaCodecClipDecoder(appContext))
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val lock = Any()
    private var session = 0
    private var active: Active? = null
    private var focusRequest: Any? = null

    private class Active(val session: Int, val track: AudioTrack)

    /** Changing it while speaking applies the new volume immediately; other options apply to the next utterance. */
    public var configuration: Configuration = configuration
        set(value) {
            synchronized(lock) {
                field = value
                active?.track?.setVolume(value.volume)
            }
        }

    private val _state = MutableStateFlow(PlaybackState.IDLE)

    /** Current playback state. */
    public val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _tokenIndex = MutableStateFlow<Int?>(null)

    /** Index (into the token list) of the segment currently audible, or null — for highlighting. */
    public val tokenIndex: StateFlow<Int?> = _tokenIndex.asStateFlow()

    // Public API

    /**
     * Converts [input] and speaks it. Returns when playback completes or is stopped.
     *
     * @throws AmountException if [input] is invalid.
     * @throws MissingAudioException if clips are missing and the policy is [MissingAssetPolicy.FAIL].
     */
    public suspend fun speak(
        input: String,
        currency: CurrencyCode,
        language: SpeechLanguage = SpeechLanguage.KHMER,
        voiceType: VoiceType = VoiceType.AMOUNT,
    ): PlaybackResult = play(AmountSpeech(input, currency, language, voiceType).tokens)

    /** Speaks a prepared [AmountSpeech]. */
    public suspend fun speak(speech: AmountSpeech): PlaybackResult = play(speech.tokens)

    /**
     * Loads and plays [tokens] as one utterance. Calling it again while playing stops the previous
     * playback first, so utterances never overlap. Cancelling the calling coroutine stops playback.
     */
    public suspend fun play(tokens: List<AudioToken>): PlaybackResult {
        val config: Configuration
        val session = synchronized(lock) {
            stopLocked()
            config = configuration
            if (tokens.isNotEmpty()) _state.value = PlaybackState.LOADING
            session
        }
        if (tokens.isEmpty()) return PlaybackResult(PlaybackStatus.COMPLETED, emptyList())

        val track: AudioTrack
        val composed: ComposedUtterance
        try {
            composed = composeUtterance(tokens, loader, config.render, config.missingAssetPolicy, config.resolver ?: bundled)
            val samples = composed.sequence.samples
            if (samples.isEmpty()) {
                synchronized(lock) { if (session == this.session) _state.value = PlaybackState.IDLE }
                return PlaybackResult(PlaybackStatus.COMPLETED, composed.skipped)
            }
            if (!isCurrent(session)) return PlaybackResult(PlaybackStatus.STOPPED, emptyList())
            track = buildTrack(samples, config)
        } catch (e: Throwable) {
            synchronized(lock) { if (session == this.session) _state.value = PlaybackState.IDLE }
            throw e
        }

        val sequence = composed.sequence
        val frames = sequence.samples.size
        synchronized(lock) {
            if (session != this.session) {
                track.release()
                return PlaybackResult(PlaybackStatus.STOPPED, emptyList())
            }
            if (config.managesAudioFocus) requestFocusLocked(config.audioAttributes)
            try {
                track.play()
            } catch (e: IllegalStateException) {
                track.release()
                abandonFocusLocked()
                _state.value = PlaybackState.IDLE
                throw e
            }
            active = Active(session, track)
            _state.value = PlaybackState.PLAYING
        }

        // Poll the play head: drives token highlighting and detects the end of a static track.
        // The wall clock is a fallback for devices whose head position stops short of the end.
        val durationNanos = (sequence.duration * 1e9).toLong()
        var playedNanos = 0L
        var last = System.nanoTime()
        try {
            while (true) {
                synchronized(lock) {
                    if (session != this.session || active?.session != session) {
                        return PlaybackResult(PlaybackStatus.STOPPED, composed.skipped)
                    }
                    val now = System.nanoTime()
                    if (_state.value == PlaybackState.PLAYING) playedNanos += now - last
                    last = now
                    val position = track.playbackHeadPosition
                    _tokenIndex.value = sequence.tokenIndex(position)
                    if (position >= frames || playedNanos > durationNanos + END_GRACE_NANOS) {
                        releaseActiveLocked()
                        return PlaybackResult(PlaybackStatus.COMPLETED, composed.skipped)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            // Reached only on cancellation while this utterance is still active.
            synchronized(lock) { if (active?.session == session) stopLocked() }
        }
    }

    public fun pause() {
        synchronized(lock) {
            val active = active ?: return
            if (_state.value != PlaybackState.PLAYING) return
            active.track.pause()
            _state.value = PlaybackState.PAUSED
        }
    }

    public fun resume() {
        synchronized(lock) {
            val active = active ?: return
            if (_state.value != PlaybackState.PAUSED) return
            active.track.play()
            _state.value = PlaybackState.PLAYING
        }
    }

    /** Stops playback immediately. Safe to call at any time, from any thread. */
    public fun stop() {
        synchronized(lock) { stopLocked() }
    }

    /** Decodes common segments ahead of time so the first [speak] starts instantly. */
    public suspend fun preload(languages: Collection<SpeechLanguage> = SpeechLanguage.entries): PreloadResult {
        val tokens = AudioCatalog.assets.filter { it.preload && it.language in languages }.map { it.token }
        val loaded = loader.load(tokens, configuration.resolver ?: bundled)
        val failed = loaded.filter { it.clips.isEmpty() }.map { it.token.id }
        return PreloadResult(loaded.size - failed.size, failed)
    }

    // Private

    private fun isCurrent(session: Int) = synchronized(lock) { session == this.session }

    private fun stopLocked() {
        session += 1
        releaseActiveLocked()
    }

    private fun releaseActiveLocked() {
        active?.track?.let { track ->
            try {
                track.pause()
                track.flush()
                track.stop()
            } catch (_: IllegalStateException) {
            }
            track.release()
        }
        active = null
        abandonFocusLocked()
        _tokenIndex.value = null
        _state.value = PlaybackState.IDLE
    }

    private fun buildTrack(samples: FloatArray, config: Configuration): AudioTrack {
        val pcm = ShortArray(samples.size) { WavEncoder.toPcm16(samples[it]) }
        val track = AudioTrack.Builder()
            .setAudioAttributes(config.audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        if (track.state != AudioTrack.STATE_NO_STATIC_DATA && track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("Cannot create an audio output")
        }
        val written = track.write(pcm, 0, pcm.size)
        if (written != pcm.size) {
            track.release()
            throw IllegalStateException("Cannot write audio to the output (code $written)")
        }
        track.setVolume(config.volume)
        return track
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
    }

    private fun requestFocusLocked(attributes: AudioAttributes) {
        if (focusRequest != null) return
        if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            audioManager.requestAudioFocus(request)
            focusRequest = request
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            focusRequest = focusListener
        }
    }

    private fun abandonFocusLocked() {
        val request = focusRequest ?: return
        focusRequest = null
        if (Build.VERSION.SDK_INT >= 26 && request is AudioFocusRequest) {
            audioManager.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    public companion object {
        /** Output sample rate of the composed utterance (the bundled clips are 44.1 kHz mono). */
        public const val SAMPLE_RATE: Int = OUTPUT_SAMPLE_RATE

        private const val POLL_INTERVAL_MS = 25L
        private const val END_GRACE_NANOS = 500_000_000L

        @JvmStatic
        public fun defaultAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
