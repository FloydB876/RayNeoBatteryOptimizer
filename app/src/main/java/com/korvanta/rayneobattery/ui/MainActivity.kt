package com.korvanta.rayneobattery.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.ui.toast.FToast
import com.ffalcon.mercury.android.sdk.ui.util.FixPosFocusTracker
import com.ffalcon.mercury.android.sdk.ui.util.FocusHolder
import com.ffalcon.mercury.android.sdk.ui.util.FocusInfo
import com.ffalcon.mercury.android.sdk.core.ViewPair
import com.ffalcon.mercury.android.sdk.ui.util.RecyclerViewFocusTracker
import com.korvanta.rayneobattery.R
import com.korvanta.rayneobattery.databinding.ActivityMainBinding
import com.korvanta.rayneobattery.profile.PowerProfileManager
import com.korvanta.rayneobattery.service.BatteryMonitorService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {

    private lateinit var profileManager: PowerProfileManager
    private lateinit var profileAdapter: ProfileAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    // Focus management (SDK pattern)
    private val topFocusHolder = FocusHolder(true)
    private var topFocusTracker: FixPosFocusTracker? = null

    // Quick controls sub-focus
    private val quickFocusHolder = FocusHolder(true)
    private var quickFocusTracker: FixPosFocusTracker? = null

    // Profile list focus
    private var profileFocusTracker: RecyclerViewFocusTracker? = null

    // Track which section is active for visual feedback
    private var quickControlsActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileManager = PowerProfileManager(this)
        checkPermissions()
        setupUI()
        setupFocusManagement()
        setupEventHandling()
        startMonitorService()
        observeBatteryData()
    }

    private fun setupUI() {
        profileAdapter = ProfileAdapter { profile ->
            profileManager.applyProfile(profile)
            profileAdapter.activeProfile = profile
            updateQuickControlsDelayed()
            FToast.show("${profile.displayName} activated")
        }

        mBindingPair.updateView {
            rvProfiles.layoutManager = LinearLayoutManager(this@MainActivity)
            rvProfiles.adapter = profileAdapter
        }

        updateQuickControlsImmediate()
    }

    private fun setupFocusManagement() {
        mBindingPair.setLeft {
            val wifiFocus = FocusInfo(
                btnWifi,
                eventHandler = { action ->
                    when (action) {
                        is TempleAction.Click -> {
                            val willEnable = !profileManager.isWifiEnabled()
                            profileManager.setWifi(willEnable)
                            // Update text immediately with intended state
                            mBindingPair.updateView {
                                btnWifi.text = "Wi-Fi: ${if (willEnable) "ON" else "OFF"}"
                            }
                            FToast.show("Wi-Fi ${if (willEnable) "ON" else "OFF"}")
                            updateQuickControlsDelayed()
                        }
                        else -> Unit
                    }
                },
                focusChangeHandler = { hasFocus ->
                    mBindingPair.updateView {
                        btnWifi.isSelected = hasFocus
                        btnWifi.setTextColor(resources.getColor(
                            if (hasFocus) R.color.focus_ring else R.color.text_primary, theme
                        ))
                    }
                }
            )

            val btFocus = FocusInfo(
                btnBluetooth,
                eventHandler = { action ->
                    when (action) {
                        is TempleAction.Click -> {
                            val willEnable = !profileManager.isBluetoothEnabled()
                            profileManager.setBluetooth(willEnable)
                            mBindingPair.updateView {
                                btnBluetooth.text = "BT: ${if (willEnable) "ON" else "OFF"}"
                            }
                            FToast.show("Bluetooth ${if (willEnable) "ON" else "OFF"}")
                            updateQuickControlsDelayed()
                        }
                        else -> Unit
                    }
                },
                focusChangeHandler = { hasFocus ->
                    mBindingPair.updateView {
                        btnBluetooth.isSelected = hasFocus
                        btnBluetooth.setTextColor(resources.getColor(
                            if (hasFocus) R.color.focus_ring else R.color.text_primary, theme
                        ))
                    }
                }
            )

            val brightFocus = FocusInfo(
                btnBrightness,
                eventHandler = { action ->
                    when (action) {
                        is TempleAction.Click -> {
                            val levels = listOf(5, 20, 50, 100, 180)
                            val current = profileManager.getCurrentBrightness()
                            val next = levels.firstOrNull { it > current } ?: levels.first()
                            profileManager.setBrightness(next)
                            mBindingPair.updateView {
                                btnBrightness.text = "Bright: $next"
                            }
                            FToast.show("Brightness: $next")
                        }
                        is TempleAction.SlideUpwards -> {
                            val current = profileManager.getCurrentBrightness()
                            val next = (current + 15).coerceAtMost(255)
                            profileManager.setBrightness(next)
                            mBindingPair.updateView {
                                btnBrightness.text = "Bright: $next"
                            }
                        }
                        is TempleAction.SlideDownwards -> {
                            val current = profileManager.getCurrentBrightness()
                            val next = (current - 15).coerceAtLeast(1)
                            profileManager.setBrightness(next)
                            mBindingPair.updateView {
                                btnBrightness.text = "Bright: $next"
                            }
                        }
                        else -> Unit
                    }
                },
                focusChangeHandler = { hasFocus ->
                    mBindingPair.updateView {
                        btnBrightness.isSelected = hasFocus
                        btnBrightness.setTextColor(resources.getColor(
                            if (hasFocus) R.color.focus_ring else R.color.text_primary, theme
                        ))
                    }
                }
            )

            val gpsFocus = FocusInfo(
                btnGps,
                eventHandler = { action ->
                    when (action) {
                        is TempleAction.Click -> {
                            val willEnable = !profileManager.isGpsEnabled()
                            profileManager.setGps(willEnable)
                            mBindingPair.updateView {
                                btnGps.text = "GPS: ${if (willEnable) "ON" else "OFF"}"
                            }
                            FToast.show("GPS ${if (willEnable) "ON" else "OFF"}")
                            updateQuickControlsDelayed()
                        }
                        else -> Unit
                    }
                },
                focusChangeHandler = { hasFocus ->
                    mBindingPair.updateView {
                        btnGps.isSelected = hasFocus
                        btnGps.setTextColor(resources.getColor(
                            if (hasFocus) R.color.focus_ring else R.color.text_primary, theme
                        ))
                    }
                }
            )

            val hudFocus = FocusInfo(
                btnHud,
                eventHandler = { action ->
                    when (action) {
                        is TempleAction.Click -> {
                            val hudIntent = Intent(this@MainActivity, BatteryMonitorService::class.java)
                            hudIntent.action = BatteryMonitorService.ACTION_TOGGLE_HUD
                            startService(hudIntent)
                            // Update button text after toggle
                            mainHandler.postDelayed({ updateHudButtonText() }, 200)
                        }
                        else -> Unit
                    }
                },
                focusChangeHandler = { hasFocus ->
                    mBindingPair.updateView {
                        btnHud.isSelected = hasFocus
                        btnHud.setTextColor(resources.getColor(
                            if (hasFocus) R.color.focus_ring else R.color.text_primary, theme
                        ))
                    }
                }
            )

            quickFocusHolder.addFocusTarget(wifiFocus, btFocus, brightFocus, gpsFocus, hudFocus)
            quickFocusHolder.currentFocus(mBindingPair.left.btnWifi)
        }

        quickFocusTracker = FixPosFocusTracker(quickFocusHolder, isVertical = false)

        val rvPair = ViewPair(mBindingPair.left.rvProfiles, mBindingPair.right.rvProfiles)
        profileFocusTracker = RecyclerViewFocusTracker(rvPair)

        topFocusTracker = FixPosFocusTracker(topFocusHolder, isVertical = true).apply {
            focusObj.hasFocus = true
        }

        // Start with profile list focused
        quickFocusTracker?.focusObj?.hasFocus = false
        profileFocusTracker?.focusObj?.hasFocus = true
        quickControlsActive = false

        // Set initial profile focus highlight
        profileAdapter.focusedPosition = 0
    }

    private fun setupEventHandling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    when (action) {
                        is TempleAction.DoubleClick -> {
                            // Double-tap: toggle between quick controls and profile list
                            if (quickControlsActive) {
                                switchToProfileList()
                            } else {
                                switchToQuickControls()
                            }
                        }
                        is TempleAction.TripleClick -> {
                            finish()
                        }
                        is TempleAction.LongClick -> {
                            profileManager.applyProfile(PowerProfileManager.Profile.ULTRA_SAVER)
                            profileAdapter.activeProfile = PowerProfileManager.Profile.ULTRA_SAVER
                            updateQuickControlsDelayed()
                            FToast.show("Ultra Saver activated!")
                        }
                        is TempleAction.DoubleFingerClick -> {
                            val hudIntent = Intent(this@MainActivity, BatteryMonitorService::class.java)
                            hudIntent.action = BatteryMonitorService.ACTION_TOGGLE_HUD
                            startService(hudIntent)
                            FToast.show("HUD toggled")
                        }
                        else -> {
                            if (quickControlsActive) {
                                quickFocusTracker?.handleFocusTargetEvent(action)
                            } else {
                                profileFocusTracker?.handleActionEvent(action) { profileAction ->
                                    when (profileAction) {
                                        is TempleAction.Click -> {
                                            val position = profileFocusTracker?.currentSelectPos ?: 0
                                            val profile = PowerProfileManager.Profile.values()[position]
                                            profileManager.applyProfile(profile)
                                            profileAdapter.activeProfile = profile
                                            updateQuickControlsDelayed()
                                            FToast.show("${profile.displayName} activated")
                                        }
                                        else -> {}
                                    }
                                }
                                // Update focus highlight after every navigation action
                                val newPos = profileFocusTracker?.currentSelectPos ?: 0
                                if (newPos != profileAdapter.focusedPosition) {
                                    profileAdapter.focusedPosition = newPos
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun switchToQuickControls() {
        quickControlsActive = true
        profileFocusTracker?.focusObj?.hasFocus = false
        quickFocusTracker?.focusObj?.hasFocus = true

        // Clear profile focus visuals
        profileAdapter.focusedPosition = -1

        // Show section indicator
        FToast.show("Quick Controls")
    }

    private fun switchToProfileList() {
        quickControlsActive = false
        quickFocusTracker?.focusObj?.hasFocus = false
        profileFocusTracker?.focusObj?.hasFocus = true

        // Clear quick control visuals
        mBindingPair.updateView {
            btnWifi.isSelected = false
            btnBluetooth.isSelected = false
            btnBrightness.isSelected = false
            btnGps.isSelected = false
            btnHud.isSelected = false
            btnWifi.setTextColor(resources.getColor(R.color.text_primary, theme))
            btnBluetooth.setTextColor(resources.getColor(R.color.text_primary, theme))
            btnBrightness.setTextColor(resources.getColor(R.color.text_primary, theme))
            btnGps.setTextColor(resources.getColor(R.color.text_primary, theme))
            btnHud.setTextColor(resources.getColor(R.color.text_primary, theme))
        }

        // Restore profile focus highlight
        profileAdapter.focusedPosition = profileFocusTracker?.currentSelectPos ?: 0

        FToast.show("Profiles")
    }

    private fun updateQuickControlsImmediate() {
        mBindingPair.updateView {
            val wifiOn = profileManager.isWifiEnabled()
            val btOn = profileManager.isBluetoothEnabled()
            val brightness = profileManager.getCurrentBrightness()
            val gpsOn = profileManager.isGpsEnabled()

            btnWifi.text = "Wi-Fi: ${if (wifiOn) "ON" else "OFF"}"
            btnBluetooth.text = "BT: ${if (btOn) "ON" else "OFF"}"
            btnBrightness.text = "Bright: $brightness"
            btnGps.text = "GPS: ${if (gpsOn) "ON" else "OFF"}"
            tvActiveProfile.text = "Profile: ${profileManager.currentProfile.displayName}"
        }
        updateHudButtonText()
    }

    private fun updateHudButtonText() {
        val prefs = getSharedPreferences("battery_optimizer", MODE_PRIVATE)
        val hudEnabled = prefs.getBoolean("hud_enabled", true)
        mBindingPair.updateView {
            btnHud.text = "HUD: ${if (hudEnabled) "ON" else "OFF"}"
        }
    }

    /**
     * Schedule a delayed refresh to pick up async system state changes
     * (Wi-Fi/BT toggles take a moment to propagate).
     */
    private fun updateQuickControlsDelayed() {
        mainHandler.postDelayed({ updateQuickControlsImmediate() }, 500)
    }

    private fun startMonitorService() {
        val intent = Intent(this, BatteryMonitorService::class.java)
        startForegroundService(intent)
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions and try to show HUD if now granted
        checkPermissions()
        if (Settings.canDrawOverlays(this)) {
            val hudIntent = Intent(this, BatteryMonitorService::class.java)
            hudIntent.action = BatteryMonitorService.ACTION_SHOW_HUD
            startService(hudIntent)
        }
        updateQuickControlsImmediate()
    }

    private fun observeBatteryData() {
        lifecycleScope.launch {
            BatteryMonitorService.batteryInfo.filterNotNull().collectLatest { info ->
                mBindingPair.updateView {
                    tvBatteryPct.text = "${info.percentage}%"
                    tvBatteryStatus.text = if (info.isCharging) "Charging" else "Discharging"
                    tvTemp.text = "${info.temperature}\u00B0C"
                    tvBatteryHealth.text = "Health: ${info.health}"
                    tvVoltage.text = "%.2fV".format(info.voltage)

                    val color = when {
                        info.isCharging -> R.color.battery_charging
                        info.percentage > 50 -> R.color.battery_good
                        info.percentage > 20 -> R.color.battery_medium
                        info.percentage > 10 -> R.color.battery_low
                        else -> R.color.battery_critical
                    }
                    tvBatteryPct.setTextColor(resources.getColor(color, theme))

                    // Health color: red if not Good
                    if (info.health != "Good") {
                        tvBatteryHealth.setTextColor(resources.getColor(R.color.battery_critical, theme))
                    } else {
                        tvBatteryHealth.setTextColor(resources.getColor(R.color.text_secondary, theme))
                    }

                    val barParams = viewBatteryBar.layoutParams as FrameLayout.LayoutParams
                    val parent = viewBatteryBar.parent as View
                    if (parent.width > 0) {
                        barParams.width = (parent.width * info.percentage) / 100
                        viewBatteryBar.layoutParams = barParams
                    }
                    viewBatteryBar.setBackgroundResource(color)
                }
            }
        }

        // Drain rate estimation
        lifecycleScope.launch {
            var lastPct = -1
            var lastTime = 0L
            BatteryMonitorService.batteryInfo.filterNotNull().collectLatest { info ->
                if (info.isCharging) {
                    mBindingPair.updateView { tvDrainRate.text = "Charging" }
                    lastPct = -1
                } else {
                    val now = System.currentTimeMillis()
                    if (lastPct >= 0 && lastTime > 0 && lastPct > info.percentage) {
                        val elapsed = (now - lastTime) / 3600000f // hours
                        if (elapsed > 0.01f) {
                            val rate = (lastPct - info.percentage) / elapsed
                            mBindingPair.updateView {
                                tvDrainRate.text = "%.1f%%/hr".format(rate)
                            }
                        }
                    }
                    if (lastPct < 0) {
                        lastPct = info.percentage
                        lastTime = now
                    }
                }
            }
        }

        lifecycleScope.launch {
            BatteryMonitorService.currentDraw.collectLatest { draw ->
                val mA = kotlin.math.abs(draw) / 1000
                val label = if (draw > 0) "+${mA} mA" else "${mA} mA"
                mBindingPair.updateView {
                    tvCurrentDraw.text = label
                }
            }
        }

        lifecycleScope.launch {
            BatteryMonitorService.estimatedMinutesLeft.collectLatest { mins ->
                mBindingPair.updateView {
                    tvTimeRemaining.text = when {
                        mins <= 0 -> "Estimating..."
                        mins < 60 -> "${mins}min remaining"
                        else -> "${mins / 60}h ${mins % 60}m remaining"
                    }
                }
            }
        }

        lifecycleScope.launch {
            BatteryMonitorService.activeAlert.filterNotNull().collectLatest { alert ->
                mBindingPair.updateView {
                    tvAlert.text = alert
                    tvAlert.visibility = View.VISIBLE
                }
            }
        }

        lifecycleScope.launch {
            profileManager.currentProfileFlow.collectLatest { profile ->
                profileAdapter.activeProfile = profile
                updateQuickControlsImmediate()
            }
        }

        lifecycleScope.launch {
            BatteryMonitorService.gpsStatus.collectLatest { gpsOn ->
                mBindingPair.updateView {
                    if (gpsOn && profileManager.currentProfile != PowerProfileManager.Profile.PERFORMANCE) {
                        btnGps.text = "GPS: LEAK!"
                        btnGps.setTextColor(resources.getColor(R.color.battery_critical, theme))
                    } else {
                        btnGps.text = "GPS: ${if (gpsOn) "ON" else "OFF"}"
                        btnGps.setTextColor(resources.getColor(R.color.text_primary, theme))
                    }
                }
            }
        }

        lifecycleScope.launch {
            BatteryMonitorService.gpsReactivatedBy.filterNotNull().collectLatest { source ->
                FToast.show("GPS re-enabled: $source")
            }
        }
    }

    private fun checkPermissions() {
        val missing = mutableListOf<String>()
        if (!Settings.System.canWrite(this)) missing.add("WRITE_SETTINGS")
        if (!Settings.canDrawOverlays(this)) missing.add("OVERLAY")

        val hasSecureSettings = try {
            Settings.Secure.putInt(contentResolver, "test_permission_check", 0)
            true
        } catch (e: SecurityException) {
            false
        }
        if (!hasSecureSettings) missing.add("WRITE_SECURE_SETTINGS")

        // Check battery optimization exemption (helps service survive)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            missing.add("BATTERY_OPT")
        }

        if (missing.isNotEmpty()) {
            val cmds = mutableListOf<String>()
            if ("WRITE_SETTINGS" in missing) {
                cmds.add("adb shell appops set $packageName WRITE_SETTINGS allow")
            }
            if ("OVERLAY" in missing) {
                cmds.add("adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
            }
            if ("WRITE_SECURE_SETTINGS" in missing) {
                cmds.add("adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
            }
            if ("BATTERY_OPT" in missing) {
                cmds.add("adb shell dumpsys deviceidle whitelist +$packageName")
            }
            FToast.show("Missing: ${missing.joinToString(", ")}")
            mBindingPair.updateView {
                tvAlert.text = "Grant via ADB:\n${cmds.joinToString("\n")}"
                tvAlert.visibility = View.VISIBLE
            }
        } else {
            // All permissions granted — hide the banner
            mBindingPair.updateView {
                tvAlert.visibility = View.GONE
            }
        }
    }
}
