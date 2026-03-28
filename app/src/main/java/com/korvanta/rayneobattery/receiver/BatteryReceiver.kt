package com.korvanta.rayneobattery.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.korvanta.rayneobattery.service.BatteryMonitorService

class BatteryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Restart battery monitor after device reboot
                val serviceIntent = Intent(context, BatteryMonitorService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
