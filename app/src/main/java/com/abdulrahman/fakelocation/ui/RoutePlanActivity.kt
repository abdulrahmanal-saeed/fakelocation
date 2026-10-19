package com.abdulrahman.fakelocation.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.RoutesRepository
import com.abdulrahman.fakelocation.data.Schedule
import com.abdulrahman.fakelocation.data.Timing
import com.abdulrahman.fakelocation.data.TimingMode
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.data.formatDuration
import com.abdulrahman.fakelocation.databinding.ActivityRoutePlanBinding
import com.abdulrahman.fakelocation.databinding.ItemTimelineBinding
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.mock.LocationMocker
import com.abdulrahman.fakelocation.mock.MockLocationService
import com.abdulrahman.fakelocation.mock.MockState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.Toast
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar

/** Pick when a route runs: now, at a set time, or so it arrives by a set time. Shows every stop's times. */
class RoutePlanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoutePlanBinding
    private lateinit var repo: RoutesRepository
    private lateinit var route: Route
    private lateinit var prefs: Prefs
    private var mode = TimingMode.NOW
    private var pickedTime: Long? = null
    private val refresh = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            refresh.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityRoutePlanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars(top = true, bottom = true)
        repo = RoutesRepository.get(this)
        prefs = Prefs(this)
        val loaded = repo.get(intent.getLongExtra(EXTRA_ROUTE_ID, -1))
        if (loaded?.path == null || loaded.stops.size < 2) {
            finish()
            return
        }
        route = loaded
        mode = route.timing.mode
        pickedTime = route.timing.timeMillis?.let(::nextOccurrence)

        binding.routeName.text = route.name
        binding.backButton.setOnClickListener { finish() }
        binding.timingGroup.check(
            when (mode) {
                TimingMode.NOW -> R.id.timingNow
                TimingMode.START_AT -> R.id.timingStartAt
                TimingMode.ARRIVE_BY -> R.id.timingArriveBy
            }
        )
        binding.timingGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            mode = when (id) {
                R.id.timingStartAt -> TimingMode.START_AT
                R.id.timingArriveBy -> TimingMode.ARRIVE_BY
                else -> TimingMode.NOW
            }
            if (mode != TimingMode.NOW && pickedTime == null) pickedTime = defaultTime()
            render()
        }
        binding.timeButton.setOnClickListener { pickTime() }
        binding.naturalSwitch.isChecked = prefs.naturalMovement
        binding.naturalSwitch.setOnCheckedChangeListener { _, checked -> prefs.naturalMovement = checked }
        binding.startButton.setOnClickListener { startTrip() }
        render()
    }

    override fun onResume() {
        super.onResume()
        refresh.post(tick)
    }

    override fun onPause() {
        refresh.removeCallbacks(tick)
        super.onPause()
    }

    /** The path with the traffic setting applied, as the trip will actually run it. */
    private val travelPath get() = route.travelPath(prefs.trafficPercent)!!

    private fun startAt(now: Long): Long {
        val time = pickedTime ?: now
        return when (mode) {
            TimingMode.NOW -> now
            TimingMode.START_AT -> time
            TimingMode.ARRIVE_BY -> Schedule.startFor(route.stops, travelPath, time).coerceAtLeast(now)
        }
    }

    private fun startTrip() {
        if (!hasLocationPermission() || !LocationMocker.isSelectedMockApp(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.step_mock)
                .setMessage(R.string.mock_not_selected)
                .setPositiveButton(R.string.setup_guide) { _, _ -> startActivity(SetupActivity.fromSettings(this)) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        if (MockState.trip.value != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.replace_trip_title)
                .setMessage(R.string.replace_trip_body)
                .setPositiveButton(R.string.replace) { _, _ -> launchTrip() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        launchTrip()
    }

    private fun launchTrip() {
        val saved = route.copy(timing = Timing(mode, if (mode == TimingMode.NOW) null else pickedTime))
        repo.save(saved)
        val start = startAt(System.currentTimeMillis())
        MockLocationService.startTrip(this, saved, start)
        if (start > System.currentTimeMillis() + 60_000) {
            Toast.makeText(this, getString(R.string.trip_scheduled, "${clockText(start)} ${dayText(start)}"), Toast.LENGTH_LONG).show()
        }
        startActivity(MainActivity.showMap(this))
        finish()
    }

    private fun defaultTime(): Long {
        val path = travelPath
        val base = System.currentTimeMillis() + if (mode == TimingMode.ARRIVE_BY) Schedule.totalMillis(route.stops, path) else 0L
        return roundUp(base + 30 * 60_000L)
    }

    private fun roundUp(time: Long): Long {
        val five = 5 * 60_000L
        return (time + five - 1) / five * five
    }

    /** The same clock time today, or tomorrow if it has already passed. */
    private fun nextOccurrence(time: Long): Long {
        val picked = Calendar.getInstance().apply { timeInMillis = time }
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, picked.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, picked.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (next.timeInMillis <= System.currentTimeMillis()) next.add(Calendar.DAY_OF_YEAR, 1)
        return next.timeInMillis
    }

    private fun pickTime() {
        val current = Calendar.getInstance().apply { timeInMillis = pickedTime ?: defaultTime() }
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(current.get(Calendar.HOUR_OF_DAY))
            .setMinute(current.get(Calendar.MINUTE))
            .setTitleText(if (mode == TimingMode.ARRIVE_BY) R.string.arrive_by else R.string.start_at)
            .build()
        picker.addOnPositiveButtonClickListener {
            val chosen = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, picker.hour)
                set(Calendar.MINUTE, picker.minute)
            }
            val next = nextOccurrence(chosen.timeInMillis)
            if (!DateUtils.isToday(next)) toast(R.string.time_in_past)
            pickedTime = next
            render()
        }
        picker.show(supportFragmentManager, "time")
    }

    private fun dayText(time: Long) = getString(if (DateUtils.isToday(time)) R.string.today_lower else R.string.tomorrow_lower)

    private fun render() {
        if (route.path == null) return
        val path = travelPath
        val stops = route.stops
        val now = System.currentTimeMillis()
        val lastName = stops.last().name
        val time = pickedTime

        binding.timeButton.isVisible = mode != TimingMode.NOW
        if (time != null && mode != TimingMode.NOW) {
            val label = getString(if (mode == TimingMode.ARRIVE_BY) R.string.arrive_by else R.string.start_at)
            binding.timeButton.text = "$label ${clockText(time)} · ${dayText(time)}"
        }

        val tooSoon = mode == TimingMode.ARRIVE_BY && Schedule.startFor(stops, path, time ?: now) < now
        val startAt = startAt(now)
        val times = Schedule.times(stops, path, startAt)
        val arrival = times.last().arriveAt

        when {
            tooSoon -> {
                binding.headlineCaption.text = getString(R.string.arrive_too_soon, lastName)
                binding.headlineTime.text = clockText(arrival)
            }
            mode == TimingMode.NOW -> {
                binding.headlineCaption.text = getString(R.string.headline_now, lastName)
                binding.headlineTime.text = clockText(arrival)
            }
            mode == TimingMode.START_AT -> {
                binding.headlineCaption.text = getString(R.string.headline_start_at, "${clockText(startAt)} ${dayText(startAt)}", lastName)
                binding.headlineTime.text = clockText(arrival)
            }
            else -> {
                binding.headlineCaption.text = getString(R.string.headline_arrive_by, lastName, clockText(time ?: arrival))
                binding.headlineTime.text = clockText(startAt)
            }
        }

        binding.timeline.removeAllViews()
        stops.forEachIndexed { i, stop ->
            val row = ItemTimelineBinding.inflate(LayoutInflater.from(this), binding.timeline, false)
            row.letter.text = stopLetter(i)
            row.letter.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, stopColorRes(i, stops.size)))
            row.name.text = stop.name
            row.arrive.text = clockText(times[i].arriveAt)
            row.line.isVisible = i < stops.lastIndex
            row.detail.text = when {
                i == stops.lastIndex -> getString(R.string.timeline_final)
                stop.waitMinutes == 0 -> getString(if (i == 0) R.string.timeline_start_nowait else R.string.timeline_mid_nowait)
                else -> getString(if (i == 0) R.string.timeline_start else R.string.timeline_mid, stop.waitMinutes, clockText(times[i].leaveAt))
            }
            binding.timeline.addView(row.root)
        }
        val total = getString(R.string.trip_total, formatDuration(Schedule.totalMillis(stops, path)), formatDistance(path.distanceMeters))
        binding.total.text = if (route.mode == TravelMode.CAR && prefs.trafficPercent > 0) {
            total + " · " + getString(R.string.traffic_note, prefs.trafficPercent)
        } else {
            total
        }
        binding.startButton.setText(if (startAt > now + 60_000) R.string.schedule_trip else R.string.start_trip)
    }

    companion object {
        private const val EXTRA_ROUTE_ID = "route_id"

        fun intent(context: Context, routeId: Long) =
            Intent(context, RoutePlanActivity::class.java).putExtra(EXTRA_ROUTE_ID, routeId)
    }
}
