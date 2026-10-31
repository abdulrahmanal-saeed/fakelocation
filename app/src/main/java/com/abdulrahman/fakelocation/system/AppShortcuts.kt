package com.abdulrahman.fakelocation.system

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Favorite
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.PlacesRepository
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.ui.NavigateActivity

/**
 * Long-press the app icon: navigate Home or to Work, go to one of the first favorites, or stop faking, without
 * opening the app first.
 */
object AppShortcuts {
    /** Launchers show about this many. */
    private const val MAX_SHORTCUTS = 5

    fun update(context: Context, favorites: List<Favorite> = PlacesRepository.get(context).favorites.value) {
        val prefs = Prefs(context)
        val navigate = listOfNotNull(
            prefs.home?.let { navigateShortcut(context, "home", it, R.string.shortcut_home, R.string.shortcut_home_long) },
            prefs.work?.let { navigateShortcut(context, "work", it, R.string.shortcut_work, R.string.shortcut_work_long) },
        )
        val shortcuts = navigate + favorites.take((MAX_SHORTCUTS - 1 - navigate.size).coerceAtLeast(0)).mapIndexed { i, favorite ->
            ShortcutInfoCompat.Builder(context, "favorite_${favorite.id}")
                .setShortLabel(favorite.name.take(24))
                .setLongLabel(context.getString(R.string.shortcut_go_to, favorite.name))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_place))
                .setIntent(QuickActionActivity.teleport(context, favorite.position, favorite.name))
                .setRank(navigate.size + i + 1)
                .build()
        } + ShortcutInfoCompat.Builder(context, "stop")
            .setShortLabel(context.getString(R.string.shortcut_stop))
            .setLongLabel(context.getString(R.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_stop))
            .setIntent(QuickActionActivity.stop(context))
            .setRank(0)
            .build()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
            .onFailure { Log.w("AppShortcuts", "shortcuts not updated", it) }
    }

    /** Real navigation to [place] (opens the Navigate screen with the route ready). */
    private fun navigateShortcut(context: Context, id: String, place: Place, short: Int, long: Int) =
        ShortcutInfoCompat.Builder(context, "navigate_$id")
            .setShortLabel(context.getString(short))
            .setLongLabel(context.getString(long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_navigate))
            .setIntent(NavigateActivity.intent(context, place).setAction(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            .setRank(if (id == "home") 1 else 2)
            .build()
}
