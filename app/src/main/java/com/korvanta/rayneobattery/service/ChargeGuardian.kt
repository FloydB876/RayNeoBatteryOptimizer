package com.korvanta.rayneobattery.service

/**
 * Monitors charging behavior and alerts the user to optimize
 * long-term battery health on the 245mAh cell.
 *
 * With only 245mAh, every charge cycle matters. Lithium-ion batteries
 * degrade fastest when:
 * 1. Held at 100% for extended periods
 * 2. Fully discharged repeatedly
 * 3. Charged at high temperatures
 *
 * The ChargeGuardian tracks these patterns and provides alerts.
 */
class ChargeGuardian {

    private var chargeStartTime = 0L
    private var chargeStartPct = 0
    private var isCurrentlyCharging = false
    private var timeAt100Pct = 0L

    /**
     * Call on every battery update. Returns an alert message if action needed,
     * or null if everything is fine.
     */
    fun evaluate(info: BatteryInfo): ChargeAlert? {
        val now = System.currentTimeMillis()

        // Detect charge start
        if (info.isCharging && !isCurrentlyCharging) {
            isCurrentlyCharging = true
            chargeStartTime = now
            chargeStartPct = info.percentage
            timeAt100Pct = 0L
        }

        // Detect charge end
        if (!info.isCharging && isCurrentlyCharging) {
            isCurrentlyCharging = false
            timeAt100Pct = 0L
        }

        if (!info.isCharging) {
            // Alert: discharging below 5% damages the cell
            if (info.percentage <= 5) {
                return ChargeAlert(
                    message = "Battery critically low (${info.percentage}%). Charge soon to avoid cell damage.",
                    severity = Severity.CRITICAL
                )
            }
            return null
        }

        // --- Charging checks ---

        // Alert: charging at high temperature degrades battery faster
        if (info.temperature > 40f) {
            return ChargeAlert(
                message = "Charging at ${info.temperature}\u00B0C. Remove from heat to protect battery.",
                severity = Severity.WARNING
            )
        }

        // Alert: sitting at 100% for more than 10 minutes
        if (info.percentage >= 100) {
            if (timeAt100Pct == 0L) {
                timeAt100Pct = now
            }
            val minutesAt100 = (now - timeAt100Pct) / 60_000
            if (minutesAt100 >= 10) {
                return ChargeAlert(
                    message = "Unplug! Sitting at 100% for ${minutesAt100}min damages the cell.",
                    severity = Severity.WARNING
                )
            }
        } else {
            timeAt100Pct = 0L
        }

        // Recommendation: stop charging at 80% for longevity
        if (info.percentage in 80..89 && chargeStartPct < 80) {
            return ChargeAlert(
                message = "Battery at ${info.percentage}%. Unplug at 80% for optimal longevity.",
                severity = Severity.INFO
            )
        }

        return null
    }

    data class ChargeAlert(
        val message: String,
        val severity: Severity
    )

    enum class Severity {
        INFO,     // Suggestion, dismissable
        WARNING,  // Should act soon
        CRITICAL  // Act immediately
    }
}
