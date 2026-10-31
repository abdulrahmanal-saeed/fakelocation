package com.abdulrahman.fakelocation.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.databinding.FragmentSettingsBinding
import com.abdulrahman.fakelocation.databinding.ItemHealthBinding
import com.abdulrahman.fakelocation.mock.LocationMocker

/**
 * The card at the top of Settings: whether Android lets the app do everything it needs, and a Fix button that opens
 * the right system screen for each thing that isn't set up. Only problems are listed.
 */
object HealthCheck {

    class Check(val title: Int, val detail: Int, val ok: Boolean, val fix: (Context) -> Unit)

    fun checks(context: Context): List<Check> = buildList {
        add(Check(R.string.health_mock, R.string.health_mock_detail, LocationMocker.isSelectedMockApp(context)) { it.openDeveloperOptions() })
        add(Check(R.string.health_location, R.string.health_location_detail, context.hasLocationPermission()) { it.openAppDetails() })
        add(
            Check(R.string.health_notifications, R.string.health_notifications_detail, NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                it.startOrAppDetails(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName))
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Check(R.string.full_screen_alarms, R.string.health_full_screen_detail, context.canShowFullScreenAlarms()) {
                it.startOrAppDetails(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", it.packageName, null)))
            })
        }
        val power = context.getSystemService(PowerManager::class.java)
        add(Check(R.string.health_battery, R.string.health_battery_detail, power.isIgnoringBatteryOptimizations(context.packageName)) {
            it.askToIgnoreBatteryOptimizations()
        })
    }

    fun render(context: Context, binding: FragmentSettingsBinding) {
        val problems = checks(context).filterNot { it.ok }
        binding.healthBadge.text = if (problems.isEmpty()) "✓" else "!"
        binding.healthBadge.backgroundTintList = ContextCompat.getColorStateList(context, if (problems.isEmpty()) R.color.success_bg else R.color.action_tint)
        binding.healthBadge.setTextColor(ContextCompat.getColor(context, if (problems.isEmpty()) R.color.success else R.color.action))
        binding.healthTitle.text = if (problems.isEmpty()) context.getString(R.string.health_all_good)
        else context.resources.getQuantityString(R.plurals.health_problems, problems.size, problems.size)
        binding.healthSubtitle.setText(if (problems.isEmpty()) R.string.health_all_good_detail else R.string.health_problems_detail)
        val list = binding.healthList
        list.removeAllViews()
        list.isVisible = problems.isNotEmpty()
        for (check in problems) {
            val row = ItemHealthBinding.inflate(LayoutInflater.from(context), list, true)
            row.title.setText(check.title)
            row.detail.setText(check.detail)
            row.fixButton.contentDescription = context.getString(R.string.health_fix_named, context.getString(check.title))
            row.fixButton.setOnClickListener { check.fix(context) }
        }
    }

    private fun Context.openAppDetails() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun Context.startOrAppDetails(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            openAppDetails()
        }
    }

    /** Asks right away ("Allow"/"Deny") where the phone supports it, otherwise opens the battery list. */
    @SuppressLint("BatteryLife") // a personal app that runs trips and navigation with the screen off
    private fun Context.askToIgnoreBatteryOptimizations() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            startOrAppDetails(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
