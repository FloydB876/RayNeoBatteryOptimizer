# Mercury SDK
-keep class com.ffalcon.mercury.android.sdk.** { *; }
-keep class com.tct.gesturedetectorwithsound.** { *; }

# ViewBinding
-keep class com.korvanta.rayneobattery.databinding.** { *; }

# Keep our services and receivers (launched by system)
-keep class com.korvanta.rayneobattery.service.BatteryMonitorService { *; }
-keep class com.korvanta.rayneobattery.receiver.BatteryReceiver { *; }
-keep class com.korvanta.rayneobattery.BatteryOptimizerApp { *; }

# Keep BatteryInfo data class (used with StateFlow)
-keep class com.korvanta.rayneobattery.service.BatteryInfo { *; }
-keep class com.korvanta.rayneobattery.service.PowerHistoryTracker$SessionSummary { *; }
-keep class com.korvanta.rayneobattery.service.ChargeGuardian$* { *; }
-keep class com.korvanta.rayneobattery.profile.PowerProfileManager$Profile { *; }

# Keep enum values (used by Profile.valueOf)
-keepclassmembers enum * { *; }
