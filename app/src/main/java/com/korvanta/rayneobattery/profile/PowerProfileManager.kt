package com.korvanta.rayneobattery.profile

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PowerProfileManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("battery_optimizer", Context.MODE_PRIVATE)

    private val _currentProfileFlow = MutableStateFlow(loadSavedProfile())
    val currentProfileFlow: StateFlow<Profile> = _currentProfileFlow

    var currentProfile: Profile
        get() = _currentProfileFlow.value
        private set(value) { _currentProfileFlow.value = value }

    private fun loadSavedProfile(): Profile {
        val name = prefs.getString("active_profile", null) ?: return Profile.BALANCED
        return try {
            Profile.valueOf(name)
        } catch (e: IllegalArgumentException) {
            Profile.BALANCED
        }
    }

    fun applyProfile(profile: Profile) {
        currentProfile = profile
        prefs.edit().putString("active_profile", profile.name).apply()
        when (profile) {
            Profile.PERFORMANCE -> applyPerformance()
            Profile.BALANCED -> applyBalanced()
            Profile.POWER_SAVER -> applyPowerSaver()
            Profile.ULTRA_SAVER -> applyUltraSaver()
            Profile.NAVIGATION -> applyNavigation()
            Profile.RECORDING -> applyRecording()
        }
    }

    private fun applyPerformance() {
        setBrightness(180)
        setWifi(true)
        setBluetooth(true)
        setGps(true)
        setScreenTimeout(60_000)
    }

    private fun applyBalanced() {
        setBrightness(50)
        setWifi(true)
        setBluetooth(true)
        setGps(false)
        setScreenTimeout(30_000)
    }

    private fun applyPowerSaver() {
        setBrightness(20)
        setWifi(false)
        setBluetooth(true)
        setGps(false)
        setScreenTimeout(15_000)
    }

    private fun applyUltraSaver() {
        setBrightness(5)
        setWifi(false)
        setBluetooth(false)
        setGps(false)
        setScreenTimeout(10_000)
    }

    private fun applyNavigation() {
        setBrightness(30)
        setWifi(false)
        setBluetooth(true)
        setGps(false)
        setScreenTimeout(60_000)
    }

    private fun applyRecording() {
        setBrightness(15)
        setWifi(false)
        setBluetooth(false)
        setGps(false)
        setScreenTimeout(120_000)
    }

    // --- Hardware Controls ---
    // Using Settings.Global for Wi-Fi and Bluetooth because the legacy
    // WifiManager.setWifiEnabled() and BluetoothAdapter.enable()/disable()
    // are broken/deprecated on Android 12. Requires WRITE_SECURE_SETTINGS
    // granted via ADB.

    fun setBrightness(level: Int) {
        val clamped = level.coerceIn(1, 255)
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                clamped
            )
        } catch (e: SecurityException) {
            // WRITE_SETTINGS not granted
        }
    }

    fun setWifi(enabled: Boolean) {
        try {
            // Settings.Global.WIFI_ON works with WRITE_SECURE_SETTINGS on Android 12
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.WIFI_ON,
                if (enabled) 1 else 0
            )
        } catch (e: SecurityException) {
            // Fallback: try deprecated API (works on older Android / some OEM builds)
            try {
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = enabled
            } catch (e2: Exception) {
                // Neither method works
            }
        }
    }

    fun setBluetooth(enabled: Boolean) {
        try {
            // Settings.Global.BLUETOOTH_ON works with WRITE_SECURE_SETTINGS on Android 12
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.BLUETOOTH_ON,
                if (enabled) 1 else 0
            )
        } catch (e: SecurityException) {
            // Fallback: try deprecated API
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
                @Suppress("DEPRECATION")
                if (enabled) adapter.enable() else adapter.disable()
            } catch (e2: Exception) {
                // Neither method works
            }
        }
    }

    fun setGps(enabled: Boolean) {
        try {
            if (enabled) {
                Settings.Secure.putInt(
                    context.contentResolver,
                    @Suppress("DEPRECATION")
                    Settings.Secure.LOCATION_MODE,
                    @Suppress("DEPRECATION")
                    Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                )
            } else {
                Settings.Secure.putInt(
                    context.contentResolver,
                    @Suppress("DEPRECATION")
                    Settings.Secure.LOCATION_MODE,
                    @Suppress("DEPRECATION")
                    Settings.Secure.LOCATION_MODE_OFF
                )
            }
        } catch (e: SecurityException) {
            // WRITE_SECURE_SETTINGS not granted
        }
    }

    fun isGpsEnabled(): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    fun setScreenTimeout(millis: Int) {
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                millis
            )
        } catch (e: SecurityException) {
            // WRITE_SETTINGS not granted
        }
    }

    fun getCurrentBrightness(): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (e: Settings.SettingNotFoundException) {
            128
        }
    }

    fun isWifiEnabled(): Boolean {
        return try {
            // Read from Settings.Global for consistency with our toggle method
            val value = Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0)
            value != 0
        } catch (e: Exception) {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled
        }
    }

    fun isBluetoothEnabled(): Boolean {
        return BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false
    }

    enum class Profile(val displayName: String, val description: String) {
        PERFORMANCE("Performance", "Full brightness, all radios + GPS on"),
        BALANCED("Balanced", "Moderate brightness, GPS off"),
        POWER_SAVER("Power Saver", "Low brightness, Wi-Fi + GPS off"),
        ULTRA_SAVER("Ultra Saver", "Minimum everything, all radios off"),
        NAVIGATION("Navigation", "GPS off, uses phone GPS via BT"),
        RECORDING("Recording", "Camera optimized, radios off")
    }
}
