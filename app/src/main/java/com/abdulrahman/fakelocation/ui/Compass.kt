package com.abdulrahman.fakelocation.ui

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import com.abdulrahman.fakelocation.data.LatLon

/**
 * Which way the top of the phone points, in degrees from true north (the magnetic heading corrected for where you
 * are). Only runs between [start] and [stop]; [heading] is null until the first reading or on phones without the
 * sensor.
 */
class Compass(private val context: Context) : SensorEventListener {

    var heading: Float? = null
        private set

    /** Called on each new reading. */
    var onChange: (() -> Unit)? = null

    /** Where we are, for the difference between magnetic and true north. */
    var near: LatLon? = null
        set(value) {
            if (value != null && (field == null || com.abdulrahman.fakelocation.data.distanceMeters(field!!, value) > 20_000)) {
                declination = GeomagneticField(value.lat.toFloat(), value.lon.toFloat(), 0f, System.currentTimeMillis()).declination
            }
            field = value
        }

    private var declination = 0f
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val matrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val angles = FloatArray(3)
    private var running = false

    val available get() = sensor != null

    fun start() {
        if (running || sensor == null) return
        running = true
        sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!running) return
        running = false
        sensors?.unregisterListener(this)
        heading = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        // The heading of the top of the screen, whichever way the phone is turned.
        @Suppress("DEPRECATION")
        val rotation = context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(matrix, x, y, remapped)
        SensorManager.getOrientation(remapped, angles)
        heading = ((Math.toDegrees(angles[0].toDouble()) + declination + 360) % 360).toFloat()
        onChange?.invoke()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
