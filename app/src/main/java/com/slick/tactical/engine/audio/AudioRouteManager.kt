package com.slick.tactical.engine.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes critical TTS alerts through the Bluetooth SCO Hands-Free Profile.
 *
 * When a Cardo or Sena helmet is connected in Mesh Intercom mode, standard audio
 * (A2DP profile) is blocked by the active intercom channel. SCO (Hands-Free Profile)
 * interrupts the intercom as if it were an incoming phone call.
 *
 * IMPORTANT: Continuous wake word listening is BANNED (battery drain over BLE).
 * Only Tap-to-Talk via MediaSessionCompat is implemented -- wired in ConvoyForegroundService.
 *
 * Audio focus lifecycle: always release focus in [releaseAudioFocus].
 * Failing to release permanently mutes the rider's music/intercom.
 */
@Singleton
class AudioRouteManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isMuted: Boolean = false

    /**
     * Initializes the Text-to-Speech engine with Australian English locale.
     * Must be called before any [speakTacticalAlert] calls.
     *
     * @return Result indicating success or failure
     */
    fun initializeTts(): Result<Unit> {
        return try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.forLanguageTag("en-AU")
                    Timber.d("TTS engine initialized with Australian English")
                } else {
                    Timber.e("TTS initialization failed with status: %d", status)
                }
            }
            // TTS initialization is async -- return success and handle errors in speak
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "TTS initialization threw unexpectedly")
            Result.failure(Exception("TTS init failed: ${e.localizedMessage}", e))
        }
    }

    /**
     * Speaks [message] through the active Bluetooth headset via SCO profile.
     *
     * 1. Requests audio focus (ducks background music/intercom)
     * 2. Opens BLE SCO channel (interrupts Cardo/Sena intercom as a "call")
     * 3. Speaks the TTS message
     * 4. Releases audio focus (restores music/intercom)
     *
     * If [isMuted] is true (rider tapped Audio Mute in Zone 3), this is a no-op.
     *
     * @param message Tactical alert text in SLICK brand voice
     * @return Result indicating success or failure
     */
    suspend fun speakTacticalAlert(message: String): Result<Unit> {
        if (isMuted) {
            Timber.d("TTS muted -- skipping: %s", message)
            return Result.success(Unit)
        }

        val ttsEngine = tts ?: return Result.failure(Exception("TTS not initialized"))

        return try {
            // 1. Request audio focus
            if (!requestAudioFocus()) {
                return Result.failure(Exception("Audio focus request denied"))
            }

            // 2. Open SCO channel if Bluetooth headset is connected
            if (audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                delay(500L)  // Wait for SCO channel to establish
                Timber.d("SCO channel opened for TTS")
            }

            // 3. Speak via TTS
            ttsEngine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "slick_alert")
            Timber.i("TTS alert spoken: %s", message)

            // 4. Wait for speech to complete, then release (approximate -- TTS has no callback)
            delay(message.length * 65L + 1000L)

            releaseAudioFocus()
            Result.success(Unit)
        } catch (e: Exception) {
            releaseAudioFocus()  // Always release on error
            Timber.e(e, "TTS speak failed")
            Result.failure(Exception("TTS speak failed: ${e.localizedMessage}", e))
        }
    }

    /**
     * Toggles the audio mute state.
     * When muted, [speakTacticalAlert] is a no-op.
     * Tapped via the "AUDIO MUTE" button in Zone 3.
     *
     * @return The new mute state (true = muted)
     */
    fun toggleMute(): Boolean {
        isMuted = !isMuted
        Timber.i("Audio %s", if (isMuted) "MUTED" else "UNMUTED")
        return isMuted
    }

    val isMutedState: Boolean get() = isMuted

    /**
     * Releases TTS and audio resources. Call from [com.slick.tactical.service.ConvoyForegroundService.onDestroy].
     */
    fun shutdown() {
        releaseAudioFocus()
        tts?.shutdown()
        tts = null
        Timber.d("AudioRouteManager shutdown")
    }

    private fun requestAudioFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { /* Focus changes handled by TTS engine */ }
            .build()

        audioFocusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseAudioFocus() {
        if (audioManager.isBluetoothScoOn) {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        }
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }
}
