package com.korvanta.rayneobattery

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.ffalcon.mercury.android.sdk.MercurySDK

class BatteryOptimizerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        MercurySDK.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing battery monitoring for RayNeo X3 Pro"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "battery_monitor"
    }
}
