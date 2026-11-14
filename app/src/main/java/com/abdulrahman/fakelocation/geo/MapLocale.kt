package com.abdulrahman.fakelocation.geo

import android.content.Context
import androidx.core.os.ConfigurationCompat
import java.util.Locale

internal data class MapLocale(val language: String, val region: String?) {
    companion object {
        fun from(context: Context): MapLocale {
            val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
            return MapLocale(locale.language.ifBlank { "en" }, locale.country.takeIf { it.length == 2 })
        }
    }
}
