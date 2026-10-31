package com.abdulrahman.fakelocation

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.abdulrahman.fakelocation.data.PlacesRepository
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.RoutesRepository
import com.abdulrahman.fakelocation.system.AppShortcuts
import com.abdulrahman.fakelocation.system.TripScheduler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().apply {
            load(this@App, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            // Lets Google tiles work with a key restricted to this app.
            additionalHttpRequestProperties.putAll(com.abdulrahman.fakelocation.geo.google.GoogleApi.appHeaders(this@App))
        }
        applyMapCacheLimit(Prefs(this).mapCacheMb)
        applyTheme(Prefs(this).theme)
        com.abdulrahman.fakelocation.nav.NavSession.init(this)

        // Weekly route alarms and launcher shortcuts follow the saved routes and favorites, whichever screen changed them.
        val scope = MainScope()
        scope.launch {
            RoutesRepository.get(this@App).routes
                .map { routes ->
                    routes.filter { it.timing.repeats }.map {
                        listOf(it.id, it.timing, it.path?.durationSeconds, it.speed, it.returnTrip, it.laps, it.stops.map { s -> s.waitMinutes })
                    }
                }
                .distinctUntilChanged()
                .collect { TripScheduler.update(this@App) }
        }
        scope.launch {
            PlacesRepository.get(this@App).favorites.collect {
                AppShortcuts.update(this@App, it)
                com.abdulrahman.fakelocation.system.FavoritesWidget.update(this@App, it)
            }
        }
    }

    companion object {
        /** Light, dark, or whatever the phone uses (Settings → Theme). */
        fun applyTheme(theme: String) = AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )

        /**
         * Every map tile you look at is kept on the phone (up to this size), so those areas still show without
         * internet. OpenStreetMap's free servers don't allow downloading whole areas in advance.
         */
        fun applyMapCacheLimit(megabytes: Int) {
            Configuration.getInstance().apply {
                tileFileSystemCacheMaxBytes = megabytes * 1024L * 1024L
                tileFileSystemCacheTrimBytes = (megabytes * 0.85).toLong() * 1024L * 1024L
            }
        }
    }
}
