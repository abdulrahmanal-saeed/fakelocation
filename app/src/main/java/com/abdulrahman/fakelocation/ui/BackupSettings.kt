package com.abdulrahman.fakelocation.ui

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.App
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Backup
import com.abdulrahman.fakelocation.data.PlacesRepository
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.RoutesRepository
import com.abdulrahman.fakelocation.databinding.FragmentSettingsBinding
import com.abdulrahman.fakelocation.system.StatusWidget
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The "Backup & restore" page: everything in one JSON file the user keeps (Drive, Files, WhatsApp to self…). */
class BackupSettings(
    private val fragment: Fragment,
    private val binding: () -> FragmentSettingsBinding?,
    private val onRestored: () -> Unit,
) {

    private val context get() = fragment.requireContext()
    private val prefs get() = Prefs(context)
    private var includeKey = false

    private val createFile = fragment.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) export(uri)
    }

    // Files apps often call a .json file "application/octet-stream", so any file can be picked and is checked after.
    private val openFile = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) read(uri)
    }

    fun setUp() {
        val b = binding() ?: return
        b.exportRow.setOnClickListener { askAndExport() }
        b.importRow.setOnClickListener { openFile.launch(arrayOf("*/*")) }
    }

    fun render() {
        val b = binding() ?: return
        val places = PlacesRepository.get(context)
        b.exportHint.text = context.getString(
            R.string.backup_export_hint,
            places.favorites.value.size, RoutesRepository.get(context).routes.value.size, places.history.value.size,
        )
    }

    private fun askAndExport() {
        val name = "FakeLocation-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
        if (!prefs.hasGoogleKey) {
            includeKey = false
            createFile.launch(name)
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.backup_key_title)
            .setMessage(R.string.backup_key_body)
            .setPositiveButton(R.string.backup_key_include) { _, _ -> includeKey = true; createFile.launch(name) }
            .setNegativeButton(R.string.backup_key_leave_out) { _, _ -> includeKey = false; createFile.launch(name) }
            .show()
    }

    private fun export(uri: Uri) {
        val app = context.applicationContext
        val places = PlacesRepository.get(app)
        val contents = Backup.Contents(
            favorites = places.favorites.value,
            history = places.history.value,
            routes = RoutesRepository.get(app).routes.value,
            settings = prefs.settingsForBackup(includeKey),
            appVersion = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty(),
        )
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Backup.write(contents).toByteArray()) }
                }.onFailure { Log.w(TAG, "backup not written", it) }.isSuccess
            }
            Toast.makeText(app, if (saved) R.string.backup_saved else R.string.backup_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun read(uri: Uri) {
        val app = context.applicationContext
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = app.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    Backup.read(text, Prefs.BACKUP_SETTINGS)
                }
            }
            result.onSuccess(::askHowToRestore).onFailure {
                Log.w(TAG, "backup not readable", it)
                Toast.makeText(app, if (it is Backup.NotABackupException) R.string.backup_not_a_backup else R.string.backup_read_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun askHowToRestore(backup: Backup.Contents) {
        if (fragment.view == null) return
        val made = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.createdAt))
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.backup_restore_title)
            .setMessage(context.getString(R.string.backup_restore_body, made, backup.favorites.size, backup.routes.size, backup.history.size))
            .setPositiveButton(R.string.backup_restore_merge) { _, _ -> restore(backup, replace = false) }
            .setNeutralButton(R.string.backup_restore_replace) { _, _ -> confirmReplace(backup) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmReplace(backup: Backup.Contents) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.backup_replace_title)
            .setMessage(R.string.backup_replace_body)
            .setPositiveButton(R.string.backup_restore_replace) { _, _ -> restore(backup, replace = true) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restore(backup: Backup.Contents, replace: Boolean) {
        val app = context.applicationContext
        val places = PlacesRepository.get(app)
        val routes = RoutesRepository.get(app)
        val result = if (replace) backup else Backup.merge(
            Backup.Contents(places.favorites.value, places.history.value, routes.routes.value, emptyMap()), backup,
        )
        places.replaceAll(result.favorites, result.history)
        routes.replaceAll(result.routes)
        if (replace) {
            prefs.restoreSettings(backup.settings, replace = true)
            App.applyMapCacheLimit(prefs.mapCacheMb)
        }
        StatusWidget.update(app)
        Toast.makeText(app, R.string.backup_restored, Toast.LENGTH_SHORT).show()
        onRestored()
    }

    private companion object {
        const val TAG = "BackupSettings"
    }
}
