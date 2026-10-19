package com.abdulrahman.fakelocation.mock

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Plays the alarm on the alarm stream, like a clock app, so it is heard even when the phone is on silent
 * or vibrate. Stops by itself after a while.
 */
object AlarmPlayer {
    private const val MAX_RING_MS = 45_000L
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(context: Context, sound: Boolean, vibrate: Boolean) {
        stop()
        val app = context.applicationContext
        if (sound) {
            try {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                player = MediaPlayer().apply {
                    setAudioAttributes(alarmAttributes)
                    setDataSource(app, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.w("AlarmPlayer", "could not play alarm sound", e)
            }
        }
        if (vibrate) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Vibrator::class.java)
            }
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400, 700, 400, 1200, 900), 0), alarmAttributes)
        }
        handler.postDelayed(autoStop, MAX_RING_MS)
    }

    fun stop() {
        handler.removeCallbacks(autoStop)
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
    }
}
