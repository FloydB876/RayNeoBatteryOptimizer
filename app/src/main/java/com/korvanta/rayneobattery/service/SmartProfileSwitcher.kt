package com.korvanta.rayneobattery.service

import android.content.Context
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import com.korvanta.rayneobattery.profile.PowerProfileManager
import com.korvanta.rayneobattery.profile.PowerProfileManager.Profile

/**
 * Automatically switches power profiles based on what hardware is actively
 * being used. This prevents users from draining battery on the wrong profile.
 *
 * Detection logic:
 * - Camera open → Recording profile (dims display, kills radios)
 * - GPS active + BT connected → Navigation profile
 * - Audio recording active → keeps current (voice assistant, translation, etc.)
 * - Nothing special → Balanced or user's last manual choice
 *
 * The user can always override by manually selecting a profile.
 * Auto-switching pauses for 5 minutes after a manual override.
 */
class SmartProfileSwitcher(
    private val context: Context,
    private val profileManager: PowerProfileManager
) {

    private var autoSwitchEnabled = true
    private var lastManualOverrideTime = 0L
    private var lastAutoProfile: Profile? = null
    private var cameraCallback: CameraManager.AvailabilityCallback? = null

    // Track active hardware state
    private var isCameraActive = false
    private var isAudioRecording = false

    fun start() {
        registerCameraCallback()
    }

    fun stop() {
        unregisterCameraCallback()
    }

    /**
     * Call this when the user manually selects a profile.
     * Pauses auto-switching for MANUAL_OVERRIDE_COOLDOWN_MS.
     */
    fun notifyManualOverride() {
        lastManualOverrideTime = System.currentTimeMillis()
    }

    /**
     * Called periodically from BatteryMonitorService to evaluate
     * whether an auto-switch is appropriate.
     */
    fun evaluate(): AutoSwitchResult {
        // Respect manual override cooldown
        val timeSinceManual = System.currentTimeMillis() - lastManualOverrideTime
        if (timeSinceManual < MANUAL_OVERRIDE_COOLDOWN_MS) {
            return AutoSwitchResult(switched = false, reason = "Manual override active")
        }

        val suggestedProfile = detectOptimalProfile()

        // Only switch if the suggestion differs from current
        if (suggestedProfile != null && suggestedProfile != profileManager.currentProfile) {
            // Don't downgrade from Ultra Saver (that's an emergency state)
            if (profileManager.currentProfile == Profile.ULTRA_SAVER) {
                return AutoSwitchResult(switched = false, reason = "Ultra Saver locked")
            }

            lastAutoProfile = suggestedProfile
            profileManager.applyProfile(suggestedProfile)
            return AutoSwitchResult(
                switched = true,
                reason = "Auto: ${suggestedProfile.displayName}",
                newProfile = suggestedProfile
            )
        }

        return AutoSwitchResult(switched = false, reason = null)
    }

    private fun detectOptimalProfile(): Profile? {
        // Priority 1: Camera is active → Recording profile
        if (isCameraActive) {
            return Profile.RECORDING
        }

        // Priority 2: GPS is on + BT connected → likely navigating
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsActive = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (gpsActive) {
            return Profile.NAVIGATION
        }

        // Priority 3: Wi-Fi transferring data → keep Balanced for connectivity
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wm.isWifiEnabled && wm.connectionInfo?.networkId != -1) {
            return Profile.BALANCED
        }

        // Default: no specific hardware activity detected
        // Return null = don't auto-switch, let user's choice stand
        return null
    }

    private fun registerCameraCallback() {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraCallback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                // Camera opened by an app
                if (cameraId == "0") { // Main camera (Sony IMX681)
                    isCameraActive = true
                }
            }

            override fun onCameraAvailable(cameraId: String) {
                // Camera released
                if (cameraId == "0") {
                    isCameraActive = false
                }
            }
        }
        cm.registerAvailabilityCallback(cameraCallback!!, null)
    }

    private fun unregisterCameraCallback() {
        cameraCallback?.let {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.unregisterAvailabilityCallback(it)
        }
    }

    fun isCameraCurrentlyActive(): Boolean = isCameraActive

    data class AutoSwitchResult(
        val switched: Boolean,
        val reason: String?,
        val newProfile: Profile? = null
    )

    companion object {
        // Don't auto-switch for 5 minutes after user manually picks a profile
        const val MANUAL_OVERRIDE_COOLDOWN_MS = 5 * 60 * 1000L
    }
}
