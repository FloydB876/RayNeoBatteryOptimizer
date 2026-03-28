package com.korvanta.rayneobattery.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.korvanta.rayneobattery.BatteryOptimizerApp
import com.korvanta.rayneobattery.R
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.korvanta.rayneobattery.profile.PowerProfileManager
import com.korvanta.rayneobattery.ui.HudOverlayManager
import com.korvanta.rayneobattery.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BatteryMonitorService : LifecycleService() {

    private lateinit var profileManager: PowerProfileManager
    private lateinit var hudManager: HudOverlayManager
    private lateinit var idleDetector: IdleDetector
    private lateinit var smartSwitcher: SmartProfileSwitcher
    private lateinit var chargeGuardian: ChargeGuardian
    private lateinit var powerHistory: PowerHistoryTracker
    private lateinit var darkEnforcer: DarkModeEnforcer
    private val mainHandler = Handler(Looper.getMainLooper())

    // Track whether we intentionally turned GPS off
    private var gpsWasKilledByUs = false
    private var brightnessBeforeIdle = -1

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateBatteryInfo(intent)
        }
    }

    // Listens for any app or system service re-enabling GPS behind our back
    private val gpsWatchdogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                checkGpsReactivation()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        profileManager = PowerProfileManager(this)
        hudManager = HudOverlayManager(this)
        chargeGuardian = ChargeGuardian()
        powerHistory = PowerHistoryTracker(this)
        darkEnforcer = DarkModeEnforcer(this)

        // Smart profile switcher — auto-detects camera, GPS, etc.
        smartSwitcher = SmartProfileSwitcher(this, profileManager)
        smartSwitcher.start()

        // IMU idle detector — dims display when head is still
        // Also detects head-tilt-up to toggle HUD visibility
        idleDetector = IdleDetector(
            context = this,
            onIdleStateChanged = { isIdle ->
                mainHandler.post { handleIdleStateChanged(isIdle) }
            },
            onHeadTiltUp = {
                mainHandler.post { hudManager.toggle() }
            }
        )
        idleDetector.start()

        startForeground(NOTIFICATION_ID, createNotification(100, false))
        registerBatteryReceiver()
        registerGpsWatchdog()
        startPeriodicMonitoring()

        // Don't re-apply profile on service start — preserve manual quick control overrides.
        // The saved profile name is restored but hardware settings stay as-is.

        // Enforce dark mode system-wide to save MicroLED power
        darkEnforcer.enforce()

        // Start power history session
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        powerHistory.startSession(pct)

        // Show HUD based on saved preference (default: enabled)
        if (Settings.canDrawOverlays(this)) {
            mainHandler.postDelayed({ hudManager.showIfEnabled() }, 1000)
        }
    }

    private fun showTimedAlert(message: String, durationMs: Long = 8000) {
        _activeAlert.value = message
        mainHandler.post { hudManager.updateAlert(message) }
        mainHandler.postDelayed({
            if (_activeAlert.value == message) {
                _activeAlert.value = null
                hudManager.updateAlert(null)
            }
        }, durationMs)
    }

    private fun handleIdleStateChanged(isIdle: Boolean) {
        if (isIdle) {
            // Save current brightness and dim to minimum
            brightnessBeforeIdle = profileManager.getCurrentBrightness()
            profileManager.setBrightness(1) // Near-off — saves max MicroLED power
            _activeAlert.value = "Idle detected \u2014 display dimmed"
            hudManager.updateAlert("IDLE \u2014 dimmed")
            hudManager.updateIdleStatus(true)
            _isIdle.value = true
        } else {
            // Restore brightness
            if (brightnessBeforeIdle > 0) {
                profileManager.setBrightness(brightnessBeforeIdle)
                brightnessBeforeIdle = -1
            }
            _activeAlert.value = null
            hudManager.updateAlert(null)
            hudManager.updateIdleStatus(false)
            _isIdle.value = false
        }
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)
    }

    private fun registerGpsWatchdog() {
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        registerReceiver(gpsWatchdogReceiver, filter)
    }

    /**
     * Checks if GPS was re-enabled by another app or system service after
     * we intentionally killed it. If so, alerts the user and optionally
     * kills it again based on the active profile.
     */
    private fun checkGpsReactivation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsNowEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)

        if (gpsNowEnabled && gpsWasKilledByUs) {
            // Something re-enabled GPS behind our back
            _gpsReactivatedBy.value = detectGpsRequestingApps()

            val profile = profileManager.currentProfile
            val profileWantsGpsOff = profile != PowerProfileManager.Profile.PERFORMANCE

            if (profileWantsGpsOff) {
                // Auto-kill it again
                profileManager.setGps(false)
                _activeAlert.value = "GPS was re-enabled by another app \u2014 killed it again"
                mainHandler.post { hudManager.updateAlert("GPS LEAK \u2014 auto-killed") }
            } else {
                _activeAlert.value = "GPS was re-enabled by another app"
                mainHandler.post { hudManager.updateAlert("GPS re-enabled externally") }
            }
        }

        // Track our intent: if profile says GPS off, mark that we killed it
        if (!gpsNowEnabled) {
            val profileWantsGpsOff = profileManager.currentProfile != PowerProfileManager.Profile.PERFORMANCE
            if (profileWantsGpsOff) {
                gpsWasKilledByUs = true
            }
        } else {
            gpsWasKilledByUs = false
        }
    }

    /**
     * Attempts to identify which apps are actively requesting GPS location.
     * Uses LocationManager to check for active GPS providers.
     */
    private fun detectGpsRequestingApps(): String {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // Check which providers are active
            val activeProviders = lm.getProviders(true)
            val gpsActive = activeProviders.contains(LocationManager.GPS_PROVIDER)
            val networkActive = activeProviders.contains(LocationManager.NETWORK_PROVIDER)

            buildString {
                if (gpsActive) append("GPS provider active")
                if (networkActive) {
                    if (isNotEmpty()) append(", ")
                    append("Network provider active")
                }
                if (isEmpty()) append("Unknown source")
            }
        } catch (e: Exception) {
            "Detection unavailable"
        }
    }

    private fun updateBatteryInfo(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)

        // ACTION_POWER_CONNECTED/DISCONNECTED may not include battery extras.
        // Only process if we have valid level data.
        if (level < 0 || scale <= 0) return

        val pct = (level * 100) / scale

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f

        val info = BatteryInfo(
            percentage = pct,
            isCharging = isCharging,
            temperature = temperature,
            voltage = voltage,
            health = getBatteryHealthString(
                intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            )
        )

        _batteryInfo.value = info
        updateNotification(pct, isCharging)

        // Push to HUD overlay
        mainHandler.post {
            hudManager.updateBattery(info)
            hudManager.updateProfile(profileManager.currentProfile)
            hudManager.updateRadios(
                wifiOn = profileManager.isWifiEnabled(),
                btOn = profileManager.isBluetoothEnabled(),
                gpsOn = profileManager.isGpsEnabled(),
                gpsLeak = profileManager.isGpsEnabled() &&
                    profileManager.currentProfile != PowerProfileManager.Profile.PERFORMANCE
            )
        }

        // Auto-apply power saving when battery is critically low
        if (pct <= CRITICAL_THRESHOLD && !isCharging) {
            profileManager.applyProfile(PowerProfileManager.Profile.ULTRA_SAVER)
            gpsWasKilledByUs = true
            showTimedAlert("Critical battery! Ultra Saver activated.")
        } else if (pct <= LOW_THRESHOLD && !isCharging) {
            if (profileManager.currentProfile != PowerProfileManager.Profile.ULTRA_SAVER) {
                profileManager.applyProfile(PowerProfileManager.Profile.POWER_SAVER)
                gpsWasKilledByUs = true
                showTimedAlert("Low battery. Power Saver activated.")
            }
        }

        // Thermal warning
        if (temperature > THERMAL_WARNING_TEMP) {
            showTimedAlert("High temperature: ${temperature}\u00B0C \u2014 reducing load")
            profileManager.applyProfile(PowerProfileManager.Profile.ULTRA_SAVER)
            gpsWasKilledByUs = true
        }

        // Charge guardian — battery longevity alerts
        val chargeAlert = chargeGuardian.evaluate(info)
        if (chargeAlert != null) {
            _activeAlert.value = chargeAlert.message
            mainHandler.post { hudManager.updateAlert(chargeAlert.message) }
            // Auto-dismiss non-critical alerts after 8 seconds
            if (chargeAlert.severity != ChargeGuardian.Severity.CRITICAL) {
                mainHandler.postDelayed({
                    if (_activeAlert.value == chargeAlert.message) {
                        _activeAlert.value = null
                        hudManager.updateAlert(null)
                    }
                }, 8000)
            }
        } else {
            // Clear stale alerts when charge guardian reports all clear
            val currentAlert = _activeAlert.value
            if (currentAlert != null && !currentAlert.startsWith("GPS")) {
                _activeAlert.value = null
                mainHandler.post { hudManager.updateAlert(null) }
            }
        }
    }

    private fun startPeriodicMonitoring() {
        lifecycleScope.launch {
            while (true) {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val currentNowRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                val energyCounter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)

                // Auto-detect units: if |value| > 10000, it's likely µA; otherwise mA.
                // Normalize everything to µA for consistent calculations.
                val currentNowUa = if (kotlin.math.abs(currentNowRaw) > 10_000) {
                    currentNowRaw // Already in µA
                } else {
                    currentNowRaw * 1000 // Convert mA to µA
                }

                _currentDraw.value = currentNowUa
                _energyCounter.value = energyCounter

                val remaining = _batteryInfo.value
                if (remaining != null && !remaining.isCharging && currentNowUa < 0) {
                    val capacityUah = 245_000L // 245 mAh in µAh
                    val remainingUah = (capacityUah * remaining.percentage) / 100
                    val drawRateUa = -currentNowUa.toLong()
                    // Minimum 1 mA (1000 µA) draw to avoid crazy estimates
                    if (drawRateUa >= 1000) {
                        val minutesLeft = ((remainingUah * 60) / drawRateUa).toInt()
                        // Cap at 24 hours max — 245mAh can't last longer
                        _estimatedMinutesLeft.value = minutesLeft.coerceAtMost(24 * 60)
                    } else {
                        _estimatedMinutesLeft.value = 0
                    }
                } else {
                    // Charging or no data — clear the estimate
                    _estimatedMinutesLeft.value = 0
                }

                // Push current draw + time to HUD
                mainHandler.post {
                    hudManager.updateCurrentDraw(currentNowUa)
                    hudManager.updateTimeRemaining(_estimatedMinutesLeft.value)
                }

                // Periodic GPS watchdog check — catch cases where the broadcast
                // was missed (e.g. if a rogue app keeps re-requesting location)
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val gpsOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                _gpsStatus.value = gpsOn
                if (gpsOn && gpsWasKilledByUs) {
                    checkGpsReactivation()
                }

                // Update HUD radio status every cycle
                mainHandler.post {
                    hudManager.updateRadios(
                        wifiOn = profileManager.isWifiEnabled(),
                        btOn = profileManager.isBluetoothEnabled(),
                        gpsOn = gpsOn,
                        gpsLeak = gpsOn && gpsWasKilledByUs
                    )
                }

                // Smart auto-profile switching (camera active → Recording, etc.)
                val switchResult = smartSwitcher.evaluate()
                if (switchResult.switched) {
                    _activeAlert.value = switchResult.reason
                    mainHandler.post {
                        switchResult.newProfile?.let { hudManager.updateProfile(it) }
                        hudManager.updateAlert(switchResult.reason)
                    }
                }

                // Record sample for power history
                val temp = _batteryInfo.value?.temperature ?: 0f
                powerHistory.recordSample(
                    currentDrawUa = currentNowUa,
                    temperature = temp,
                    profileName = profileManager.currentProfile.displayName
                )

                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    private fun createNotification(pct: Int, isCharging: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val status = if (isCharging) "Charging" else "Discharging"
        val profile = profileManager.currentProfile.displayName
        val gpsWarning = if (_gpsStatus.value && gpsWasKilledByUs) " | GPS LEAK!" else ""

        return NotificationCompat.Builder(this, BatteryOptimizerApp.CHANNEL_ID)
            .setContentTitle("Battery: $pct% \u2014 $status$gpsWarning")
            .setContentText("Profile: $profile")
            .setSmallIcon(R.drawable.ic_battery)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(pct: Int, isCharging: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification(pct, isCharging))
    }

    private fun getBatteryHealthString(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unknown"
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    fun toggleHud() {
        if (Settings.canDrawOverlays(this)) {
            mainHandler.post { hudManager.toggle() }
        }
    }

    fun showHud() {
        if (Settings.canDrawOverlays(this) && !hudManager.isVisible()) {
            mainHandler.post { hudManager.showIfEnabled() }
        }
    }

    fun isHudVisible(): Boolean = hudManager.isVisible()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Handle HUD actions from activity
        when (intent?.action) {
            ACTION_TOGGLE_HUD -> toggleHud()
            ACTION_SHOW_HUD -> showHud()
        }

        // Always try to restore HUD if enabled and not showing
        // (covers START_STICKY restart and AlarmManager restart cases)
        if (Settings.canDrawOverlays(this) && hudManager.isEnabled && !hudManager.isVisible()) {
            mainHandler.postDelayed({ hudManager.showIfEnabled() }, 500)
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // User swiped app away — the service is a foreground service so it
        // should NOT be killed. But some OEMs (including Mercury OS) may kill it.
        // Schedule immediate restart as insurance.
        val restartIntent = Intent(applicationContext, BatteryMonitorService::class.java)
        restartIntent.action = ACTION_SHOW_HUD
        try {
            startForegroundService(restartIntent)
        } catch (e: Exception) {
            // If immediate restart fails, use AlarmManager as fallback
            val pi = PendingIntent.getService(
                applicationContext, 1, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 500, pi)
        }
    }

    override fun onDestroy() {
        // End power history session
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val summary = powerHistory.endSession(pct)
        _lastSessionSummary.value = summary

        // Clean up sensors and receivers
        idleDetector.stop()
        smartSwitcher.stop()
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(gpsWatchdogReceiver) } catch (e: Exception) {}

        // Only hide HUD if user explicitly disabled it.
        // If enabled, leave it — the system will clean up the overlay when the
        // process dies, and START_STICKY will restart the service to re-create it.
        if (!hudManager.isEnabled) {
            hudManager.hide()
        }

        // Schedule self-restart to bring HUD back if process dies
        if (hudManager.isEnabled) {
            val restartIntent = Intent(applicationContext, BatteryMonitorService::class.java)
            restartIntent.action = ACTION_SHOW_HUD
            val pi = PendingIntent.getService(
                applicationContext, 2, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 1000, pi)
        }

        super.onDestroy()
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): BatteryMonitorService = this@BatteryMonitorService
    }

    private val binder = LocalBinder()

    companion object {
        const val NOTIFICATION_ID = 1001
        const val MONITOR_INTERVAL_MS = 5000L
        const val LOW_THRESHOLD = 20
        const val CRITICAL_THRESHOLD = 10
        const val THERMAL_WARNING_TEMP = 42f
        const val ACTION_TOGGLE_HUD = "com.korvanta.rayneobattery.TOGGLE_HUD"
        const val ACTION_SHOW_HUD = "com.korvanta.rayneobattery.SHOW_HUD"

        private val _batteryInfo = MutableStateFlow<BatteryInfo?>(null)
        val batteryInfo: StateFlow<BatteryInfo?> = _batteryInfo

        private val _currentDraw = MutableStateFlow(0)
        val currentDraw: StateFlow<Int> = _currentDraw

        private val _energyCounter = MutableStateFlow(0L)
        val energyCounter: StateFlow<Long> = _energyCounter

        private val _estimatedMinutesLeft = MutableStateFlow(0)
        val estimatedMinutesLeft: StateFlow<Int> = _estimatedMinutesLeft

        private val _activeAlert = MutableStateFlow<String?>(null)
        val activeAlert: StateFlow<String?> = _activeAlert

        // GPS watchdog state
        private val _gpsStatus = MutableStateFlow(false)
        val gpsStatus: StateFlow<Boolean> = _gpsStatus

        private val _gpsReactivatedBy = MutableStateFlow<String?>(null)
        val gpsReactivatedBy: StateFlow<String?> = _gpsReactivatedBy

        // Idle state
        private val _isIdle = MutableStateFlow(false)
        val isIdle: StateFlow<Boolean> = _isIdle

        // Power history
        private val _lastSessionSummary = MutableStateFlow<PowerHistoryTracker.SessionSummary?>(null)
        val lastSessionSummary: StateFlow<PowerHistoryTracker.SessionSummary?> = _lastSessionSummary
    }
}

data class BatteryInfo(
    val percentage: Int,
    val isCharging: Boolean,
    val temperature: Float,
    val voltage: Float,
    val health: String
)
