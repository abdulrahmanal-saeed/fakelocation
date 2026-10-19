package com.abdulrahman.fakelocation.nav

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * The car's turn rate from the gyroscope, whatever way the phone is mounted: the rotation is measured around
 * "up" (from the gravity sensor), so a phone standing in a holder or lying flat both work.
 */
class LaneSensors(context: Context, private val onYawRate: (Long, Double) -> Unit) : SensorEventListener {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val gravity = FloatArray(3)
    private var haveGravity = false

    val available get() = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    fun start() {
        manager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager.unregisterListener(this)
        haveGravity = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                val n = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
                if (n < 1f) return
                for (i in 0..2) gravity[i] = event.values[i] / n
                haveGravity = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!haveGravity) return
                // Gravity reads "up"; counter-clockwise around up is a left turn, compass headings grow clockwise.
                val up = event.values[0] * gravity[0] + event.values[1] * gravity[1] + event.values[2] * gravity[2]
                onYawRate(System.currentTimeMillis(), -Math.toDegrees(up.toDouble()))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
