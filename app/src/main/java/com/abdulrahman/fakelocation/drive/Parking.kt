package com.abdulrahman.fakelocation.drive

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.mock.AlarmPlayer
import com.abdulrahman.fakelocation.ui.AlarmActivity
import com.abdulrahman.fakelocation.ui.ParkingActivity
import com.abdulrahman.fakelocation.ui.clockText
import org.json.JSONObject
import java.util.Locale

/** A paid parking session: where the car is, the zone code from the sign, and when the paid time ends. */
data class Parking(
    val zone: String,
    val plate: String,
    val position: LatLon?,
    val placeName: String?,
    val startedAt: Long,
    val endsAt: Long,
) {
    fun toJson(): String = JSONObject()
        .put("zone", zone).put("plate", plate)
        .put("lat", position?.lat).put("lon", position?.lon)
        .put("place", placeName).put("start", startedAt).put("end", endsAt)
        .toString()

    companion object {
        fun fromJson(text: String): Parking? = runCatching {
            val j = JSONObject(text)
            Parking(
                j.getString("zone"), j.optString("plate"),
                if (j.has("lat")) LatLon(j.getDouble("lat"), j.getDouble("lon")) else null,
                j.optString("place").ifBlank { null }, j.getLong("start"), j.getLong("end"),
            )
        }.getOrNull()

        /** Dubai's parking SMS: "<plate> <zone> <hours>" to 7275. */
        const val DUBAI_SMS_NUMBER = "7275"

        fun smsBody(plate: String, zone: String, hours: Int): String =
            "${plate.uppercase(Locale.US)} ${zone.uppercase(Locale.US)} $hours"

        fun smsIntent(plate: String, zone: String, hours: Int): Intent =
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$DUBAI_SMS_NUMBER")).putExtra("sms_body", smsBody(plate, zone, hours))

        /** A Dubai zone code from the sign: 3 digits and a letter, e.g. "365A" (also accepts other short codes). */
        fun isZoneCode(text: String) = Regex("^[0-9]{2,4}[A-Za-z]{0,2}$").matches(text.trim())
    }
}

/** Starts and ends parking, schedules the "parking ends soon" alarm and shows the ongoing notification. */
object ParkingAlarms {

    private const val CHANNEL_LOUD = "parking_alarm"
    private const val CHANNEL_SOUND = "parking_alert"
    private const val CHANNEL_ONGOING = "parking_status"
    private const val NOTIFICATION_ONGOING = 41
    private const val NOTIFICATION_ALERT = 42
    const val ACTION_ALERT = "com.abdulrahman.fakelocation.PARKING_ALERT"
    const val ACTION_END = "com.abdulrahman.fakelocation.PARKING_END"
    const val ACTION_DISMISS = "com.abdulrahman.fakelocation.PARKING_DISMISS"
    const val ACTION_STOP = "com.abdulrahman.fakelocation.PARKING_STOP"

    fun start(context: Context, parking: Parking) {
        val prefs = Prefs(context)
        prefs.activeParking = parking
        prefs.recentZones = (listOf(parking.zone) + prefs.recentZones.filter { !it.equals(parking.zone, true) })
        schedule(context, parking)
        showOngoing(context, parking)
    }

    fun extend(context: Context, minutes: Int) {
        val current = Prefs(context).activeParking ?: return
        start(context, current.copy(endsAt = current.endsAt.coerceAtLeast(System.currentTimeMillis()) + minutes * 60_000L))
    }

    fun stop(context: Context) {
        Prefs(context).activeParking = null
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(receiverIntent(context, ACTION_ALERT))
        alarms.cancel(receiverIntent(context, ACTION_END))
        notifications(context).cancel(NOTIFICATION_ONGOING)
        dismiss(context)
    }

    fun dismiss(context: Context) {
        AlarmPlayer.stop()
        notifications(context).cancel(NOTIFICATION_ALERT)
        context.sendBroadcast(Intent(AlarmActivity.ACTION_CLOSE).setPackage(context.packageName))
    }

