package com.abdulrahman.fakelocation.system

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.RoutesRepository
import com.abdulrahman.fakelocation.trip.TripSchedule
import com.abdulrahman.fakelocation.ui.clockText
import java.time.ZoneId

/**
 * Wakes the phone when a repeating route is due. Android only lets the location service start from the foreground,
 * so the alarm shows a full-screen notification: with the screen off it opens [QuickActionActivity] straight away,
 * which starts the trip; while the phone is in use it shows "Start" instead.
 */
object TripScheduler {
    private const val CHANNEL = "trip_start"
    private const val ACTION_FIRE = "com.abdulrahman.fakelocation.TRIP_DUE"
    private const val EXTRA_ROUTE_ID = "route_id"
    private const val EXTRA_START_AT = "start_at"
    private const val TAG = "TripScheduler"

    /** Sets one alarm for the next run of every repeating route and clears alarms of routes that no longer repeat. */
    fun update(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val alarms = app.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val armed = HashSet<Long>()
        for (route in RoutesRepository.get(app).routes.value) {
            val start = TripSchedule.nextStart(route, prefs.trafficPercent, now, ZoneId.systemDefault()) ?: continue
            exact(alarms, start, fireIntent(app, route.id, start))
            armed += route.id
        }
        for (id in prefs.scheduledRouteIds - armed) alarms.cancel(fireIntent(app, id, 0))
        prefs.scheduledRouteIds = armed
    }

    /** The alarm went off: offer (or, with the screen off, start) the trip, then set the next alarm. */
    internal fun onDue(context: Context, routeId: Long, startAt: Long) {
        val app = context.applicationContext
        val route = RoutesRepository.get(app).get(routeId)
        if (route != null && route.timing.repeats && route.path != null) {
            if (Prefs(app).activeTrip != null) notify(app, route, startAt, skipped = true)
            else notify(app, route, startAt, skipped = false)
        }
        update(app)
    }

    /** Called once the trip has started (or was skipped), so the notification goes away. */
    fun dismiss(context: Context, routeId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(routeId))
    }

    private fun notify(context: Context, route: Route, startAt: Long, skipped: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_trip_start), NotificationManager.IMPORTANCE_HIGH)
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.fake))
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60_000L)
        if (skipped) {
            builder.setContentTitle(context.getString(R.string.schedule_skipped_title, route.name))
                .setContentText(context.getString(R.string.schedule_skipped_text))
        } else {
            val start = PendingIntent.getActivity(
                context, notificationId(route.id), QuickActionActivity.startRoute(context, route.id, startAt),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentTitle(context.getString(R.string.schedule_due_title, route.name))
                .setContentText(context.getString(R.string.schedule_due_text, clockText(startAt)))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(start)
                .setFullScreenIntent(start, true)
                .addAction(R.drawable.ic_play, context.getString(R.string.start_trip), start)
        }
        manager.notify(notificationId(route.id), builder.build())
    }

    private fun notificationId(routeId: Long) = 5000 + (routeId % 100_000).toInt()

    private fun fireIntent(context: Context, routeId: Long, startAt: Long) = PendingIntent.getBroadcast(
        context, (routeId % Int.MAX_VALUE).toInt(),
        Intent(context, TripScheduleReceiver::class.java).setAction(ACTION_FIRE)
            .putExtra(EXTRA_ROUTE_ID, routeId).putExtra(EXTRA_START_AT, startAt),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun exact(alarms: AlarmManager, at: Long, operation: PendingIntent) {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (canExact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
    }

    internal fun handle(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> onDue(context, intent.getLongExtra(EXTRA_ROUTE_ID, -1), intent.getLongExtra(EXTRA_START_AT, System.currentTimeMillis()))
            // Alarms are lost on reboot and must follow clock changes.
            else -> runCatching { update(context) }.onFailure { Log.w(TAG, "schedule not restored", it) }
        }
    }
}

class TripScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = TripScheduler.handle(context, intent)
}
