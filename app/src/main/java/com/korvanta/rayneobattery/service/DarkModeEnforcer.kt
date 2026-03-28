package com.korvanta.rayneobattery.service

import android.content.Context
import android.provider.Settings

/**
 * Forces system-wide dark mode on the X3 Pro to minimize MicroLED
 * power consumption. White pixels on MicroLED are the #1 display
 * power drain — each white pixel requires all three sub-pixels (R+G+B)
 * at full brightness.
 *
 * This enforcer:
 * 1. Sets the system UI mode to dark/night
 * 2. Reduces system animation scales to cut GPU power
 * 3. Disables wallpaper (not applicable on glasses but prevents
 *    any system process from rendering one)
 *
 * Requires WRITE_SECURE_SETTINGS (granted via ADB).
 */
class DarkModeEnforcer(private val context: Context) {

    /**
     * Apply all power-saving display settings.
     */
    fun enforce() {
        forceNightMode()
        reduceAnimations()
        setForceDarkMode()
    }

    /**
     * Restore default display settings.
     */
    fun restore() {
        restoreNightMode()
        restoreAnimations()
        clearForceDarkMode()
    }

    /**
     * Enable system night mode (dark theme).
     * UI_NIGHT_MODE: 1 = auto, 2 = yes (dark), 3 = no (light)
     */
    private fun forceNightMode() {
        try {
            Settings.Secure.putInt(
                context.contentResolver,
                "ui_night_mode",
                2 // Force dark
            )
        } catch (e: SecurityException) {
            // WRITE_SECURE_SETTINGS not available
        }
    }

    private fun restoreNightMode() {
        try {
            Settings.Secure.putInt(
                context.contentResolver,
                "ui_night_mode",
                1 // Auto
            )
        } catch (e: SecurityException) {}
    }

    /**
     * Reduce animation scales to 0.5x — cuts GPU workload and
     * reduces the time bright transition frames are displayed.
     */
    private fun reduceAnimations() {
        try {
            Settings.Global.putFloat(
                context.contentResolver,
                Settings.Global.WINDOW_ANIMATION_SCALE,
                0.5f
            )
            Settings.Global.putFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                0.5f
            )
            Settings.Global.putFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                0.5f
            )
        } catch (e: SecurityException) {}
    }

    private fun restoreAnimations() {
        try {
            Settings.Global.putFloat(
                context.contentResolver,
                Settings.Global.WINDOW_ANIMATION_SCALE,
                1.0f
            )
            Settings.Global.putFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1.0f
            )
            Settings.Global.putFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
        } catch (e: SecurityException) {}
    }

    /**
     * Android 10+ has a "force dark mode" developer setting that applies
     * dark theming to apps that don't natively support it. This is
     * extremely useful on MicroLED where white backgrounds are costly.
     *
     * Setting key: "force_dark_mode" or via secure settings.
     */
    private fun setForceDarkMode() {
        try {
            // Force dark mode on all apps (developer option)
            Settings.Secure.putInt(
                context.contentResolver,
                "force_dark_mode",
                1
            )
        } catch (e: SecurityException) {}

        try {
            // Also try the alternative key used on some Android 12 builds
            Settings.Global.putInt(
                context.contentResolver,
                "force_dark_mode",
                1
            )
        } catch (e: SecurityException) {}
    }

    private fun clearForceDarkMode() {
        try {
            Settings.Secure.putInt(
                context.contentResolver,
                "force_dark_mode",
                0
            )
        } catch (e: SecurityException) {}

        try {
            Settings.Global.putInt(
                context.contentResolver,
                "force_dark_mode",
                0
            )
        } catch (e: SecurityException) {}
    }

    /**
     * Check if night mode is currently active.
     */
    fun isNightModeActive(): Boolean {
        return try {
            Settings.Secure.getInt(context.contentResolver, "ui_night_mode") == 2
        } catch (e: Settings.SettingNotFoundException) {
            false
        }
    }
}