    private fun schedule(context: Context, parking: Parking) {
        val alertAt = parking.endsAt - Prefs(context).parkingAlertMinutes * 60_000L
        val now = System.currentTimeMillis()
        if (alertAt > now) exact(context, alertAt, receiverIntent(context, ACTION_ALERT))
        if (parking.endsAt > now) exact(context, parking.endsAt, receiverIntent(context, ACTION_END))
    }

    private fun exact(context: Context, at: Long, operation: PendingIntent) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (canExact) {
            val show = PendingIntent.getActivity(context, 0, Intent(context, ParkingActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    private fun receiverIntent(context: Context, action: String) = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, ParkingReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun showOngoing(context: Context, parking: Parking) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, ParkingActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.fake))
            .setContentTitle(context.getString(R.string.parking_ongoing_title, parking.zone))
            .setContentText(context.getString(R.string.parking_ongoing_text, clockText(parking.endsAt)))
            .setWhen(parking.endsAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, context.getString(R.string.parking_end), receiverIntent(context, ACTION_STOP))
            .build()
        notifications(context).notify(NOTIFICATION_ONGOING, notification)
    }

    internal fun onAlarm(context: Context, action: String) {
        val parking = Prefs(context).activeParking ?: return
        when (action) {
            ACTION_ALERT -> ring(context, parking)
            ACTION_END -> {
                if (!canNotify(context)) return
                ensureChannels(context)
                val open = PendingIntent.getActivity(context, 0, Intent(context, ParkingActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                notifications(context).notify(
                    NOTIFICATION_ONGOING,
                    NotificationCompat.Builder(context, CHANNEL_SOUND)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setColor(ContextCompat.getColor(context, R.color.limit_red))
                        .setContentTitle(context.getString(R.string.parking_ended_title, parking.zone))
                        .setContentText(context.getString(R.string.parking_ended_text))
                        .setContentIntent(open)
                        .addAction(R.drawable.ic_stop, context.getString(R.string.parking_end), receiverIntent(context, ACTION_STOP))
                        .build(),
                )
            }
            ACTION_DISMISS -> dismiss(context)
            ACTION_STOP -> stop(context)
        }
    }

    private fun ring(context: Context, parking: Parking) {
        val prefs = Prefs(context)
        val loud = prefs.ringInSilent
        if (loud) AlarmPlayer.start(context, sound = true, vibrate = prefs.vibrate)
        if (!canNotify(context)) return
        ensureChannels(context)
        val screen = AlarmActivity.parkingIntent(context, parking.zone, parking.placeName, parking.endsAt)
        val screenIntent = PendingIntent.getActivity(context, 12, screen, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dismiss = receiverIntent(context, ACTION_DISMISS)
        val builder = NotificationCompat.Builder(context, if (loud) CHANNEL_LOUD else CHANNEL_SOUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.fake))
            .setContentTitle(context.getString(R.string.parking_alarm_title, parking.zone))
            .setContentText(context.getString(R.string.parking_ongoing_text, clockText(parking.endsAt)))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(screenIntent)
            .setDeleteIntent(dismiss)
            .addAction(R.drawable.ic_close, context.getString(R.string.dismiss), dismiss)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
        if (loud) builder.setFullScreenIntent(screenIntent, true)
        notifications(context).notify(NOTIFICATION_ALERT, builder.build())
    }

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notifications(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannels(context: Context) {
        val loud = NotificationChannel(CHANNEL_LOUD, context.getString(R.string.parking_channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // AlarmPlayer rings on the alarm stream
            enableVibration(false)
            setBypassDnd(true)
        }
        val sound = NotificationChannel(CHANNEL_SOUND, context.getString(R.string.parking_channel_alert), NotificationManager.IMPORTANCE_HIGH)
        val ongoing = NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.parking_channel_status), NotificationManager.IMPORTANCE_LOW)
        notifications(context).createNotificationChannels(listOf(loud, sound, ongoing))
    }
}

class ParkingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val action = intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                Prefs(context).activeParking?.let { if (it.endsAt > System.currentTimeMillis()) ParkingAlarms.start(context, it) }
            null -> Unit
            else -> ParkingAlarms.onAlarm(context, action)
        }
    }
}
