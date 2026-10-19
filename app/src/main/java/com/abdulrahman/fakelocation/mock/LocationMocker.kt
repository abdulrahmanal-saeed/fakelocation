package com.abdulrahman.fakelocation.mock

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

/**
 * Pushes fake positions into both the platform location providers (GPS / network)
 * and Google's fused provider, so apps see the same position whichever API they use.
 */
class LocationMocker(private val context: Context) {

    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val allProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private var started = false

    /**
     * Whether the raw GPS chip is faked too. Turned off during in-app navigation so the app can read the real
     * GPS; Google's location service (what Google Maps and most apps use) stays fake either way.
     */
    var fakeGpsChip = true
        private set

    private val providers get() = if (fakeGpsChip) allProviders else listOf(LocationManager.NETWORK_PROVIDER)

    fun setFakeGpsChip(on: Boolean) {
        if (on == fakeGpsChip) return
        fakeGpsChip = on
        if (!started) return
        val gps = LocationManager.GPS_PROVIDER
        if (on) {
            runCatching { locationManager.removeTestProvider(gps) }
            addTestProvider(gps)
            locationManager.setTestProviderEnabled(gps, true)
        } else {
            runCatching { locationManager.setTestProviderEnabled(gps, false) }
            runCatching { locationManager.removeTestProvider(gps) }
        }
    }

    /** Throws [SecurityException] when this app is not the selected mock location app. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        for (provider in providers) {
            runCatching { locationManager.removeTestProvider(provider) }
            addTestProvider(provider)
            locationManager.setTestProviderEnabled(provider, true)
        }
        fusedClient.setMockMode(true)
        started = true
    }

    @SuppressLint("MissingPermission")
    fun push(lat: Double, lon: Double, bearing: Float = 0f, speedMps: Float = 0f, accuracy: Float = 3f) {
        if (!started) return
        for (provider in providers) {
            locationManager.setTestProviderLocation(provider, buildLocation(provider, lat, lon, bearing, speedMps, accuracy))
        }
        fusedClient.setMockLocation(buildLocation("fused", lat, lon, bearing, speedMps, accuracy))
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!started) return
        for (provider in allProviders) {
            runCatching { locationManager.setTestProviderEnabled(provider, false) }
            runCatching { locationManager.removeTestProvider(provider) }
        }
        runCatching { fusedClient.setMockMode(false) }
        started = false
    }

    private fun addTestProvider(provider: String) {
        val isGps = provider == LocationManager.GPS_PROVIDER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            locationManager.addTestProvider(
                provider,
                ProviderProperties.Builder()
                    .setHasSatelliteRequirement(isGps)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(if (isGps) ProviderProperties.POWER_USAGE_HIGH else ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(if (isGps) ProviderProperties.ACCURACY_FINE else ProviderProperties.ACCURACY_COARSE)
                    .build()
            )
        } else {
            // Pre-Android 12 overload; the int constants share values with ProviderProperties.
            @Suppress("DEPRECATION")
            @SuppressLint("InlinedApi")
            locationManager.addTestProvider(
                provider, !isGps, isGps, false, false, true, true, true,
                if (isGps) ProviderProperties.POWER_USAGE_HIGH else ProviderProperties.POWER_USAGE_LOW,
                if (isGps) ProviderProperties.ACCURACY_FINE else ProviderProperties.ACCURACY_COARSE
            )
        }
    }

    private fun buildLocation(provider: String, lat: Double, lon: Double, bearing: Float, speedMps: Float, accuracyM: Float) =
        Location(provider).apply {
            latitude = lat
            longitude = lon
            altitude = 30.0
            accuracy = accuracyM
            this.bearing = bearing
            speed = speedMps
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            verticalAccuracyMeters = 3f
            bearingAccuracyDegrees = 1f
            speedAccuracyMetersPerSecond = 0.5f
        }

    companion object {
        /** True when this app is chosen in Developer options → "Select mock location app". */
        fun isSelectedMockApp(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
