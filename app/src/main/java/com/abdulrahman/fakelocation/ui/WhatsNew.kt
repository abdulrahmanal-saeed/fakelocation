package com.abdulrahman.fakelocation.ui

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import com.abdulrahman.fakelocation.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** "What's new" once after each update (not on a fresh install), and from Settings → About any time. */
object WhatsNew {
    private const val KEY_SEEN = "whats_new_seen"

    /** Called on the main screen. A fresh install marks itself seen when setup finishes, so this only fires on updates. */
    fun showIfUpdated(activity: Activity) {
        val prefs = activity.getSharedPreferences("fake_location", Context.MODE_PRIVATE)
        val current = versionCode(activity)
        val seen = prefs.getLong(KEY_SEEN, -1)
        prefs.edit { putLong(KEY_SEEN, current) }
        // -1 here means an update from a version before this screen existed.
        if (seen < current) show(activity)
    }

    /** First-time setup is done: nothing is "new" for someone who just installed the app. */
    fun markSeen(context: Context) {
        context.getSharedPreferences("fake_location", Context.MODE_PRIVATE).edit { putLong(KEY_SEEN, versionCode(context)) }
    }

    fun show(activity: Activity) {
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.whats_new_title, version))
            .setMessage(R.string.whats_new_body)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun versionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
}
