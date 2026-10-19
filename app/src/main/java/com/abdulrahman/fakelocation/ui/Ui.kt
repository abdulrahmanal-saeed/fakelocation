package com.abdulrahman.fakelocation.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.HistoryEntry
import com.abdulrahman.fakelocation.data.formatDuration
import com.abdulrahman.fakelocation.databinding.DialogNameBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Adds the status / navigation bar heights to this view's own padding. */
fun View.padForSystemBars(top: Boolean = true, bottom: Boolean = false) {
    val start = Pair(paddingTop, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.updatePadding(
            top = start.first + if (top) bars.top else 0,
            bottom = start.second + if (bottom) bars.bottom else 0
        )
        insets
    }
}

fun Context.toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

fun Context.hasLocationPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun Context.openDeveloperOptions() {
    try {
        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    } catch (_: Exception) {
        Toast.makeText(this, R.string.developer_options_disabled, Toast.LENGTH_LONG).show()
    }
}

fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("coordinates", text))
}

fun Context.clipboardText(): String? =
    getSystemService(ClipboardManager::class.java).primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }

/** Asks for a favorite's name, prefilled with [initial]. */
fun Context.askName(title: Int, initial: String, onName: (String) -> Unit) {
    val binding = DialogNameBinding.inflate(LayoutInflater.from(this))
    binding.nameInput.setText(initial)
    binding.nameInput.setSelection(initial.length)
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(binding.root)
        .setPositiveButton(R.string.save) { _, _ ->
            binding.nameInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(onName)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
    binding.nameInput.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            true
        } else {
            false
        }
    }
    binding.nameInput.requestFocus()
}

/** "Today, 09:12 · stayed 2h 10m" or "Now · 12m". */
fun Context.historySubtitle(entry: HistoryEntry, now: Long = System.currentTimeMillis()): String {
    val end = entry.endedAt ?: return getString(R.string.history_now, formatDuration(now - entry.startedAt))
    return getString(R.string.history_stayed, whenText(entry.startedAt), formatDuration(end - entry.startedAt))
}

private fun Context.whenText(time: Long): String {
    val clock = SimpleDateFormat("HH:mm", Locale.US).format(Date(time))
    val day = when {
        DateUtils.isToday(time) -> getString(R.string.today)
        DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> getString(R.string.yesterday)
        Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR) ->
            SimpleDateFormat("EEE d MMM", Locale.US).format(Date(time))
        else -> SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(time))
    }
    return "$day, $clock"
}

fun ViewGroup.inflate(layout: Int): View = LayoutInflater.from(context).inflate(layout, this, false)
