package com.abdulrahman.fakelocation.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Favorite
import com.abdulrahman.fakelocation.data.PlacesRepository
import com.abdulrahman.fakelocation.ui.MainActivity

/** Home-screen widget: tap a favorite to fake it there, or Stop. Kept current whenever favorites change (see App). */
class FavoritesWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)

    companion object {
        private val SLOTS = listOf(R.id.fav1, R.id.fav2, R.id.fav3, R.id.fav4)

        fun update(context: Context, favorites: List<Favorite> = PlacesRepository.get(context).favorites.value) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FavoritesWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, build(context, favorites))
        }

        private fun build(context: Context, favorites: List<Favorite>): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_favorites)
            val open = PendingIntent.getActivity(
                context, 30, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.favRoot, open)
            views.setViewVisibility(R.id.favEmpty, if (favorites.isEmpty()) View.VISIBLE else View.GONE)
            SLOTS.forEachIndexed { i, slot ->
                val favorite = favorites.getOrNull(i)
                views.setViewVisibility(slot, if (favorite == null) View.GONE else View.VISIBLE)
                if (favorite != null) {
                    views.setTextViewText(slot, favorite.name)
                    views.setContentDescription(slot, context.getString(R.string.shortcut_go_to, favorite.name))
                    views.setOnClickPendingIntent(
                        slot,
                        PendingIntent.getActivity(
                            context, 31 + i, QuickActionActivity.teleport(context, favorite.position, favorite.name),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
            }
            views.setOnClickPendingIntent(
                R.id.favStop,
                PendingIntent.getActivity(context, 39, QuickActionActivity.stop(context), PendingIntent.FLAG_IMMUTABLE),
            )
            return views
        }
    }
}
