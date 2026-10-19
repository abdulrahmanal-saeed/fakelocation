package com.abdulrahman.fakelocation.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.databinding.ActivitySetupBinding
import com.abdulrahman.fakelocation.mock.LocationMocker

/** First-run checklist: location permission, notifications, and picking this app as the mock location app. */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private var askedLocationOnce = false

    private val locationLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
        // Android stops showing the prompt after two refusals: send the user to app settings instead.
        if (!hasLocationPermission() && askedLocationOnce &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            openAppSettings()
        }
        askedLocationOnce = true
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars(top = true, bottom = true)

        binding.allowLocation.setOnClickListener {
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        binding.allowNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.openDeveloperOptions.setOnClickListener { openDeveloperOptions() }
        binding.continueButton.setOnClickListener { finishSetup() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val location = hasLocationPermission()
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val mock = LocationMocker.isSelectedMockApp(this)

        markStep(binding.stepLocationBadge, "1", location)
        binding.stepLocationStatus.setText(if (location) R.string.allowed else R.string.not_allowed)
        binding.allowLocation.isVisible = !location

        markStep(binding.stepNotificationsBadge, "2", notifications)
        binding.stepNotificationsStatus.setText(if (notifications) R.string.allowed else R.string.not_allowed)
        binding.allowNotifications.isVisible = !notifications

        markStep(binding.stepMockBadge, "3", mock)
        binding.stepMockStatus.setText(if (mock) R.string.done else R.string.step_mock_hint)
        binding.stepMockDetails.isVisible = !mock
        binding.stepMock.setBackgroundResource(if (mock) R.drawable.bg_round else R.drawable.bg_step_focus)
    }

    private fun markStep(badge: TextView, number: String, done: Boolean) {
        badge.text = if (done) "✓" else number
        badge.setTextColor(ContextCompat.getColor(this, if (done) R.color.success else R.color.ink))
        badge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (done) R.color.success_bg else R.color.nav_active))
    }

    private fun finishSetup() {
        if (!hasLocationPermission()) {
            toast(R.string.permission_needed)
            binding.allowLocation.performClick()
            return
        }
        Prefs(this).setupDone = true
        if (!intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)) {
            // Forward anything we were launched with (e.g. a shared Google Maps link).
            startActivity(Intent(this, MainActivity::class.java).putExtras(intent))
        }
        finish()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    companion object {
        private const val EXTRA_FROM_SETTINGS = "from_settings"

        fun fromSettings(context: Context) =
            Intent(context, SetupActivity::class.java).putExtra(EXTRA_FROM_SETTINGS, true)
    }
}
