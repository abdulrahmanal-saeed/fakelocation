package com.abdulrahman.fakelocation.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.abdulrahman.fakelocation.data.Prefs
import kotlin.math.ceil

/**
 * Makes the navigation voice heard over loud music: pauses (or lowers) whatever is playing, turns the volume up
 * for the sentence and back down after, boosts the voice itself, and can send it over the Bluetooth "call" channel,
 * which makes most car stereos cut the radio.
 */
class VoiceOutput(context: Context) {

    /** How loud the voice is: share of the maximum volume while it talks, plus extra boost (millibels). */
    enum class Level(val streamShare: Float, val boostMb: Int) {
        MEDIA(0f, 0), LOUD(0.8f, 600), LOUDER(0.92f, 1000), MAX(1f, 1500)
    }

    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    /** The voice engine plays into this audio session, so the boost can be applied to it alone. */
    val sessionId: Int = audio.generateAudioSessionId()
    private var focus: AudioFocusRequest? = null
    private var enhancer: LoudnessEnhancer? = null
    private var savedVolume = -1
    private var raisedTo = -1
    private var stream = AudioManager.STREAM_MUSIC
    private var callChannel = false
    private var talking = 0

    private val prefs get() = Prefs(app)

    /** What the voice engine should play as (navigation guidance, or a call for the Bluetooth option). */
    fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (prefs.voiceOverCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Before a sentence: quiet the music, raise the volume, open the channel; then [ready] runs. */
    fun begin(ready: () -> Unit) {
        handler.removeCallbacks(release)
        talking++
        if (focus != null) {
            ready()
            return
        }
        val pause = prefs.pauseMusicForVoice
        val request = AudioFocusRequest.Builder(if (pause) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes())
            .setWillPauseWhenDucked(false)
            .build()
        focus = request
        audio.requestAudioFocus(request)

        callChannel = prefs.voiceOverCall && openCallChannel()
        stream = if (callChannel) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        val level = prefs.voiceLevel
        if (level != Level.MEDIA) {
            val max = audio.getStreamMaxVolume(stream)
            val target = ceil(max * level.streamShare).toInt().coerceIn(1, max)
            val current = audio.getStreamVolume(stream)
            if (current < target) {
                savedVolume = current
                raisedTo = target
                runCatching { audio.setStreamVolume(stream, target, 0) }
            }
        }
        if (level.boostMb > 0) {
            enhancer = runCatching {
                LoudnessEnhancer(sessionId).apply {
                    setTargetGain(level.boostMb)
                    enabled = true
                }
            }.getOrNull()
        }
        // The car needs a moment to switch over to the call channel before the first word.
        if (callChannel) handler.postDelayed(ready, 900) else ready()
    }

    /** After a sentence (finished, interrupted or failed). */
    fun end() {
        talking = (talking - 1).coerceAtLeast(0)
        if (talking == 0) {
            handler.removeCallbacks(release)
            handler.postDelayed(release, 500) // a short pause before the music comes back
        }
    }

    private val release = Runnable {
        if (talking > 0) return@Runnable
        // Put the volume back, unless you changed it yourself meanwhile.
        if (savedVolume >= 0 && audio.getStreamVolume(stream) == raisedTo) runCatching { audio.setStreamVolume(stream, savedVolume, 0) }
        savedVolume = -1
        raisedTo = -1
        enhancer?.release()
        enhancer = null
        if (callChannel) closeCallChannel()
        callChannel = false
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun openCallChannel(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: return false
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            return audio.setCommunicationDevice(device)
        }
        @Suppress("DEPRECATION")
        if (!audio.isBluetoothScoAvailableOffCall) return false
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audio.startBluetoothSco()
        @Suppress("DEPRECATION")
        audio.isBluetoothScoOn = true
        return true
    }

    private fun closeCallChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audio.stopBluetoothSco()
        }
        audio.mode = AudioManager.MODE_NORMAL
    }
}
