package com.abdulrahman.fakelocation.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.format
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.mock.MockLocationService
import com.abdulrahman.fakelocation.mock.MockState
import com.abdulrahman.fakelocation.mock.TripUi
import com.abdulrahman.fakelocation.trip.TripPhase
import com.abdulrahman.fakelocation.ui.MainActivity
import com.abdulrahman.fakelocation.ui.clockText
import com.abdulrahman.fakelocation.ui.stopLetter

/** Home-screen widget (4×2): fake location or trip status, with a countdown and quick buttons. */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context)
    }

    companion object {
        /** Redraws every placed widget from the current state. Cheap enough to call on each change. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(context)
            manager.updateAppWidget(ids, views)
        }

        private data class Button(val label: String, val intent: PendingIntent)

        private fun build(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_status)
            val prefs = Prefs(context)
            val trip = MockState.trip.value
            val session = MockState.session.value ?: prefs.activeSession
            val open = PendingIntent.getActivity(
                context, 20, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, open)
            views.setViewVisibility(R.id.widgetProgress, View.GONE)
            views.setViewVisibility(R.id.widgetClock, View.GONE)

            val buttons: List<Button>
            when {
                trip != null -> buttons = bindTrip(context, views, trip, open)
                session != null -> {
                    status(context, views, context.getString(R.string.widget_on), R.color.fake)
                    views.setTextViewText(R.id.widgetTitle, session.name)
                    views.setTextViewText(R.id.widgetSubtitle, session.position.format())
                    chronometer(views, SystemClock.elapsedRealtime() - (System.currentTimeMillis() - session.startedAt), countDown = false, running = true)
                    buttons = listOf(
                        Button(context.getString(R.string.widget_open), open),
                        Button(context.getString(R.string.stop), service(context, MockLocationService.ACTION_STOP, 21)),
                    )
                }
                else -> {
                    status(context, views, context.getString(R.string.widget_off), R.color.idle_dot)
                    val place = QuickActions.defaultPlace(context)
                    views.setTextViewText(R.id.widgetTitle, place?.name ?: context.getString(R.string.app_name))
                    views.setTextViewText(R.id.widgetSubtitle, place?.position?.format() ?: context.getString(R.string.widget_tap_place))
                    val go = PendingIntent.getActivity(context, 22, QuickActionActivity.intent(context), PendingIntent.FLAG_IMMUTABLE)
                    buttons = listOfNotNull(
                        Button(context.getString(R.string.widget_open), open),
                        place?.let { Button(context.getString(R.string.widget_go, it.name), go) },
                    )
                }
            }

            val slots = listOf(R.id.widgetButton1, R.id.widgetButton2, R.id.widgetButton3)
            // The last button is the main one (white); fill slots from the right.
            val offset = slots.size - buttons.size
            slots.forEachIndexed { i, id ->
                val button = buttons.getOrNull(i - offset)
                if (button == null) {
                    views.setViewVisibility(id, View.GONE)
                } else {
                    views.setViewVisibility(id, View.VISIBLE)
                    views.setTextViewText(id, button.label)
                    views.setOnClickPendingIntent(id, button.intent)
                }
            }
            return views
        }

        private fun bindTrip(context: Context, views: RemoteViews, trip: TripUi, open: PendingIntent): List<Button> {
            val p = trip.point
            val stops = trip.stops
            val now = SystemClock.elapsedRealtime()
            val stop = Button(context.getString(R.string.stop), service(context, MockLocationService.ACTION_STOP, 21))
            val skip = service(context, MockLocationService.ACTION_SKIP, 23)
            return when (p.phase) {
                TripPhase.SCHEDULED -> {
                    status(context, views, context.getString(R.string.widget_starts, clockText(trip.wallOf(0))), R.color.real)
                    views.setTextViewText(R.id.widgetTitle, trip.routeName)
                    views.setTextViewText(R.id.widgetSubtitle, "${stopLetter(0)} → ${stopLetter(stops.lastIndex)} · ${stops.last().name}")
                    chronometer(views, now - trip.t, countDown = true, running = true)
                    listOf(Button(context.getString(R.string.start_now_action), skip), stop.copy(label = context.getString(R.string.cancel_trip)))
                }
                TripPhase.WAITING -> {
                    val i = p.stopIndex
                    status(context, views, if (trip.paused) context.getString(R.string.widget_paused) else context.getString(R.string.widget_waiting, stopLetter(i)), R.color.fake)
                    views.setTextViewText(R.id.widgetTitle, stops[i].name)
                    val next = trip.nextStop
                    views.setTextViewText(
                        R.id.widgetSubtitle,
                        if (next == null) context.getString(R.string.trip_waiting_last)
                        else context.getString(R.string.widget_waiting_sub, clockText(trip.wallOf(trip.timeline.leave[i])), "${stopLetter(next)} · ${stops[next].name}")
                    )
                    if (next != null) chronometer(views, now + (trip.timeline.leave[i] - trip.t), countDown = true, running = !trip.paused)
                    progress(views, trip)
                    listOfNotNull(
                        Button(context.getString(R.string.plus_five), service(context, MockLocationService.ACTION_ADD_WAIT, 24)),
                        next?.let { Button(context.getString(R.string.leave_now), skip) },
                        stop,
                    )
                }
                else -> {
                    val next = trip.nextStop ?: stops.lastIndex
                    val label = if (trip.mode == TravelMode.CAR) R.string.widget_driving else R.string.widget_walking
                    status(context, views, if (trip.paused) context.getString(R.string.widget_paused) else context.getString(label, stopLetter(next)), R.color.fake)
                    views.setTextViewText(R.id.widgetTitle, stops[next].name)
                    views.setTextViewText(
                        R.id.widgetSubtitle,
                        context.getString(
                            R.string.widget_moving_sub, formatDistance(p.distanceToNextStop),
                            stopLetter(stops.lastIndex), clockText(trip.wallOf(trip.timeline.arrive.last()))
                        )
                    )
                    chronometer(views, now + (trip.timeline.arrive[next] - trip.t), countDown = true, running = !trip.paused)
                    progress(views, trip)
                    listOf(
                        Button(
                            context.getString(if (trip.paused) R.string.resume else R.string.pause),
                            if (trip.paused) service(context, MockLocationService.ACTION_RESUME, 25) else service(context, MockLocationService.ACTION_PAUSE, 26)
                        ),
                        Button(context.getString(R.string.skip), skip),
                        stop,
                    )
                }
            }
        }

        private fun status(context: Context, views: RemoteViews, text: String, dotColor: Int) {
            views.setTextViewText(R.id.widgetStatus, text)
            views.setInt(R.id.widgetDot, "setColorFilter", ContextCompat.getColor(context, dotColor))
        }

        private fun chronometer(views: RemoteViews, base: Long, countDown: Boolean, running: Boolean) {
            views.setViewVisibility(R.id.widgetClock, View.VISIBLE)
            views.setChronometer(R.id.widgetClock, base, null, running)
            views.setChronometerCountDown(R.id.widgetClock, countDown)
        }

        private fun progress(views: RemoteViews, trip: TripUi) {
            views.setViewVisibility(R.id.widgetProgress, View.VISIBLE)
            val total = trip.timeline.totalDistance.coerceAtLeast(1.0)
            views.setProgressBar(R.id.widgetProgress, 1000, (trip.point.distanceDone / total * 1000).toInt(), false)
        }

        /** A widget button that sends an action to the location service. */
        private fun service(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getService(
                context, code, MockLocationService.actionIntent(context, action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
