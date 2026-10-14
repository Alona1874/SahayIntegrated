package com.example.pathfinderindoor.indoor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

/**
 * Pedestrian Dead Reckoning (PDR) Engine.
 * Combines step detection (hardware step detector with accelerometer fallback)
 * and orientation sensing (rotation vector with geomagnetic fallback).
 */
class PdrEngine(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val stepDetector: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private val rotationVector: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val magneticField: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val hasStepDetector: Boolean get() = stepDetector != null

    var totalSteps: Int = 0
        private set

    var currentHeadingDegrees: Float = 0f
        private set

    var onStep: ((totalSteps: Int) -> Unit)? = null
    var onHeadingChanged: ((degrees: Float) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Accelerometer fallback step detection state
    private var lastStepTimestampNs = 0L
    private var lastAccMagnitude = 9.8f
    private var isAccPeakCandidate = false
    private val stepThreshold = 11.5f
    private val minStepIntervalNs = 300_000_000L // 300ms minimum between steps

    // Magnetic fallback orientation
    private val lastAccelerometerValues = FloatArray(3)
    private val lastMagneticValues = FloatArray(3)
    private var hasAccelerometerData = false
    private var hasMagneticData = false

    private var isStarted = false

    fun start() {
        if (isStarted) return
        isStarted = true

        var stepSensorRegistered = false
        if (stepDetector != null) {
            try {
                stepSensorRegistered = sensorManager.registerListener(
                    this,
                    stepDetector,
                    SensorManager.SENSOR_DELAY_UI
                )
            } catch (_: SecurityException) {
                // If ACTIVITY_RECOGNITION is not granted or restricted on this device,
                // fallback to accelerometer
                stepSensorRegistered = false
            }
        }

        // If hardware step detector is unavailable or registration failed, use accelerometer
        if (!stepSensorRegistered && accelerometer != null) {
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        var rotationRegistered = false
        if (rotationVector != null) {
            rotationRegistered = sensorManager.registerListener(
                this,
                rotationVector,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        // If rotation vector is missing, fallback to accelerometer + magnetic sensor
        if (!rotationRegistered) {
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            magneticField?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    fun stop() {
        isStarted = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                totalSteps += 1
                dispatchStep(totalSteps)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAccelerometerValues, 0, 3)
                hasAccelerometerData = true

                // Accelerometer step detector fallback if hardware step detector is not present
                if (stepDetector == null) {
                    processAccelerometerStep(event.values, event.timestamp)
                }

                // If rotation vector is missing, compute orientation using accelerometer + magnetometer
                if (rotationVector == null && hasMagneticData) {
                    updateFallbackOrientation()
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMagneticValues, 0, 3)
                hasMagneticData = true
                if (rotationVector == null && hasAccelerometerData) {
                    updateFallbackOrientation()
                }
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val degrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                currentHeadingDegrees = (degrees + 360f) % 360f
                dispatchHeading(currentHeadingDegrees)
            }
        }
    }

    private fun processAccelerometerStep(values: FloatArray, timestampNs: Long) {
        val x = values[0]
        val y = values[1]
        val z = values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        if (magnitude > stepThreshold && lastAccMagnitude <= stepThreshold) {
            isAccPeakCandidate = true
        }

        if (isAccPeakCandidate && magnitude < lastAccMagnitude) {
            if (timestampNs - lastStepTimestampNs >= minStepIntervalNs) {
                lastStepTimestampNs = timestampNs
                totalSteps += 1
                dispatchStep(totalSteps)
            }
            isAccPeakCandidate = false
        }

        lastAccMagnitude = magnitude
    }

    private fun updateFallbackOrientation() {
        val rMatrix = FloatArray(9)
        val iMatrix = FloatArray(9)
        if (SensorManager.getRotationMatrix(rMatrix, iMatrix, lastAccelerometerValues, lastMagneticValues)) {
            val angles = FloatArray(3)
            SensorManager.getOrientation(rMatrix, angles)
            val degrees = Math.toDegrees(angles[0].toDouble()).toFloat()
            currentHeadingDegrees = (degrees + 360f) % 360f
            dispatchHeading(currentHeadingDegrees)
        }
    }

    private fun dispatchStep(steps: Int) {
        mainHandler.post {
            onStep?.invoke(steps)
        }
    }

    private fun dispatchHeading(heading: Float) {
        mainHandler.post {
            onHeadingChanged?.invoke(heading)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun angularDifference(from: Float, to: Float): Float {
        var diff = (to - from) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return diff
    }
}
