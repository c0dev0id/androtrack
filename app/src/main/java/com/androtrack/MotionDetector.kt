package com.androtrack

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class MotionDetector(
    context: Context,
    private val threshold: Float = 0.8f,
    private val onMotionDetected: () -> Unit,
    private val onMotionStopped: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var isMoving = false
    private var lastMotionTime = 0L
    private val stillnessThresholdMs = 5000L

    fun start() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)

        if (magnitude > threshold) {
            lastMotionTime = System.currentTimeMillis()
            if (!isMoving) {
                isMoving = true
                onMotionDetected()
            }
        } else {
            val now = System.currentTimeMillis()
            if (isMoving && (now - lastMotionTime) > stillnessThresholdMs) {
                isMoving = false
                onMotionStopped()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
