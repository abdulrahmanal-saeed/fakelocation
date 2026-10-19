package com.abdulrahman.fakelocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

/**
 * Foreground service that keeps re-sending the fake position every second.
 * Apps that ask for location while the screen is off or the app is closed still get it.
 */
class MockLocationService : LifecycleService() {

    private lateinit var mocker: LocationMocker
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var position: LatLon? = null

    private val tick = object : Runnable {
        override fun run() {
            position?.let {
                runCatching { mocker.push(it.lat, it.lon) }
                    .onFailure { e -> Log.w(TAG, "push failed", e) }
            }
            handler.postDelayed(this, PUSH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        mocker = LocationMocker(this)
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopMocking()
                return START_NOT_STICKY
            }
            ACTION_TELEPORT -> {
                val target = LatLon(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0))
                startMocking(target)
            }
            // Restarted by the system after being killed: restore the last position.
            null -> prefs.activeFakeLocation?.let { startMocking(it) } ?: stopSelf()
        }
        return START_STICKY
    }

    private fun startMocking(target: LatLon) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(target),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        try {
            mocker.start()
        } catch (e: SecurityException) {
            Log.w(TAG, "not the selected mock app", e)
            stopMocking()
            return
        }
        position = target
        prefs.activeFakeLocation = target
        MockState.setFakePosition(target)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun stopMocking() {
        handler.removeCallbacks(tick)
        mocker.stop()
        position = null
        prefs.activeFakeLocation = null
        MockState.setFakePosition(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        mocker.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_STATUS, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(target: LatLon): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_pin_white)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("%.5f, %.5f".format(target.lat, target.lon))
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_stop, getString(R.string.stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_STATUS = "status"
        private const val NOTIFICATION_ID = 1
        private const val PUSH_INTERVAL_MS = 1000L
        private const val ACTION_TELEPORT = "teleport"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"

        fun teleport(context: Context, target: LatLon) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_TELEPORT)
                .putExtra(EXTRA_LAT, target.lat)
                .putExtra(EXTRA_LON, target.lon)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MockLocationService::class.java).setAction(ACTION_STOP))
        }
    }
}
