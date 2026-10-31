package com.abdulrahman.fakelocation.mock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.FakeSession
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.format
import com.abdulrahman.fakelocation.data.formatClock
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.data.formatDuration
import com.abdulrahman.fakelocation.trip.Alert
import com.abdulrahman.fakelocation.trip.AlertKind
import com.abdulrahman.fakelocation.trip.TripPhase
import com.abdulrahman.fakelocation.trip.TripPoint
import com.abdulrahman.fakelocation.trip.TripRun
import com.abdulrahman.fakelocation.ui.AlarmActivity
import com.abdulrahman.fakelocation.ui.MainActivity
import com.abdulrahman.fakelocation.ui.clockText
import com.abdulrahman.fakelocation.ui.stopLetter
import kotlin.math.roundToInt

/**
 * Everything [MockLocationService] shows outside the app: the ongoing notification (fixed spot or trip, with its
 * buttons), the stop alarms (sound, full-screen screen) and the "arrived" / "finished" notes.
 */
class ServiceNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val prefs get() = Prefs(context)

    fun createChannels() {
        val status = NotificationChannel(CHANNEL_STATUS, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        // The app plays the alarm sound itself (on the alarm stream), so this channel stays quiet.
        val alarms = NotificationChannel(CHANNEL_ALARMS, context.getString(R.string.channel_alarms), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
        }
        val alerts = NotificationChannel(CHANNEL_ALERTS_SOUND, context.getString(R.string.channel_alerts_sound), NotificationManager.IMPORTANCE_HIGH)
        val info = NotificationChannel(CHANNEL_TRIP_INFO, context.getString(R.string.channel_trip_info), NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannels(listOf(status, alarms, alerts, info))
    }

    /** Replaces the ongoing notification (the service's foreground one). */
    fun showOngoing(notification: Notification) = manager.notify(NOTIFICATION_ID, notification)

    // region Ongoing notification

    private fun openAppIntent() =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun serviceIntent(action: String, code: Int) =
        PendingIntent.getService(context, code, MockLocationService.actionIntent(context, action), PendingIntent.FLAG_IMMUTABLE)

    fun fixed(session: FakeSession): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.fake))
            .setContentTitle(context.getString(R.string.notification_title, session.name))
            .setContentText(session.position.format())
            .setContentIntent(openAppIntent())
            .addAction(R.drawable.ic_stop, context.getString(R.string.stop), serviceIntent(MockLocationService.ACTION_STOP, 1))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(session.startedAt)
            .build()

    fun trip(run: TripRun, point: TripPoint): Notification {
        val stops = run.route.stops
        val now = System.currentTimeMillis()
        val t = run.clock.at(now)
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.fake))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        var title: String
        val text: String
        when (point.phase) {
            TripPhase.SCHEDULED -> {
                title = context.getString(R.string.trip_starts_at, clockText(now - t))
                text = run.route.name
                builder.addAction(R.drawable.ic_route, context.getString(R.string.start_now_action), serviceIntent(MockLocationService.ACTION_SKIP, 3))
                builder.addAction(R.drawable.ic_stop, context.getString(R.string.cancel_trip), serviceIntent(MockLocationService.ACTION_STOP, 1))
            }
            TripPhase.MOVING, TripPhase.FINISHED -> {
                val next = (point.stopIndex + 1).coerceAtMost(stops.lastIndex)
                val toNext = run.timeline.arrive[next] - t
                title = context.getString(
                    if (run.route.mode == TravelMode.CAR) R.string.trip_driving_to else R.string.trip_walking_to,
                    stopLetter(next), stops[next].name
                )
                text = context.getString(
                    R.string.trip_moving_text, formatDuration(toNext.coerceAtLeast(0) + 999),
                    formatDistance(point.distanceToNextStop), (point.speedMps * 3.6f).roundToInt()
                )
                val total = run.timeline.totalDistance.coerceAtLeast(1.0)
                builder.setProgress(1000, (point.distanceDone / total * 1000).toInt(), false)
                builder.addAction(
                    R.drawable.ic_stop,
                    context.getString(if (run.clock.paused) R.string.resume else R.string.pause),
                    serviceIntent(if (run.clock.paused) MockLocationService.ACTION_RESUME else MockLocationService.ACTION_PAUSE, 4)
                )
                builder.addAction(R.drawable.ic_route, context.getString(R.string.skip), serviceIntent(MockLocationService.ACTION_SKIP, 3))
                builder.addAction(R.drawable.ic_stop, context.getString(R.string.stop), serviceIntent(MockLocationService.ACTION_STOP, 1))
            }
            TripPhase.WAITING -> {
                val i = point.stopIndex
                title = context.getString(R.string.trip_waiting_at, stopLetter(i), stops[i].name)
                val next = stops.getOrNull(i + 1)
                text = if (next == null) context.getString(R.string.trip_waiting_last)
                else context.getString(R.string.trip_waiting_text, formatClock(run.timeline.leave[i] - t + 999).removePrefix("00:"), "${stopLetter(i + 1)} · ${next.name}")
                builder.addAction(R.drawable.ic_plus, context.getString(R.string.plus_five), serviceIntent(MockLocationService.ACTION_ADD_WAIT, 5))
                builder.addAction(R.drawable.ic_route, context.getString(R.string.leave_now), serviceIntent(MockLocationService.ACTION_SKIP, 3))
                builder.addAction(R.drawable.ic_stop, context.getString(R.string.stop), serviceIntent(MockLocationService.ACTION_STOP, 1))
            }
        }
        if (run.clock.paused) title = context.getString(R.string.trip_paused_prefix, title)
        return builder.setContentTitle(title).setContentText(text).build()
    }

    // endregion

    // region Alarms

    fun fire(alert: Alert, run: TripRun, t: Long, now: Long) {
        val stops = run.route.stops
        val i = alert.stopIndex
        val stop = stops[i]
        val wallOf = { tripMs: Long -> now + (tripMs - t) }
        when (alert.kind) {
            AlertKind.ARRIVING -> {
                val target = wallOf(run.timeline.arrive[i])
                ring(
                    AlarmActivity.Kind.ARRIVING, i, stops.size, stop.name, target,
                    context.getString(R.string.alarm_arriving_title, stopLetter(i), stop.name),
                    context.getString(R.string.alarm_arriving_text, formatDuration(target - now + 999)),
                    note = if (i < stops.lastIndex && stop.waitMinutes > 0) context.getString(R.string.wait_minutes, stop.waitMinutes) else null,
                )
            }
            AlertKind.LEAVING -> {
                val target = wallOf(run.timeline.leave[i])
                val next = stops.getOrNull(i + 1)
                ring(
                    AlarmActivity.Kind.LEAVING, i, stops.size, stop.name, target,
                    context.getString(R.string.alarm_leaving_title, stop.name, formatDuration(target - now + 999)),
                    next?.let { context.getString(R.string.alarm_leaving_text, stopLetter(i + 1), it.name) }.orEmpty(),
                    note = next?.let { "${stopLetter(i + 1)} · ${it.name}" },
                )
            }
            AlertKind.ARRIVED -> info(
                context.getString(R.string.arrived_title, stopLetter(i), stop.name),
                context.getString(R.string.arrived_text, formatDuration(stop.waitMinutes * 60_000L), clockText(wallOf(run.timeline.leave[i]))),
            )
            AlertKind.FINISHED -> info(context.getString(R.string.finished_title), context.getString(R.string.finished_text, stop.name))
        }
    }

    private fun ring(
        kind: AlarmActivity.Kind, stopIndex: Int, stopCount: Int, name: String, target: Long,
        title: String, text: String, note: String?,
    ) {
        val loud = prefs.ringInSilent
        if (loud) AlarmPlayer.start(context, sound = true, vibrate = prefs.vibrate)
        val screen = AlarmActivity.intent(context, kind, stopIndex, stopCount, name, target, note)
        val screenIntent = PendingIntent.getActivity(context, 10, screen, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dismiss = PendingIntent.getService(
            context, 11, MockLocationService.actionIntent(context, MockLocationService.ACTION_DISMISS_ALARM), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, if (loud) CHANNEL_ALARMS else CHANNEL_ALERTS_SOUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.fake))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(screenIntent)
            .setDeleteIntent(dismiss)
            .addAction(R.drawable.ic_close, context.getString(R.string.dismiss), dismiss)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
        if (loud) builder.setFullScreenIntent(screenIntent, true)
        manager.notify(ALERT_NOTIFICATION_ID, builder.build())
    }

    /** A quiet note that opens the app (arrived, finished, mock app no longer selected…). */
    fun info(title: String, text: String) {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            INFO_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_TRIP_INFO)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.fake))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    fun dismissAlarm() {
        AlarmPlayer.stop()
        manager.cancel(ALERT_NOTIFICATION_ID)
        context.sendBroadcast(Intent(AlarmActivity.ACTION_CLOSE).setPackage(context.packageName))
    }

    // endregion

    companion object {
        const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2
        private const val INFO_NOTIFICATION_ID = 3
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALARMS = "alarms"
        private const val CHANNEL_ALERTS_SOUND = "alerts"
        private const val CHANNEL_TRIP_INFO = "trip_info"
    }
}
