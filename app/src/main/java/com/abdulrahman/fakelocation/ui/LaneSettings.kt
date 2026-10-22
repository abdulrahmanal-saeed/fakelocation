package com.abdulrahman.fakelocation.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.databinding.FragmentSettingsBinding
import com.abdulrahman.fakelocation.nav.NavSession
import com.abdulrahman.fakelocation.nav.VoiceOutput
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The "Lanes & voice" settings: lane guidance, the lane camera, GPS precision and the navigation voice. */
class LaneSettings(private val fragment: Fragment, private val binding: () -> FragmentSettingsBinding?) {

    private val context get() = fragment.requireContext()
    private val prefs get() = Prefs(context)
    private val rates = listOf(0.8f to R.string.voice_slow, 0.95f to R.string.voice_normal, 1.15f to R.string.voice_fast)

    private val askCamera: ActivityResultLauncher<String> =
        fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            prefs.laneCamera = granted
            render()
        }

    fun setUp() {
        val b = binding() ?: return
        b.laneGuidanceSwitch.setOnCheckedChangeListener { _, on -> prefs.laneGuidance = on }
        b.laneCameraSwitch.setOnCheckedChangeListener { _, on ->
            if (on && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                askCamera.launch(Manifest.permission.CAMERA)
            } else {
                prefs.laneCamera = on
            }
        }
        b.voiceRow.setOnClickListener { chooseVoice() }
        b.voiceLevelRow.setOnClickListener { chooseVoiceLevel(context) { render() } }
        b.musicRow.setOnClickListener {
            val options = listOf(true to R.string.music_pause, false to R.string.music_lower)
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_music)
                .setSingleChoiceItems(options.map { context.getString(it.second) }.toTypedArray(), if (prefs.pauseMusicForVoice) 0 else 1) { d, which ->
                    prefs.pauseMusicForVoice = options[which].first
                    render()
                    d.dismiss()
                }
                .show()
        }
        b.voiceCallSwitch.setOnCheckedChangeListener { _, on ->
            if (on != prefs.voiceOverCall) {
                prefs.voiceOverCall = on
                NavSession.previewVoice()
            }
        }
        b.voiceRateRow.setOnClickListener {
            val current = rates.indexOfFirst { kotlin.math.abs(it.first - prefs.navSpeechRate) < 0.01f }.coerceAtLeast(1)
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_voice_rate)
                .setSingleChoiceItems(rates.map { context.getString(it.second) }.toTypedArray(), current) { d, which ->
                    prefs.navSpeechRate = rates[which].first
                    NavSession.previewVoice()
                    render()
                    d.dismiss()
                }
                .show()
        }
        b.testVoiceRow.setOnClickListener {
            if (NavSession.voiceChoices().isEmpty()) context.toast(R.string.settings_voice_loading) else NavSession.previewVoice()
        }
    }

    private fun chooseVoice() {
        val voices = NavSession.voiceChoices()
        if (voices.isEmpty()) {
            context.toast(R.string.settings_voice_loading)
            return
        }
        val names = listOf("") + voices.map { it.first }
        val labels = listOf(context.getString(R.string.settings_voice_best)) + voices.map { it.second }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_voice)
            .setSingleChoiceItems(labels.toTypedArray(), names.indexOf(prefs.navVoice).coerceAtLeast(0)) { _, which ->
                prefs.navVoice = names[which]
                NavSession.previewVoice()
                render()
            }
            .setPositiveButton(R.string.save, null)
            .show()
    }

    fun render() {
        val b = binding() ?: return
        b.laneGuidanceSwitch.isChecked = prefs.laneGuidance
        b.laneCameraSwitch.isChecked = prefs.laneCamera &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        b.gnssValue.setText(if (prefs.seenDualFrequency) R.string.settings_gnss_dual else R.string.settings_gnss_single)
        val voices = NavSession.voiceChoices()
        b.voiceValue.text = voices.firstOrNull { it.first == prefs.navVoice }?.second ?: context.getString(R.string.settings_voice_best)
        b.voiceLevelValue.setText(levelLabel(prefs.voiceLevel))
        b.musicValue.setText(if (prefs.pauseMusicForVoice) R.string.music_pause else R.string.music_lower)
        b.voiceCallSwitch.isChecked = prefs.voiceOverCall
        val rate = rates.minByOrNull { kotlin.math.abs(it.first - prefs.navSpeechRate) }!!
        b.voiceRateValue.setText(rate.second)
    }
}

fun levelLabel(level: VoiceOutput.Level) = when (level) {
    VoiceOutput.Level.MEDIA -> R.string.voice_level_media
    VoiceOutput.Level.LOUD -> R.string.voice_level_loud
    VoiceOutput.Level.LOUDER -> R.string.voice_level_louder
    VoiceOutput.Level.MAX -> R.string.voice_level_max
}

/** Pick how loud the navigation voice is; plays a sample with the new level. */
fun chooseVoiceLevel(context: android.content.Context, done: () -> Unit) {
    val prefs = Prefs(context)
    val levels = VoiceOutput.Level.entries
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.settings_voice_level)
        .setSingleChoiceItems(levels.map { context.getString(levelLabel(it)) }.toTypedArray(), levels.indexOf(prefs.voiceLevel)) { _, which ->
            prefs.voiceLevel = levels[which]
            NavSession.previewVoice()
            done()
        }
        .setPositiveButton(R.string.save, null)
        .show()
}
