package com.codleander.gamebooster

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SessionRecord(
    val start: Long,
    val end: Long,
    val durationMs: Long,
    val label: String,
    val packageName: String?,
    val peakThermal: Int,
    val freedMb: Int,
    val isBoost: Boolean
)

data class WeeklyReport(
    val boosts: Int,
    val previousBoosts: Int,
    val playMs: Long,
    val freedMb: Int,
    val activeDays: Int,
    val topGame: String?
)

object HistoryStore {

    private const val FILE = "booster_history"
    private const val MAX_ENTRIES = 2000

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(context: Context): List<SessionRecord> {
        val raw = prefs(context).getString("sessions", "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<SessionRecord>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val start = item.optLong("start", 0L)
            val duration = item.optLong("duration", 0L)
            if (start <= 0L) continue
            val pkg = if (item.has("package")) item.optString("package").ifBlank { null } else null
            out.add(
                SessionRecord(
                    start = start,
                    end = item.optLong("end", start + duration),
                    durationMs = duration,
                    label = item.optString("label", "Game"),
                    packageName = pkg,
                    peakThermal = item.optInt("peakThermal", -1),
                    freedMb = item.optInt("freedMb", 0),
                    isBoost = item.optString("type") == "boost"
                )
            )
        }
        out.sortByDescending { it.start }
        return out
    }

    fun add(context: Context, record: SessionRecord) {
        val raw = prefs(context).getString("sessions", "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        array.put(JSONObject().apply {
            put("start", record.start)
            put("end", record.end)
            put("duration", record.durationMs)
            put("label", record.label)
            record.packageName?.let { put("package", it) }
            put("peakThermal", record.peakThermal)
            put("freedMb", record.freedMb)
            put("type", if (record.isBoost) "boost" else "session")
        })
        while (array.length() > MAX_ENTRIES) array.remove(0)
        prefs(context).edit()
            .putString("sessions", array.toString())
            .putString("last_label", record.label)
            .putLong("last_duration_ms", record.durationMs)
            .apply()
    }

    fun boostsInRange(context: Context, since: Long, until: Long): Int =
        read(context).count { it.isBoost && it.start in since..until }

    fun playMsInRange(context: Context, since: Long, until: Long): Long =
        read(context)
            .filter { !it.isBoost && it.start in since..until }
            .sumOf { it.durationMs }

    fun averageFreedMb(context: Context, since: Long, until: Long): Int {
        val values = read(context)
            .filter { it.isBoost && it.start in since..until && it.freedMb > 0 }
            .map { it.freedMb }
        if (values.isEmpty()) return 0
        return values.sum() / values.size
    }

    fun recent(context: Context, limit: Int): List<SessionRecord> =
        read(context).take(limit)

    /** Aggregates the last seven days and the seven days before that. */
    fun weeklyReport(context: Context, now: Long): WeeklyReport {
        val weekMs = 7L * 24L * 60L * 60L * 1000L
        val since = now - weekMs
        val records = read(context)
        val current = records.filter { it.start in since..now }
        val previous = records.filter { it.start in (since - weekMs)..since }
        val zone = java.time.ZoneId.systemDefault()
        val activeDays = current
            .map { java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }
            .distinct()
            .size
        val topGame = current
            .filter { !it.isBoost }
            .groupBy { it.label }
            .maxByOrNull { entry -> entry.value.sumOf { it.durationMs } }
            ?.key
        return WeeklyReport(
            boosts = current.count { it.isBoost },
            previousBoosts = previous.count { it.isBoost },
            playMs = current.filter { !it.isBoost }.sumOf { it.durationMs },
            freedMb = current.filter { it.isBoost }.sumOf { it.freedMb },
            activeDays = activeDays,
            topGame = topGame
        )
    }

    /** Play minutes per evenly sized bucket across [since, until]. */
    fun buckets(context: Context, since: Long, until: Long, count: Int): FloatArray {
        val out = FloatArray(count.coerceAtLeast(1))
        val span = (until - since).coerceAtLeast(1L)
        val bucketMs = (span / out.size).coerceAtLeast(1L)
        read(context).forEach { record ->
            if (record.isBoost || record.durationMs <= 0L) return@forEach
            val middle = record.start + record.durationMs / 2
            if (middle < since || middle > until) return@forEach
            val index = ((middle - since) / bucketMs).toInt().coerceIn(0, out.size - 1)
            out[index] += record.durationMs / 60000f
        }
        return out
    }
}
