package com.abdulrahman.fakelocation.ui

import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Drive
import com.abdulrahman.fakelocation.data.DriveLog
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.data.formatDuration
import com.abdulrahman.fakelocation.databinding.ActivityDriveLogBinding
import com.abdulrahman.fakelocation.databinding.ItemDriveBinding
import com.abdulrahman.fakelocation.databinding.ItemDriveMonthBinding
import com.abdulrahman.fakelocation.drive.Fuel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Real drives made with navigation, month by month: distance, Salik / Darb and fuel. */
class DriveLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDriveLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDriveLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars(top = true, bottom = true)
        binding.backButton.setOnClickListener { finish() }
        val log = DriveLog.get(this)
        binding.clearButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.drive_log_clear_title)
                .setPositiveButton(R.string.clear) { _, _ -> log.clear() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { log.drives.collect(::render) }
        }
    }

    private fun render(drives: List<Drive>) {
        binding.empty.isVisible = drives.isEmpty()
        binding.clearButton.isVisible = drives.isNotEmpty()
        binding.list.removeAllViews()
        val zone = ZoneId.systemDefault()
        val byMonth = drives.groupBy { YearMonth.from(java.time.Instant.ofEpochMilli(it.startedAt).atZone(zone)) }
        val monthName = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
        val when_ = SimpleDateFormat("EEE d MMM HH:mm", Locale.US)
        for (total in DriveLog.months(drives, zone)) {
            val month = ItemDriveMonthBinding.inflate(LayoutInflater.from(this), binding.list, false)
            month.month.text = total.month.format(monthName)
            month.distance.text = formatDistance(total.meters)
            month.tolls.text = Fuel.aed(total.tollsAed).replace(".00", "")
            month.fuel.text = Fuel.aed(total.fuelAed).replace(".00", "")
            month.summary.text = resources.getQuantityString(R.plurals.drive_month_summary, total.drives, total.drives, formatDuration((total.seconds * 1000).toLong()))
            for (drive in byMonth[total.month].orEmpty()) {
                val row = ItemDriveBinding.inflate(LayoutInflater.from(this), month.drives, false)
                row.destination.text = drive.destination
                row.detail.text = listOf(
                    when_.format(Date(drive.startedAt)),
                    formatDistance(drive.meters),
                    formatDuration((drive.seconds * 1000).toLong()),
                    getString(R.string.kmh, drive.averageKmh.roundToInt()),
                ).joinToString(" · ")
                row.cost.text = if (drive.mode == TravelMode.CAR) Fuel.aed(drive.tollsAed + drive.fuelAed).replace(".00", "") else getString(R.string.mode_walk)
                month.drives.addView(row.root)
            }
            binding.list.addView(month.root)
        }
    }
}
