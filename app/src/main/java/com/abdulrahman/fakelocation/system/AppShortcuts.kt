package com.abdulrahman.fakelocation.system

import android.content.Context
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Favorite

/** Long-press the app icon: go to one of the first favorites, or stop faking, without opening the app. */
object AppShortcuts {
    private const val MAX_FAVORITES = 3

    fun update(context: Context, favorites: List<Favorite>) {
        val shortcuts = favorites.take(MAX_FAVORITES).mapIndexed { i, favorite ->
            ShortcutInfoCompat.Builder(context, "favorite_${favorite.id}")
                .setShortLabel(favorite.name.take(24))
                .setLongLabel(context.getString(R.string.shortcut_go_to, favorite.name))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_place))
                .setIntent(QuickActionActivity.teleport(context, favorite.position, favorite.name))
                .setRank(i + 1)
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
}
