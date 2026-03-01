package de.codevoid.androtrack

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Captures lean angle and longitudinal acceleration from the device's IMU sensors.
 *
 * Uses TYPE_GAME_ROTATION_VECTOR (gyro+accel fusion, no magnetometer — immune to
 * motorcycle engine magnetic interference) for lean angle, and TYPE_LINEAR_ACCELERATION
 * (gravity removed) for acceleration/deceleration.
 *
 * On start, calibrates by recording the device orientation as "upright, stationary".
 * This zeroes out the arbitrary handlebar mount angle. An EMA low-pass filter with
 * ~2 Hz cutoff removes engine vibration noise (typically 30–200+ Hz).
 */
class BikeSensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Calibration state
    private var isCalibrated = false
    private var pendingCalibration = true
    private val refRotationMatrix = FloatArray(9)
    private val refRotationMatrixInv = FloatArray(9)
    private val forwardWorld = FloatArray(3)

    // Pre-allocated scratch arrays (avoid GC pressure in 50 Hz callbacks)
    private val liveRotationMatrix = FloatArray(9)
    private val relativeRotationMatrix = FloatArray(9)

    // EMA filter state
    private val cutoffHz = 2.0f
    private var lastRotationTimestampNs = 0L
    private var lastAccelTimestampNs = 0L
    private var filteredLean = 0f
    private var filteredAccel = 0f

    // Output values read by TrackingService GPS callback
    @Volatile var leanAngleDeg: Float = 0f
        private set
    @Volatile var longitudinalAccel: Float = 0f
        private set
    @Volatile var isActive: Boolean = false
        private set

    fun start() {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        }
        if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }
        isActive = rotationSensor != null
        pendingCalibration = true
        isCalibrated = false
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        isActive = false
        isCalibrated = false
        lastRotationTimestampNs = 0L
        lastAccelTimestampNs = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> handleRotation(event)
            Sensor.TYPE_LINEAR_ACCELERATION -> handleAcceleration(event)
        }
    }

    private fun handleRotation(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(liveRotationMatrix, event.values)

        if (pendingCalibration) {
            // Store reference rotation and its inverse (transpose for orthogonal matrix)
            liveRotationMatrix.copyInto(refRotationMatrix)
            transpose3x3(refRotationMatrix, refRotationMatrixInv)

            // Forward direction: device Y-axis projected to world horizontal plane
            val fx = refRotationMatrix[1]
            val fy = refRotationMatrix[4]
            val len = sqrt(fx * fx + fy * fy)
            if (len > 0.001f) {
                forwardWorld[0] = fx / len
                forwardWorld[1] = fy / len
                forwardWorld[2] = 0f
            } else {
                forwardWorld[0] = 1f; forwardWorld[1] = 0f; forwardWorld[2] = 0f
            }

            pendingCalibration = false
            isCalibrated = true
            filteredLean = 0f
            filteredAccel = 0f
            lastRotationTimestampNs = event.timestamp
            return
        }

        if (!isCalibrated) return

        // Relative rotation: R_rel = R_live * R_ref_inv
        multiply3x3(liveRotationMatrix, refRotationMatrixInv, relativeRotationMatrix)

        // Extract lean angle (roll around forward axis)
        val rawLean = atan2(
            relativeRotationMatrix[7].toDouble(),
            relativeRotationMatrix[8].toDouble()
        ).toFloat() * (180f / Math.PI.toFloat())

        // EMA low-pass filter
        val dtNs = event.timestamp - lastRotationTimestampNs
        if (dtNs > 0 && lastRotationTimestampNs > 0) {
            val dtSec = dtNs / 1_000_000_000f
            val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
            val alpha = dtSec / (rc + dtSec)
            filteredLean = alpha * rawLean + (1f - alpha) * filteredLean
        } else {
            filteredLean = rawLean
        }
        lastRotationTimestampNs = event.timestamp
        leanAngleDeg = filteredLean
    }

    private fun handleAcceleration(event: SensorEvent) {
        if (!isCalibrated) return

        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        // Rotate device-frame acceleration to world frame: a_world = R_live * a_device
        val awx = liveRotationMatrix[0]*ax + liveRotationMatrix[1]*ay + liveRotationMatrix[2]*az
        val awy = liveRotationMatrix[3]*ax + liveRotationMatrix[4]*ay + liveRotationMatrix[5]*az

        // Project onto bike forward direction (horizontal plane)
        val rawAccel = awx * forwardWorld[0] + awy * forwardWorld[1]

        // EMA low-pass filter
        val dtNs = event.timestamp - lastAccelTimestampNs
        if (dtNs > 0 && lastAccelTimestampNs > 0) {
            val dtSec = dtNs / 1_000_000_000f
            val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
            val alpha = dtSec / (rc + dtSec)
            filteredAccel = alpha * rawAccel + (1f - alpha) * filteredAccel
        } else {
            filteredAccel = rawAccel
        }
        lastAccelTimestampNs = event.timestamp
        longitudinalAccel = filteredAccel
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun transpose3x3(src: FloatArray, dst: FloatArray) {
        dst[0] = src[0]; dst[1] = src[3]; dst[2] = src[6]
        dst[3] = src[1]; dst[4] = src[4]; dst[5] = src[7]
        dst[6] = src[2]; dst[7] = src[5]; dst[8] = src[8]
    }

    private fun multiply3x3(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (row in 0..2) {
            for (col in 0..2) {
                out[row * 3 + col] =
                    a[row * 3 + 0] * b[0 * 3 + col] +
                    a[row * 3 + 1] * b[1 * 3 + col] +
                    a[row * 3 + 2] * b[2 * 3 + col]
            }
        }
    }
}
