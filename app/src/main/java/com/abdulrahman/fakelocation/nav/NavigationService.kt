package com.abdulrahman.fakelocation.nav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.ui.NavigateActivity
import kotlinx.coroutines.launch

/** Keeps real navigation going with the screen off or while Android Auto shows it. */
class NavigationService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastText: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_END) {
            NavSession.stop()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.nav_channel), NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(getString(R.string.nav_title), ""),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FakeLocation:navigation")
                .apply { acquire(6 * 60 * 60_000L) }
            lifecycleScope.launch {
                NavSession.state.collect { s ->
                    if (!s.navigating) {
                        stopSelf()
                        return@collect
                    }
                    val p = s.progress
                    val title = p?.nextStep?.text ?: getString(R.string.nav_title)
                    val text = p?.let { formatDistance(it.metersToNextStep) + " · " + formatDistance(it.remainingMeters) } ?: ""
                    if ("$title|$text" != lastText) {
                        lastText = "$title|$text"
                        manager.notify(NOTIFICATION_ID, notification(title, text))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_navigate)
            .setColor(ContextCompat.getColor(this, R.color.real))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, NavigateActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(R.drawable.ic_stop, getString(R.string.nav_end),
                PendingIntent.getService(this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE))
            .build()

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "navigation"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_END = "end"

        fun intent(context: Context) = Intent(context, NavigationService::class.java)
    }
}
