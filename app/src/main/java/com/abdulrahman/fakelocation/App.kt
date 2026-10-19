package com.abdulrahman.fakelocation

import android.app.Application
import com.abdulrahman.fakelocation.data.Prefs
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
    }

    companion object {
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
