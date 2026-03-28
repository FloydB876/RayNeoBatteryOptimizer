package com.korvanta.rayneobattery.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects when the user's head is idle (no significant movement) using
 * the Game Rotation Vector sensor. When idle is detected, the callback
 * fires so the service can auto-dim the display to save power.
 *
 * On the X3 Pro, keeping the MicroLED displays at any brightness while
 * the user isn't looking at them is pure waste. This catches scenarios like:
 * - Glasses pushed up on forehead while talking
 * - User looking at their phone instead of the HUD
 * - Glasses sitting on a table still powered on
 *
 * Uses Game Rotation Vector (no magnetometer) per RayNeo's recommendation
 * to avoid magnetic interference and save the magnetometer's power draw.
 */
class IdleDetector(
    private val context: Context,
    private val onIdleStateChanged: (isIdle: Boolean) -> Unit,
    private val onHeadTiltUp: (() -> Unit)? = null
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    // Previous quaternion for comparison
    private var prevX = 0f
    private var prevY = 0f
    private var prevZ = 0f
    private var prevW = 1f
    private var hasPrevious = false

    // Idle tracking
    private var stillFrames = 0
    private var isCurrentlyIdle = false
    private var lastMovementTime = System.currentTimeMillis()

    // Head tilt tracking — pitch angle from rotation matrix
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastTiltToggleTime = 0L

    fun start() {
        rotationSensor?.let {
            // SENSOR_DELAY_UI = ~60ms interval, good balance of responsiveness vs power
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val w = if (event.values.size > 3) event.values[3] else 1f

        if (!hasPrevious) {
            prevX = x; prevY = y; prevZ = z; prevW = w
            hasPrevious = true
            return
        }

        // Calculate angular difference between current and previous quaternion
        // Using dot product: cos(angle) = |q1 · q2|
        val dot = abs(prevX * x + prevY * y + prevZ * z + prevW * w).coerceIn(0f, 1f)
        val angleDeg = Math.toDegrees(2.0 * Math.acos(dot.toDouble())).toFloat()

        if (angleDeg > MOVEMENT_THRESHOLD_DEG) {
            // Significant head movement detected
            stillFrames = 0
            lastMovementTime = System.currentTimeMillis()
            if (isCurrentlyIdle) {
                isCurrentlyIdle = false
                onIdleStateChanged(false)
            }
        } else {
            stillFrames++
        }

        // Check if idle timeout has been reached
        val timeSinceMovement = System.currentTimeMillis() - lastMovementTime
        if (!isCurrentlyIdle && timeSinceMovement > IDLE_TIMEOUT_MS) {
            isCurrentlyIdle = true
            onIdleStateChanged(true)
        }

        prevX = x; prevY = y; prevZ = z; prevW = w

        // Head tilt detection: convert quaternion to rotation matrix then extract pitch
        if (onHeadTiltUp != null) {
            val quat = floatArrayOf(x, y, z, w)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, quat)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()

            // Pitch > 25° means head is tilted significantly upward
            val now = System.currentTimeMillis()
            if (pitchDeg > TILT_UP_THRESHOLD_DEG && (now - lastTiltToggleTime) > TILT_COOLDOWN_MS) {
                lastTiltToggleTime = now
                onHeadTiltUp.invoke()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun isIdle(): Boolean = isCurrentlyIdle

    companion object {
        // Head must rotate more than 1.5° between samples to count as movement
        const val MOVEMENT_THRESHOLD_DEG = 1.5f

        // 30 seconds of no movement = idle
        const val IDLE_TIMEOUT_MS = 30_000L

        // Head tilt up threshold (degrees) to toggle HUD
        const val TILT_UP_THRESHOLD_DEG = 25f

        // Cooldown to prevent rapid toggling (2 seconds)
        const val TILT_COOLDOWN_MS = 2000L
    }
}
