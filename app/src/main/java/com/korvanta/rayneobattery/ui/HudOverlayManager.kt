package com.korvanta.rayneobattery.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.korvanta.rayneobattery.R
import com.korvanta.rayneobattery.profile.PowerProfileManager
import com.korvanta.rayneobattery.service.BatteryInfo

class HudOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("battery_optimizer", Context.MODE_PRIVATE)

    private var overlayView: View? = null
    private var leftHud: HudViews? = null
    private var rightHud: HudViews? = null
    private var isShowing = false

    var isEnabled: Boolean
        get() = prefs.getBoolean("hud_enabled", true)
        set(value) { prefs.edit().putBoolean("hud_enabled", value).apply() }

    fun show() {
        if (isShowing) return

        val inflater = LayoutInflater.from(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Left eye HUD — centered in the left half
        val leftContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val leftView = inflater.inflate(R.layout.hud_overlay, leftContainer, false)
        leftContainer.addView(leftView)
        leftHud = HudViews.from(leftView)

        // Right eye HUD — centered in the right half
        val rightContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val rightView = inflater.inflate(R.layout.hud_overlay, rightContainer, false)
        rightContainer.addView(rightView)
        rightHud = HudViews.from(rightView)

        root.addView(leftContainer)
        root.addView(rightContainer)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        windowManager.addView(root, params)
        overlayView = root
        isShowing = true
    }

    fun hide() {
        if (!isShowing) return
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            // View may already be removed
        }
        overlayView = null
        leftHud = null
        rightHud = null
        isShowing = false
    }

    fun toggle() {
        if (isShowing) {
            hide()
            isEnabled = false
        } else {
            show()
            isEnabled = true
        }
    }

    fun showIfEnabled() {
        if (isEnabled && !isShowing) show()
    }

    fun isVisible(): Boolean = isShowing

    fun updateBattery(info: BatteryInfo) {
        updateBothEyes { hud ->
            hud.batteryPct.text = "${info.percentage}%"
            val colorRes = when {
                info.isCharging -> R.color.battery_charging
                info.percentage > 50 -> R.color.battery_good
                info.percentage > 20 -> R.color.battery_medium
                info.percentage > 10 -> R.color.battery_low
                else -> R.color.battery_critical
            }
            hud.batteryPct.setTextColor(context.resources.getColor(colorRes, context.theme))
            hud.temp.text = " \u00B7 ${info.temperature}\u00B0C"
            if (info.temperature > 42f) {
                hud.temp.setTextColor(context.resources.getColor(R.color.battery_critical, context.theme))
            } else {
                hud.temp.setTextColor(context.resources.getColor(R.color.text_secondary, context.theme))
            }
        }
    }

    fun updateCurrentDraw(microAmps: Int) {
        val mA = kotlin.math.abs(microAmps) / 1000
        val direction = if (microAmps > 0) "+" else "-"
        updateBothEyes { hud ->
            hud.currentDraw.text = "${direction}${mA} mA"
        }
    }

    fun updateTimeRemaining(minutes: Int) {
        updateBothEyes { hud ->
            hud.timeLeft.text = when {
                minutes <= 0 -> " \u00B7 --m"
                minutes < 60 -> " \u00B7 ${minutes}m"
                else -> " \u00B7 ${minutes / 60}h${minutes % 60}m"
            }
        }
    }

    fun updateProfile(profile: PowerProfileManager.Profile) {
        val shortName = when (profile) {
            PowerProfileManager.Profile.PERFORMANCE -> "PERF"
            PowerProfileManager.Profile.BALANCED -> "BAL"
            PowerProfileManager.Profile.POWER_SAVER -> "SAVE"
            PowerProfileManager.Profile.ULTRA_SAVER -> "ULTRA"
            PowerProfileManager.Profile.NAVIGATION -> "NAV"
            PowerProfileManager.Profile.RECORDING -> "REC"
        }
        updateBothEyes { hud -> hud.profile.text = shortName }
    }

    fun updateRadios(wifiOn: Boolean, btOn: Boolean, gpsOn: Boolean, gpsLeak: Boolean) {
        updateBothEyes { hud ->
            val w = if (wifiOn) "W" else "-"
            val b = if (btOn) "B" else "-"
            val g = when {
                gpsLeak -> "G!"
                gpsOn -> "G"
                else -> "-"
            }
            hud.radios.text = " $w $b $g"
            if (gpsLeak) {
                hud.radios.setTextColor(context.resources.getColor(R.color.battery_critical, context.theme))
            } else {
                hud.radios.setTextColor(context.resources.getColor(R.color.text_secondary, context.theme))
            }
        }
    }

    fun updateIdleStatus(isIdle: Boolean) {
        updateBothEyes { hud ->
            if (isIdle) {
                hud.idleStatus.text = "ZZZ"
                hud.idleStatus.visibility = View.VISIBLE
            } else {
                hud.idleStatus.visibility = View.GONE
            }
        }
    }

    fun updateAlert(message: String?) {
        updateBothEyes { hud ->
            if (message != null) {
                hud.alert.text = message
                hud.alert.visibility = View.VISIBLE
            } else {
                hud.alert.visibility = View.GONE
            }
        }
    }

    private fun updateBothEyes(block: (HudViews) -> Unit) {
        leftHud?.let { block(it) }
        rightHud?.let { block(it) }
    }

    private data class HudViews(
        val batteryPct: TextView,
        val timeLeft: TextView,
        val currentDraw: TextView,
        val temp: TextView,
        val profile: TextView,
        val radios: TextView,
        val idleStatus: TextView,
        val alert: TextView
    ) {
        companion object {
            fun from(view: View): HudViews = HudViews(
                batteryPct = view.findViewById(R.id.hudBatteryPct),
                timeLeft = view.findViewById(R.id.hudTimeLeft),
                currentDraw = view.findViewById(R.id.hudCurrentDraw),
                temp = view.findViewById(R.id.hudTemp),
                profile = view.findViewById(R.id.hudProfile),
                radios = view.findViewById(R.id.hudRadios),
                idleStatus = view.findViewById(R.id.hudIdleStatus),
                alert = view.findViewById(R.id.hudAlert)
            )
        }
    }
}
