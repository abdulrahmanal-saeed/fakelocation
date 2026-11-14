package com.abdulrahman.fakelocation.account

import android.content.Context
import androidx.core.content.edit
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val syncLock = Mutex()
    private val parkingKeys = setOf("plate", "recent_zones")

    fun show(activity: AppCompatActivity, user: FirebaseUser) {
        val selected = enabledGroups(activity, user).let { stored ->
            BooleanArray(groups.size) { if (stored.isEmpty()) it == 0 else groups[it] in stored }
        }
        MaterialAlertDialogBuilder(activity).setTitle(R.string.account_sync)
            .setMultiChoiceItems(labels.map(activity::getString).toTypedArray(), selected) { _, i, on -> selected[i] = on }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.account_sync_restore) { _, _ -> perform(activity) { restore(activity, user, selected) } }
            .setPositiveButton(R.string.account_sync_upload) { _, _ -> perform(activity) { upload(activity, user, selected) } }
            .show()
    }

    fun showAutoSync(activity: AppCompatActivity, user: FirebaseUser) {
        val enabled = enabledGroups(activity, user)
        val selected = BooleanArray(groups.size) { groups[it] in enabled }
        MaterialAlertDialogBuilder(activity).setTitle(R.string.account_sync_automatic)
            .setMultiChoiceItems(labels.map(activity::getString).toTypedArray(), selected) { _, i, on -> selected[i] = on }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.done) { _, _ ->
                val chosen = groups.filterIndexed { index, _ -> selected[index] }.toSet()
                syncSettings(activity).edit {
                    putStringSet(user.uid, chosen)
                    (enabled - chosen).forEach { remove(revisionKey(user, it)) }
                }
                if (chosen.isNotEmpty()) perform(activity) { sync(activity, user) }
            }.show()
    }

    private fun syncSettings(context: Context) = context.getSharedPreferences("wayshift_sync", Context.MODE_PRIVATE)
    private fun enabledGroups(context: Context, user: FirebaseUser): Set<String> =
        syncSettings(context).getStringSet(user.uid, emptySet()).orEmpty()

    private fun revisionKey(user: FirebaseUser, group: String) = "${user.uid}:$group:revision"

    suspend fun sync(context: Context) {
        val user = WayShiftAccount.auth(context)?.currentUser?.takeIf { it.isEmailVerified } ?: return
        sync(context, user)
    }

    private suspend fun sync(context: Context, user: FirebaseUser) = syncLock.withLock {
        val selected = enabledGroups(context, user)
        if (selected.isEmpty()) return@withLock
        val store = syncSettings(context)
        val database = database()
        val collection = profile(user).collection("backups")
        for (group in groups.filter { it in selected }) {
            if (WayShiftAccount.auth(context)?.currentUser?.uid != user.uid) return@withLock
            val reference = collection.document(group)
            val local = snapshot(context, group)
            val key = revisionKey(user, group)
            val previous = store.getString(key, null)
            val remote = database.runTransaction { tx ->
                val document = tx.get(reference)
                val cloud = document.record()
                if (cloud == null || cloud.revision == previous && local.revision != previous) {
                    tx.set(reference, local.fields())
                    null
                } else if (cloud.revision != local.revision) cloud else null
            }.awaitResult()
            if (WayShiftAccount.auth(context)?.currentUser?.uid != user.uid) return@withLock
            val applied = if (remote == null) local else {
                applyRecord(context, group, remote, replace = previous != null && local.revision == previous)
                val merged = snapshot(context, group)
                if (merged.revision != remote.revision) {
                    database.runTransaction { tx ->
                        val latest = tx.get(reference).record()
                        if (latest?.revision != remote.revision) throw FirebaseFirestoreException(
                            "Backup changed during sync", FirebaseFirestoreException.Code.ABORTED
                        )
                        tx.set(reference, merged.fields())
                    }.awaitResult()
                }
                merged
            }
            store.edit { putString(key, applied.revision) }
        }
    }

    private data class Record(val backup: String, val drives: String) {
        val revision: String by lazy {
            val normalized = org.json.JSONObject(backup).apply { remove("createdAt"); remove("appVersion") }
            MessageDigest.getInstance("SHA-256").digest((normalized.toString() + "\n" + drives).toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        fun fields(): Map<String, String> {
            require(backup.toByteArray().size + drives.toByteArray().size < 950_000)
            return mapOf("backup" to backup, "drives" to drives)
        }
    }

    private fun DocumentSnapshot.record(): Record? = getString("backup")?.let { Record(it, getString("drives") ?: "[]") }

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

    private fun snapshot(context: Context, group: String): Record {
        val prefs = Prefs(context)
        val places = PlacesRepository.get(context)
        val routes = RoutesRepository.get(context)
        val settings = prefs.settingsForBackup(false).toSortedMap().filterKeys { it !in setOf("osrm_server", "valhalla_server") }
        val data = when (group) {
            "core" -> Backup.Contents(places.favorites.value, emptyList(), routes.routes.value, settings.filterKeys { it !in homeKeys && it !in parkingKeys })
            "history" -> Backup.Contents(emptyList(), places.history.value, emptyList(), emptyMap())
            "places" -> Backup.Contents(emptyList(), emptyList(), emptyList(), settings.filterKeys { it in homeKeys })
            else -> Backup.Contents(emptyList(), emptyList(), emptyList(), settings.filterKeys { it in parkingKeys })
        }
        val drives = if (group == "history") JSONArray(DriveLog.get(context).drives.value.map(DriveLog::write)).toString() else "[]"
        return Record(Backup.write(data), drives)
    }

    private suspend fun upload(context: Context, user: FirebaseUser, selected: BooleanArray) = syncLock.withLock {
        val collection = profile(user).collection("backups")
        val batch = database().batch()
        val records = groups.filterIndexed { i, _ -> selected[i] }.associateWith { snapshot(context, it) }
        records.forEach { (group, record) -> batch.set(collection.document(group), record.fields()) }
        batch.commit().awaitResult()
        syncSettings(context).edit { records.forEach { (group, record) -> putString(revisionKey(user, group), record.revision) } }
    }

    private suspend fun restore(context: Context, user: FirebaseUser, selected: BooleanArray) = syncLock.withLock {
        val docs = profile(user).collection("backups").get(Source.SERVER).awaitResult().documents
        for (doc in docs) {
            val index = groups.indexOf(doc.id)
            if (index < 0 || !selected[index]) continue
            val record = doc.record() ?: continue
            applyRecord(context, doc.id, record)
        }
    }

    private fun applyRecord(context: Context, group: String, record: Record, replace: Boolean = false) {
        val prefs = Prefs(context)
        val places = PlacesRepository.get(context)
        val routes = RoutesRepository.get(context)
        val backup = Backup.read(record.backup, Prefs.BACKUP_SETTINGS)
        if (group == "core" || group == "history") {
            val current = Backup.Contents(places.favorites.value, places.history.value, routes.routes.value, emptyMap())
            val merged = Backup.merge(current, backup)
            val favorites = if (group == "core" && replace) backup.favorites else merged.favorites
            places.replaceAll(favorites, merged.history)
            if (group == "core") routes.replaceAll(if (replace) backup.routes else merged.routes)
        }
        if (group == "history") {
            val log = DriveLog.get(context)
            val existing = log.drives.value.map { it.id }.toMutableSet()
            val list = JSONArray(record.drives)
            for (i in list.length() - 1 downTo 0) {
                val drive = DriveLog.read(list.getJSONObject(i))
                if (existing.add(drive.id)) log.add(drive)
            }
        }
        prefs.restoreSettings(backup.settings.filterKeys { it != Prefs.KEY_GOOGLE }, replace = false)
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
