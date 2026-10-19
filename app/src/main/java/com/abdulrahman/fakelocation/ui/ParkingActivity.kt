package com.abdulrahman.fakelocation.ui

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.databinding.ActivityParkingBinding
import com.abdulrahman.fakelocation.drive.Parking
import com.abdulrahman.fakelocation.drive.ParkingAlarms
import com.abdulrahman.fakelocation.drive.ParkingZones
import com.abdulrahman.fakelocation.geo.PlaceSearch
import com.abdulrahman.fakelocation.nav.NavSession
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Paid parking in Dubai: note the zone from the sign, pay by SMS to 7275, and get an alarm before the time
 * runs out. Uses the real location (like navigation), not the fake one.
 */
class ParkingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParkingBinding
    private val prefs by lazy { Prefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var hours = 1
    private var spot: LatLon? = null
    private var spotName: String? = null
    private var lookedUp = false

    private val tick = object : Runnable {
        override fun run() {
            renderActive()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityParkingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars(top = true, bottom = true)

        hours = prefs.parkingHours.coerceIn(1, 24)
        binding.plateInput.setText(prefs.plateNumber)
        binding.backButton.setOnClickListener { finish() }
        binding.hoursMinus.setOnClickListener { setHours(hours - 1) }
        binding.hoursPlus.setOnClickListener { setHours(hours + 1) }
        binding.zoneInput.doAfterTextChanged { binding.zoneLayout.error = null; renderSms() }
        binding.plateInput.doAfterTextChanged { binding.plateLayout.error = null; renderSms() }
        binding.payButton.setOnClickListener { begin(paySms = true) }
        binding.timerButton.setOnClickListener { begin(paySms = false) }
        binding.addHourButton.setOnClickListener {
            ParkingAlarms.extend(this, 60)
            renderActive()
        }
        binding.endButton.setOnClickListener {
            ParkingAlarms.stop(this)
            render()
        }
        binding.backToCarButton.setOnClickListener { walkBack() }
        binding.alertNote.text = getString(R.string.parking_alert_note, prefs.parkingAlertMinutes)
        setHours(hours)
        renderZoneChips(emptyList())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavSession.state.collect { s -> s.real?.let { onRealLocation(it) } }
            }
        }
        if (spot == null) prefs.lastRealLocation?.let { showSpot(it, null) }
    }

    override fun onStart() {
        super.onStart()
        NavSession.acquire()
        render()
    }

    override fun onStop() {
        NavSession.release()
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private fun render() {
        val active = prefs.activeParking
        binding.setupGroup.isVisible = active == null
        binding.activeGroup.isVisible = active != null
        handler.removeCallbacks(tick)
        if (active != null) {
            active.position?.let { showSpot(it, active.placeName) }
            handler.post(tick)
        }
    }

    private fun renderActive() {
        val p = prefs.activeParking ?: return render()
        binding.activeZone.text = getString(R.string.parking_active, p.zone).uppercase(Locale.US)
        val left = (p.endsAt - System.currentTimeMillis()) / 1000
        binding.countdownText.text = if (left <= 0) getString(R.string.parking_over)
        else String.format(Locale.US, "%d:%02d:%02d", left / 3600, left / 60 % 60, left % 60)
        binding.untilText.text = getString(R.string.parking_until, clockText(p.endsAt))
        binding.backToCarButton.isEnabled = p.position != null
    }

    private fun onRealLocation(here: LatLon) {
        if (prefs.activeParking != null) return // the car stays where it was parked
        val moved = spot?.let { com.abdulrahman.fakelocation.data.distanceMeters(it, here) > 40 } ?: true
        if (!moved) return
        showSpot(here, null)
        lifecycleScope.launch {
            val place = runCatching { PlaceSearch.reverse(this@ParkingActivity, here) }.getOrNull()
            spotName = place?.name ?: place?.address
            if (spot == here) binding.placeText.text = spotName ?: getString(R.string.parking_my_car)
        }
        if (!lookedUp) {
            lookedUp = true
            lifecycleScope.launch {
                val zones = runCatching { ParkingZones.near(here) }.getOrDefault(emptyList())
                renderZoneChips(zones)
            }
        }
    }

    private fun showSpot(position: LatLon, name: String?) {
        spot = position
        if (name != null) spotName = name
        binding.placeText.text = spotName ?: getString(R.string.parking_locating)
        binding.coordsText.text = String.format(Locale.US, "%.5f, %.5f", position.lat, position.lon)
    }

    private fun renderZoneChips(nearby: List<String>) {
        val recent = prefs.recentZones
        val all = (nearby + recent).distinct()
        binding.zoneChips.removeAllViews()
        binding.zoneChipsLabel.isVisible = all.isNotEmpty()
        binding.zoneChipsLabel.setText(if (nearby.isNotEmpty()) R.string.parking_nearby else R.string.parking_recent)
        for (zone in all) {
            binding.zoneChips.addView(Chip(this, null, 0).apply {
                setChipDrawable(ChipDrawable.createFromAttributes(this@ParkingActivity, null, 0, R.style.Chip_Place))
                text = zone
                setOnClickListener { binding.zoneInput.setText(zone) }
            })
        }
        if (binding.zoneInput.text.isNullOrBlank() && nearby.size == 1) binding.zoneInput.setText(nearby.first())
    }

    private fun setHours(value: Int) {
        hours = value.coerceIn(1, 24)
        prefs.parkingHours = hours
        binding.hoursText.text = getString(R.string.parking_hours_value, hours)
        renderSms()
    }

    private fun zone() = binding.zoneInput.text?.toString()?.trim()?.uppercase(Locale.US).orEmpty()
    private fun plate() = binding.plateInput.text?.toString()?.trim()?.uppercase(Locale.US).orEmpty()

    private fun renderSms() {
        val zone = zone().ifBlank { "ZONE" }
        val plate = plate().ifBlank { "PLATE" }
        binding.smsPreview.text = getString(R.string.parking_sms_preview, Parking.smsBody(plate, zone, hours))
    }

    private fun begin(paySms: Boolean) {
        val zone = zone()
        val plate = plate()
        if (!Parking.isZoneCode(zone)) {
            binding.zoneLayout.error = getString(R.string.parking_zone_invalid)
            return
        }
        if (paySms && plate.isBlank()) {
            binding.plateLayout.error = getString(R.string.parking_plate_needed)
            return
        }
        if (plate.isNotBlank()) prefs.plateNumber = plate
        if (paySms) {
            try {
                startActivity(Parking.smsIntent(plate, zone, hours))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.parking_no_sms_app, Toast.LENGTH_SHORT).show()
                return
            }
        }
        val now = System.currentTimeMillis()
        ParkingAlarms.start(this, Parking(zone, plate, spot, spotName, now, now + hours * 3_600_000L))
        render()
    }

    private fun walkBack() {
        val p = prefs.activeParking ?: return
        val position = p.position ?: return
        NavSession.clear()
        NavSession.setMode(TravelMode.WALK)
        startActivity(NavigateActivity.intent(this, Place(position, getString(R.string.parking_my_car), p.placeName)))
    }
}
