package com.gary.guardian

import android.content.Context

enum class FlagStatus { PENDING, KEPT, REMOVAL_REQUESTED }

data class FlaggedAppEntry(
    val packageName: String,
    val reason: String,
    val status: FlagStatus,
    val timestamp: Long
)

object FlaggedApps {
    private const val FILE = "guardian_flagged"
    private const val KEY = "entries"
    private const val DELIM = "::"

    fun record(context: Context, packageName: String, reason: String) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val entries = getAll(context).associateBy { it.packageName }.toMutableMap()
        if (entries.containsKey(packageName)) return
        entries[packageName] = FlaggedAppEntry(packageName, reason, FlagStatus.PENDING, System.currentTimeMillis())
        save(prefs, entries.values)
    }

    fun setStatus(context: Context, packageName: String, status: FlagStatus) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val entries = getAll(context).associateBy { it.packageName }.toMutableMap()
        val existing = entries[packageName]
        entries[packageName] = if (existing != null) {
            existing.copy(status = status)
        } else {
            FlaggedAppEntry(packageName, "Manually reviewed", status, System.currentTimeMillis())
        }
        save(prefs, entries.values)
    }

    fun getAll(context: Context): List<FlaggedAppEntry> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
        return raw.mapNotNull { decode(it) }.sortedByDescending { it.timestamp }
    }

    private fun save(prefs: android.content.SharedPreferences, entries: Collection<FlaggedAppEntry>) {
        val encoded = entries.map { encode(it) }.toSet()
        prefs.edit().putStringSet(KEY, encoded).apply()
    }

    private fun encode(e: FlaggedAppEntry): String {
        return listOf(e.packageName, e.reason, e.status.name, e.timestamp.toString()).joinToString(DELIM)
    }

    private fun decode(s: String): FlaggedAppEntry? {
        val parts = s.split(DELIM)
        if (parts.size < 4) return null
        val pkg = parts[0]
        val status = try { FlagStatus.valueOf(parts[parts.size - 2]) } catch (e: Exception) { FlagStatus.PENDING }
        val timestamp = parts[parts.size - 1].toLongOrNull() ?: 0L
        val reason = parts.subList(1, parts.size - 2).joinToString(DELIM)
        return FlaggedAppEntry(pkg, reason, status, timestamp)
    }
}
