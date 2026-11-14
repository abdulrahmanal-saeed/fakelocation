package com.abdulrahman.fakelocation.account

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Backup
import com.abdulrahman.fakelocation.data.DriveLog
import com.abdulrahman.fakelocation.data.PlacesRepository
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.RoutesRepository
import com.abdulrahman.fakelocation.ui.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray

internal object CloudBackup {
    private val groups = listOf("core", "history", "places", "parking")
    private val labels = intArrayOf(R.string.account_sync_basic, R.string.account_sync_history, R.string.account_sync_home, R.string.account_sync_plate)
    private val homeKeys = setOf("home_place", "work_place")
    private val parkingKeys = setOf("plate", "recent_zones")

    fun show(activity: AppCompatActivity, user: FirebaseUser) {
        val selected = booleanArrayOf(true, false, false, false)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.account_sync)
            .setMultiChoiceItems(labels.map(activity::getString).toTypedArray(), selected) { _, i, on -> selected[i] = on }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.account_sync_restore) { _, _ -> perform(activity) { restore(activity, user, selected) } }
            .setPositiveButton(R.string.account_sync_upload) { _, _ -> perform(activity) { upload(activity, user, selected) } }
            .show()
    }

    private fun perform(activity: AppCompatActivity, task: suspend () -> Unit) {
        activity.lifecycleScope.launch {
            try {
                task()
                activity.toast(R.string.account_sync_saved)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                activity.toast(R.string.account_failure)
            }
        }
    }

    private fun database() = FirebaseFirestore.getInstance()
    private fun profile(user: FirebaseUser) = database().collection("users").document(user.uid)

    private suspend fun upload(context: Context, user: FirebaseUser, selected: BooleanArray) {
        val db = database()
        val profile = profile(user)
        val prefs = Prefs(context)
        val places = PlacesRepository.get(context)
        val routes = RoutesRepository.get(context)
        val settings = prefs.settingsForBackup(false).filterKeys { it !in setOf("osrm_server", "valhalla_server") }
        val batch = db.batch()
        groups.forEachIndexed { i, group ->
            if (!selected[i]) return@forEachIndexed
            val data = when (group) {
                "core" -> Backup.Contents(places.favorites.value, emptyList(), routes.routes.value, settings.filterKeys { it !in homeKeys && it !in parkingKeys })
                "history" -> Backup.Contents(emptyList(), places.history.value, emptyList(), emptyMap())
                "places" -> Backup.Contents(emptyList(), emptyList(), emptyList(), settings.filterKeys { it in homeKeys })
                else -> Backup.Contents(emptyList(), emptyList(), emptyList(), settings.filterKeys { it in parkingKeys })
            }
            val json = Backup.write(data)
            val drives = if (group == "history") JSONArray(DriveLog.get(context).drives.value.map(DriveLog::write)).toString() else "[]"
            require(json.toByteArray().size + drives.toByteArray().size < 950_000)
            batch.set(profile.collection("backups").document(group), mapOf("backup" to json, "drives" to drives))
        }
        batch.commit().awaitResult()
    }

    private suspend fun restore(context: Context, user: FirebaseUser, selected: BooleanArray) {
        val docs = profile(user).collection("backups").get(Source.SERVER).awaitResult().documents
        val prefs = Prefs(context)
        val places = PlacesRepository.get(context)
        val routes = RoutesRepository.get(context)
        for (doc in docs) {
            val index = groups.indexOf(doc.id)
            if (index < 0 || !selected[index]) continue
            val backup = Backup.read(doc.getString("backup") ?: continue, Prefs.BACKUP_SETTINGS)
            if (doc.id == "core" || doc.id == "history") {
                val current = Backup.Contents(places.favorites.value, places.history.value, routes.routes.value, emptyMap())
                val merged = Backup.merge(current, backup)
                places.replaceAll(merged.favorites, merged.history)
                if (doc.id == "core") routes.replaceAll(merged.routes)
            }
            if (doc.id == "history") {
                val log = DriveLog.get(context)
                val existing = log.drives.value.map { it.id }.toSet()
                val list = JSONArray(doc.getString("drives") ?: "[]")
                for (i in list.length() - 1 downTo 0) {
                    val drive = DriveLog.read(list.getJSONObject(i))
                    if (drive.id !in existing) log.add(drive)
                }
            }
            prefs.restoreSettings(backup.settings.filterKeys { it != Prefs.KEY_GOOGLE }, replace = false)
        }
    }

    suspend fun delete(context: Context, user: FirebaseUser) {
        val token = user.getIdToken(true).awaitResult().token ?: error("No Firebase token")
        if (com.abdulrahman.fakelocation.geo.WayShiftApi.configured) {
            com.abdulrahman.fakelocation.geo.WayShiftApi.request("/v1/account/delete", org.json.JSONObject(), token)
        }
        val db = database()
        val profile = profile(user)
        profile.set(mapOf("deleting" to true)).awaitResult()
        val batch = db.batch()
        groups.forEach { batch.delete(profile.collection("backups").document(it)) }
        batch.commit().awaitResult()
    }
}
