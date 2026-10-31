package com.abdulrahman.fakelocation.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.geo.Http
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Is there a newer build than this one? Reads the repository's "Latest build" release, whose notes CI writes as
 * "… · versionName 2.3.0 · versionCode 19", and offers its APK.
 */
object UpdateChecker {
    private const val RELEASE_API = "https://api.github.com/repos/abdulrahmanal-saeed/fakelocation/releases/tags/latest-build"
    private const val APK_NAME = "FakeLocation.apk"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val DAY_MS = 24 * 60 * 60_000L

    data class Update(val versionCode: Long, val versionName: String, val apkUrl: String)

    /** The build described by GitHub's release JSON, or null when it says nothing usable. */
    fun parse(json: JSONObject): Update? {
        val body = json.optString("body")
        val code = Regex("""versionCode\s*[:=]?\s*(\d+)""").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val name = Regex("""versionName\s*[:=]?\s*([0-9][\w.\-]*)""").find(body)?.groupValues?.get(1) ?: code.toString()
        val assets = json.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name") == APK_NAME }?.optString("browser_download_url")
            ?.takeIf { it.isNotBlank() } ?: return null
        return Update(code, name, apk)
    }

    /** The newer build, null when this one is current. Throws when GitHub can't be reached. */
    suspend fun check(context: Context): Update? = withContext(Dispatchers.IO) {
        val json = JSONObject(Http.get(RELEASE_API, headers = mapOf("Accept" to "application/vnd.github+json")))
        context.getSharedPreferences("fake_location", Context.MODE_PRIVATE).edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
        parse(json)?.takeIf { it.versionCode > currentCode(context) }
    }

    /** At most once a day, quietly: only says something when there is a newer build. */
    suspend fun checkDaily(activity: Activity) {
        val prefs = activity.getSharedPreferences("fake_location", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < DAY_MS) return
        val update = runCatching { check(activity) }.onFailure { Log.i("UpdateChecker", "no update check: ${it.message}") }.getOrNull()
        if (update != null && !activity.isFinishing) offer(activity, update)
    }

    fun offer(activity: Activity, update: Update) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_title, update.versionName))
            .setMessage(R.string.update_body)
            .setPositiveButton(R.string.update_download) { _, _ ->
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.apkUrl)))
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun currentCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
}
