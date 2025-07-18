package com.example.screenstabilizer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class SensorHandler(
    context: Context,
    private val listener: Listener
) : SensorEventListener {

    interface Listener {
        fun onRotationChanged(rotationX: Float, rotationY: Float)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var timestamp: Long = 0
    private var alpha = 0.95f // Default to Medium

    private var rotationX: Float = 0f
    private var rotationY: Float = 0f

    private var isRunning = false

    fun start(): Boolean {
        if (gyroscope == null) {
            return false
        }
        if (!isRunning) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
            isRunning = true
        }
        return true
    }

    fun stop() {
        if (isRunning) {
            sensorManager.unregisterListener(this)
            isRunning = false
            timestamp = 0
        }
    }

    fun recenter() {
        rotationX = 0f
        rotationY = 0f
    }

    // NEW: Allows MainActivity to change the filter strength
    fun setFilterAlpha(newAlpha: Float) {
        this.alpha = newAlpha
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_GYROSCOPE) return

        if (timestamp == 0L) {
            timestamp = event.timestamp
            return
        }

        val dt = (event.timestamp - timestamp) * NS2S
        timestamp = event.timestamp

        var axisX = event.values[0]
        var axisY = event.values[1]

        axisX = alpha * axisX + (1 - alpha) * rotationX
        axisY = alpha * axisY + (1 - alpha) * rotationY

        rotationX += axisX * dt
        rotationY += axisY * dt

        listener.onRotationChanged(rotationX, rotationY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    companion object {
        private const val NS2S = 1.0f / 1_000_000_000.0f
    }
}
