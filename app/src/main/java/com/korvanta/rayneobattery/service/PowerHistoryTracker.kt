package com.korvanta.rayneobattery.service

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tracks power consumption history across sessions so the user can see
 * trends and identify what activities drain the most battery.
 *
 * Stores data in SharedPreferences as lightweight JSON.
 * Keeps the last 50 sessions to avoid unbounded storage growth.
 */
class PowerHistoryTracker(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Current session tracking
    private var sessionStartTime = 0L
    private var sessionStartPct = 0
    private var peakCurrentDraw = 0       // µA
    private var peakTemperature = 0f
    private var totalSamples = 0
    private var cumulativeCurrentDraw = 0L // for averaging
    private var profileUsage = mutableMapOf<String, Long>() // profile name → ms spent
    private var lastProfileChangeTime = 0L
    private var lastProfile = ""

    fun startSession(batteryPct: Int) {
        sessionStartTime = System.currentTimeMillis()
        sessionStartPct = batteryPct
        peakCurrentDraw = 0
        peakTemperature = 0f
        totalSamples = 0
        cumulativeCurrentDraw = 0L
        profileUsage.clear()
        lastProfileChangeTime = sessionStartTime
        lastProfile = ""
    }

    fun recordSample(currentDrawUa: Int, temperature: Float, profileName: String) {
        totalSamples++
        cumulativeCurrentDraw += kotlin.math.abs(currentDrawUa.toLong())

        if (kotlin.math.abs(currentDrawUa) > peakCurrentDraw) {
            peakCurrentDraw = kotlin.math.abs(currentDrawUa)
        }
        if (temperature > peakTemperature) {
            peakTemperature = temperature
        }

        // Track profile usage time
        val now = System.currentTimeMillis()
        if (lastProfile.isNotEmpty() && lastProfile != profileName) {
            val duration = now - lastProfileChangeTime
            profileUsage[lastProfile] = (profileUsage[lastProfile] ?: 0L) + duration
        }
        lastProfileChangeTime = now
        lastProfile = profileName
    }

    fun endSession(batteryPct: Int): SessionSummary {
        val now = System.currentTimeMillis()

        // Finalize last profile usage
        if (lastProfile.isNotEmpty()) {
            val duration = now - lastProfileChangeTime
            profileUsage[lastProfile] = (profileUsage[lastProfile] ?: 0L) + duration
        }

        val durationMin = ((now - sessionStartTime) / 60_000).toInt()
        val batteryUsed = sessionStartPct - batteryPct
        val avgCurrentMa = if (totalSamples > 0)
            (cumulativeCurrentDraw / totalSamples / 1000).toInt() else 0
        val peakCurrentMa = peakCurrentDraw / 1000

        // Drain rate: % per hour
        val drainRatePerHour = if (durationMin > 0)
            (batteryUsed * 60f) / durationMin else 0f

        val summary = SessionSummary(
            startTime = sessionStartTime,
            durationMinutes = durationMin,
            startPct = sessionStartPct,
            endPct = batteryPct,
            batteryUsed = batteryUsed,
            avgCurrentDrawMa = avgCurrentMa,
            peakCurrentDrawMa = peakCurrentMa,
            peakTemperature = peakTemperature,
            drainRatePerHour = drainRatePerHour,
            profileUsage = profileUsage.toMap()
        )

        saveSession(summary)
        return summary
    }

    private fun saveSession(summary: SessionSummary) {
        val history = loadHistory()
        history.add(summary)

        // Keep only last 50 sessions
        while (history.size > MAX_SESSIONS) {
            history.removeAt(0)
        }

        val jsonArray = JSONArray()
        for (s in history) {
            jsonArray.put(s.toJson())
        }
        prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
    }

    fun loadHistory(): MutableList<SessionSummary> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { SessionSummary.fromJson(array.getJSONObject(it)) }
                .toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun getAverageDrainRate(): Float {
        val history = loadHistory()
        if (history.isEmpty()) return 0f
        return history.map { it.drainRatePerHour }.average().toFloat()
    }

    fun getWorstProfile(): String? {
        val history = loadHistory()
        if (history.isEmpty()) return null

        // Aggregate profile usage across all sessions
        val totalUsage = mutableMapOf<String, Long>()
        for (session in history) {
            for ((profile, ms) in session.profileUsage) {
                totalUsage[profile] = (totalUsage[profile] ?: 0L) + ms
            }
        }

        // Find which profile correlates with highest drain
        // (simplified: just return the one used most during high-drain sessions)
        val highDrainSessions = history.filter { it.drainRatePerHour > 100f }
        if (highDrainSessions.isEmpty()) return null

        val highDrainUsage = mutableMapOf<String, Long>()
        for (session in highDrainSessions) {
            for ((profile, ms) in session.profileUsage) {
                highDrainUsage[profile] = (highDrainUsage[profile] ?: 0L) + ms
            }
        }

        return highDrainUsage.maxByOrNull { it.value }?.key
    }

    data class SessionSummary(
        val startTime: Long,
        val durationMinutes: Int,
        val startPct: Int,
        val endPct: Int,
        val batteryUsed: Int,
        val avgCurrentDrawMa: Int,
        val peakCurrentDrawMa: Int,
        val peakTemperature: Float,
        val drainRatePerHour: Float,
        val profileUsage: Map<String, Long>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("startTime", startTime)
            put("durationMinutes", durationMinutes)
            put("startPct", startPct)
            put("endPct", endPct)
            put("batteryUsed", batteryUsed)
            put("avgCurrentDrawMa", avgCurrentDrawMa)
            put("peakCurrentDrawMa", peakCurrentDrawMa)
            put("peakTemperature", peakTemperature)
            put("drainRatePerHour", drainRatePerHour)
            put("profileUsage", JSONObject(profileUsage.mapValues { it.value }))
        }

        companion object {
            fun fromJson(json: JSONObject): SessionSummary {
                val usageJson = json.optJSONObject("profileUsage") ?: JSONObject()
                val usage = mutableMapOf<String, Long>()
                for (key in usageJson.keys()) {
                    usage[key] = usageJson.getLong(key)
                }
                return SessionSummary(
                    startTime = json.getLong("startTime"),
                    durationMinutes = json.getInt("durationMinutes"),
                    startPct = json.getInt("startPct"),
                    endPct = json.getInt("endPct"),
                    batteryUsed = json.getInt("batteryUsed"),
                    avgCurrentDrawMa = json.getInt("avgCurrentDrawMa"),
                    peakCurrentDrawMa = json.getInt("peakCurrentDrawMa"),
                    peakTemperature = json.getFloat("peakTemperature"),
                    drainRatePerHour = json.getFloat("drainRatePerHour"),
                    profileUsage = usage
                )
            }
        }
    }

    companion object {
        const val PREFS_NAME = "power_history"
        const val KEY_HISTORY = "sessions"
        const val MAX_SESSIONS = 50
    }
}

// Extension for JSONObject
private fun JSONObject.getFloat(key: String): Float = getDouble(key).toFloat()
