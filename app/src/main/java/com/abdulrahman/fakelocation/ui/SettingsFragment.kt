package com.abdulrahman.fakelocation.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.databinding.FragmentSettingsBinding
import com.abdulrahman.fakelocation.mock.AlarmPlayer
import com.abdulrahman.fakelocation.nav.NightMapMode
import com.abdulrahman.fakelocation.system.UpdateChecker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.App
import com.abdulrahman.fakelocation.system.MapCache
import kotlinx.coroutines.launch

/**
 * Settings: a main list (setup check, the pages, About) and one page at a time. Pages are parts of the same layout
 * shown one by one, so each settings group keeps its own small class (Google, costs, lanes…).
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    /** A Settings page and what "Reset to defaults" clears on it (null: nothing to reset). */
    private enum class Page(val title: Int, val reset: List<String>?) {
        ALERTS(R.string.page_alerts, Prefs.RESET_ALERTS),
        MOVEMENT(R.string.page_movement, Prefs.RESET_MOVEMENT),
        LANES(R.string.page_lanes, Prefs.RESET_LANES),
        COSTS(R.string.page_costs, Prefs.RESET_COSTS),
        GOOGLE(R.string.page_google, Prefs.RESET_GOOGLE),
        MAP(R.string.page_map, Prefs.RESET_MAP),
        BACKUP(R.string.page_backup, null),
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs
    private var google: GoogleSettings? = null
    private var costs: CostSettings? = null
    private val lanes = LaneSettings(this) { _binding }
    private val backup = BackupSettings(this, { _binding }) { render() }
    private val servers = ServerSettings(this) { _binding }
    private var page: Page? = null

    private val backToList = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = open(null)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSettingsBinding.bind(view)
        binding.root.padForSystemBars(top = true)
        prefs = Prefs(requireContext())
        val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        binding.versionText.text = info.versionName
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backToList)

        binding.backButton.setOnClickListener { open(null) }
        binding.openAlerts.setOnClickListener { open(Page.ALERTS) }
        binding.openMovement.setOnClickListener { open(Page.MOVEMENT) }
        binding.openLanes.setOnClickListener { open(Page.LANES) }
        binding.openCosts.setOnClickListener { open(Page.COSTS) }
        binding.openGoogle.setOnClickListener { open(Page.GOOGLE) }
        binding.openMap.setOnClickListener { open(Page.MAP) }
        binding.openBackup.setOnClickListener { open(Page.BACKUP) }
        binding.openDriveLog.setOnClickListener { startActivity(android.content.Intent(requireContext(), DriveLogActivity::class.java)) }
        binding.whatsNewRow.setOnClickListener { WhatsNew.show(requireActivity()) }
        binding.updateRow.setOnClickListener { checkForUpdate() }
        binding.themeRow.setOnClickListener {
            val themes = listOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.theme)
                .setSingleChoiceItems(themes.map { getString(themeLabel(it)) }.toTypedArray(), themes.indexOf(prefs.theme)) { dialog, which ->
                    dialog.dismiss()
                    prefs.theme = themes[which]
                    App.applyTheme(themes[which]) // recreates the screens in the new colours
                }
                .show()
        }
        binding.nightMapRow.setOnClickListener {
            val modes = NightMapMode.entries
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.night_map)
                .setSingleChoiceItems(modes.map { getString(nightLabel(it)) }.toTypedArray(), modes.indexOf(prefs.nightMap)) { dialog, which ->
                    prefs.nightMap = modes[which]
                    render()
                    dialog.dismiss()
                }
                .show()
        }
        binding.resetButton.setOnClickListener { page?.let(::confirmReset) }

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
        binding.redLightsRow.setOnClickListener {
            val options = RED_LIGHT_OPTIONS
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.red_lights)
                .setSingleChoiceItems(options.map { getString(it.second) }.toTypedArray(), options.indexOfFirst { it.first == prefs.redLightPercent }) { dialog, which ->
                    prefs.redLightPercent = options[which].first
                    render()
                    dialog.dismiss()
                }
                .show()
        }
        binding.updateRateRow.setOnClickListener {
            val options = listOf(1, 2, 5)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.update_rate)
                .setSingleChoiceItems(options.map { getString(R.string.update_rate_value, it) }.toTypedArray(), options.indexOf(prefs.fakeUpdatesPerSecond)) { dialog, which ->
                    prefs.fakeUpdatesPerSecond = options[which]
                    render()
                    dialog.dismiss()
                }
                .show()
        }
        binding.silentSwitch.setOnCheckedChangeListener { _, checked -> prefs.ringInSilent = checked }
        binding.vibrateSwitch.setOnCheckedChangeListener { _, checked -> prefs.vibrate = checked }
        binding.naturalSwitch.setOnCheckedChangeListener { _, checked -> prefs.naturalMovement = checked }
        binding.vectorMapSwitch.setOnCheckedChangeListener { _, checked -> prefs.vectorNavMap = checked }
        binding.fullScreenRow.setOnClickListener { openFullScreenSettings() }
        binding.testAlarmRow.setOnClickListener { testAlarm() }
        binding.setupRow.setOnClickListener { (requireActivity() as MainActivity).openSetup() }
        google = GoogleSettings(this, binding) { }.also { it.setUp() }
        costs = CostSettings(this, binding).also { it.setUp() }
        lanes.setUp()
        backup.setUp()
        servers.setUp()
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
        open(savedInstanceState?.getString(STATE_PAGE)?.let { name -> Page.entries.firstOrNull { it.name == name } })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        page?.let { outState.putString(STATE_PAGE, it.name) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        backToList.isEnabled = page != null && !hidden
        if (!hidden) render()
    }

    /** Shows one page, or the main list for null. */
    private fun open(target: Page?) {
        val b = _binding ?: return
        page = target
        b.mainPage.isVisible = target == null
        b.alertsPage.isVisible = target == Page.ALERTS
        b.movementPage.isVisible = target == Page.MOVEMENT
        b.lanesPage.isVisible = target == Page.LANES
        b.costsPage.isVisible = target == Page.COSTS
        b.googlePage.isVisible = target == Page.GOOGLE
        b.mapPage.isVisible = target == Page.MAP
        b.backupPage.isVisible = target == Page.BACKUP
        b.backButton.isVisible = target != null
        b.pageTitle.setText(target?.title ?: R.string.nav_settings)
        b.resetButton.isVisible = target?.reset != null
        backToList.isEnabled = target != null && !isHidden
        b.root.scrollTo(0, 0)
        render()
    }

    private fun confirmReset(target: Page) {
        val keys = target.reset ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.reset_title, getString(target.title)))
            .setMessage(R.string.reset_body)
            .setPositiveButton(R.string.reset) { _, _ ->
                prefs.reset(keys)
                if (target == Page.MAP) App.applyMapCacheLimit(prefs.mapCacheMb)
                requireContext().toast(R.string.reset_done)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun render() {
        val b = _binding ?: return
        HealthCheck.render(requireContext(), b)
        b.googleSummary.setText(if (prefs.hasGoogleKey) R.string.page_google_on else R.string.page_google_hint)
        google?.render()
        costs?.render()
        lanes.render()
        backup.render()
        servers.render()
        b.nightMapValue.setText(nightLabel(prefs.nightMap))
        b.themeValue.setText(themeLabel(prefs.theme))
        b.arriveValue.text = minutes(prefs.alertBeforeArriveMinutes)
        b.leaveValue.text = minutes(prefs.alertBeforeLeaveMinutes)
        b.trafficValue.text = trafficLabel(prefs.trafficPercent)
        b.updateRateValue.text = getString(R.string.update_rate_value, prefs.fakeUpdatesPerSecond)
        b.redLightsValue.setText(RED_LIGHT_OPTIONS.minBy { kotlin.math.abs(it.first - prefs.redLightPercent) }.second)
        b.silentSwitch.isChecked = prefs.ringInSilent
        b.vibrateSwitch.isChecked = prefs.vibrate
        b.naturalSwitch.isChecked = prefs.naturalMovement
        b.vectorMapSwitch.isChecked = prefs.vectorNavMap
        val needsFullScreen = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        b.fullScreenRow.isVisible = needsFullScreen
        b.fullScreenDivider.isVisible = needsFullScreen
        b.fullScreenStatus.setText(if (requireContext().canShowFullScreenAlarms()) R.string.full_screen_allowed else R.string.full_screen_needed)
        viewLifecycleOwner.lifecycleScope.launch {
            val used = MapCache.sizeBytes()
            _binding?.offlineValue?.text = getString(R.string.offline_used, megabytes(used), megabytes(prefs.mapCacheMb * 1024L * 1024L))
        }
    }

    /** Settings → About → Check for updates: asks GitHub now and says what it found. */
    private fun checkForUpdate() {
        val b = _binding ?: return
        b.updateValue.setText(R.string.update_checking)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { UpdateChecker.check(requireContext()) }
            val view = _binding ?: return@launch
            result.onSuccess { update ->
                if (update == null) view.updateValue.setText(R.string.update_none)
                else {
                    view.updateValue.text = getString(R.string.update_available, update.versionName)
                    UpdateChecker.offer(requireActivity(), update)
                }
            }.onFailure { view.updateValue.setText(R.string.update_failed) }
        }
    }

    private fun themeLabel(theme: String) = when (theme) {
        Prefs.THEME_LIGHT -> R.string.theme_light
        Prefs.THEME_DARK -> R.string.theme_dark
        else -> R.string.theme_system
    }

    private fun nightLabel(mode: NightMapMode) = when (mode) {
        NightMapMode.AUTO -> R.string.night_map_auto
        NightMapMode.ALWAYS -> R.string.night_map_always
        NightMapMode.OFF -> R.string.off
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

    private companion object {
        const val STATE_PAGE = "settings_page"
    }
}

/** Android 14+ asks the user before an app may show alarms over the lock screen. */
fun android.content.Context.canShowFullScreenAlarms(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

/** Red lights on fake trips: share of lights (in 100) and its label. */
private val RED_LIGHT_OPTIONS = listOf(
    0 to R.string.red_lights_none,
    25 to R.string.red_lights_few,
    45 to R.string.red_lights_some,
    65 to R.string.red_lights_many,
)
