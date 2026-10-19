package com.abdulrahman.fakelocation.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.databinding.FragmentSettingsBinding
import com.abdulrahman.fakelocation.mock.AlarmPlayer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.App
import com.abdulrahman.fakelocation.system.MapCache
import kotlinx.coroutines.launch

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs
    private var google: GoogleSettings? = null
    private var costs: CostSettings? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSettingsBinding.bind(view)
        binding.root.padForSystemBars(top = true)
        prefs = Prefs(requireContext())
        val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        binding.versionText.text = info.versionName

        binding.arriveRow.setOnClickListener {
            choose(R.string.alert_before_arriving, listOf(0, 1, 2, 3, 5, 10), prefs.alertBeforeArriveMinutes) {
                prefs.alertBeforeArriveMinutes = it
            }
        }
        binding.leaveRow.setOnClickListener {
            choose(R.string.alert_before_leaving, listOf(0, 1, 2, 3, 5), prefs.alertBeforeLeaveMinutes) {
                prefs.alertBeforeLeaveMinutes = it
            }
        }
        binding.trafficRow.setOnClickListener {
            val options = listOf(0, 15, 30, 50)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.traffic)
                .setSingleChoiceItems(options.map(::trafficLabel).toTypedArray(), options.indexOf(prefs.trafficPercent)) { dialog, which ->
                    prefs.trafficPercent = options[which]
                    render()
                    dialog.dismiss()
                }
                .show()
        }
        binding.silentSwitch.setOnCheckedChangeListener { _, checked -> prefs.ringInSilent = checked }
        binding.vibrateSwitch.setOnCheckedChangeListener { _, checked -> prefs.vibrate = checked }
        binding.naturalSwitch.setOnCheckedChangeListener { _, checked -> prefs.naturalMovement = checked }
        binding.fullScreenRow.setOnClickListener { openFullScreenSettings() }
        binding.testAlarmRow.setOnClickListener { testAlarm() }
        binding.setupRow.setOnClickListener { (requireActivity() as MainActivity).openSetup() }
        google = GoogleSettings(this, binding) { }.also { it.setUp() }
        costs = CostSettings(this, binding).also { it.setUp() }
        binding.offlineRow.setOnClickListener {
            val options = listOf(300, 600, 1000, 2000)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.offline_limit_title)
                .setSingleChoiceItems(options.map { megabytes(it.toLong() * 1024 * 1024) }.toTypedArray(), options.indexOf(prefs.mapCacheMb)) { dialog, which ->
                    prefs.mapCacheMb = options[which]
                    App.applyMapCacheLimit(options[which])
                    render()
                    dialog.dismiss()
                }
                .show()
        }
        binding.clearMapRow.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clear_saved_map)
                .setMessage(R.string.clear_saved_map_body)
                .setPositiveButton(R.string.clear) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        MapCache.clear()
                        requireContext().toast(R.string.saved_map_cleared)
                        render()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) render()
    }

    private fun render() {
        val b = _binding ?: return
        google?.render()
        costs?.render()
        b.arriveValue.text = minutes(prefs.alertBeforeArriveMinutes)
        b.leaveValue.text = minutes(prefs.alertBeforeLeaveMinutes)
        b.trafficValue.text = trafficLabel(prefs.trafficPercent)
        b.silentSwitch.isChecked = prefs.ringInSilent
        b.vibrateSwitch.isChecked = prefs.vibrate
        b.naturalSwitch.isChecked = prefs.naturalMovement
        val needsFullScreen = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        b.fullScreenRow.isVisible = needsFullScreen
        b.fullScreenDivider.isVisible = needsFullScreen
        b.fullScreenStatus.setText(if (requireContext().canShowFullScreenAlarms()) R.string.full_screen_allowed else R.string.full_screen_needed)
        viewLifecycleOwner.lifecycleScope.launch {
            val used = MapCache.sizeBytes()
            _binding?.offlineValue?.text = getString(R.string.offline_used, megabytes(used), megabytes(prefs.mapCacheMb * 1024L * 1024L))
        }
    }

    private fun megabytes(bytes: Long): String =
        if (bytes >= 1000L * 1024 * 1024) String.format(java.util.Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
        else "${bytes / 1024 / 1024} MB"

    private fun minutes(value: Int) = if (value == 0) getString(R.string.off) else getString(R.string.minutes_value, value)

    private fun trafficLabel(percent: Int) = if (percent == 0) getString(R.string.traffic_none) else getString(R.string.traffic_value, percent)

    private fun choose(title: Int, options: List<Int>, current: Int, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options.map(::minutes).toTypedArray(), options.indexOf(current)) { dialog, which ->
                onPick(options[which])
                render()
                dialog.dismiss()
            }
            .show()
    }

    private fun openFullScreenSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", requireContext().packageName, null))
            )
        }
    }

    /** Rings the alarm now, exactly like a real stop alarm, so the user can check volume and silent mode. */
    private fun testAlarm() {
        val context = requireContext()
        if (prefs.ringInSilent) AlarmPlayer.start(context, sound = true, vibrate = prefs.vibrate)
        startActivity(
            AlarmActivity.intent(
                context, AlarmActivity.Kind.ARRIVING, 2, 3, getString(R.string.test_alarm),
                System.currentTimeMillis() + prefs.alertBeforeArriveMinutes.coerceAtLeast(1) * 60_000L, null
            )
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

/** Android 14+ asks the user before an app may show alarms over the lock screen. */
fun android.content.Context.canShowFullScreenAlarms(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
