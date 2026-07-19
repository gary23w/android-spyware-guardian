package com.gary.guardian

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

object IndicatorUpdater {
    private const val INDEX_URL =
        "https://raw.githubusercontent.com/mvt-project/mvt-indicators/main/indicators.yaml"
    private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000

    private val PATTERN_REGEX = Regex(
        "(app:id|process:name|file:name|file:hashes\\.sha256|domain-name:value)\\s*=\\s*'([^']+)'"
    )

    data class IndicatorSet(
        val packageIds: Set<String>,
        val processNames: Set<String>,
        val fileNames: Set<String>,
        val sha256Hashes: Set<String>,
        val domains: Set<String>,
        val sourceCount: Int
    )

    fun shouldUpdate(context: Context): Boolean {
        val prefs = context.getSharedPreferences(GuardianPrefs.INDICATORS_FILE, Context.MODE_PRIVATE)
        val last = prefs.getLong(GuardianPrefs.KEY_LAST_INDICATOR_UPDATE, 0L)
        return System.currentTimeMillis() - last >= UPDATE_INTERVAL_MS
    }

    fun update(context: Context) {
        val sources = try {
            fetchSourceList()
        } catch (e: Exception) {
            AlertLog.write(context, "ERROR", "Could not reach mvt-indicators index: ${e.message}")
            return
        }

        val packageIds = mutableSetOf<String>()
        val processNames = mutableSetOf<String>()
        val fileNames = mutableSetOf<String>()
        val hashes = mutableSetOf<String>()
        val domains = mutableSetOf<String>()
        var okCount = 0

        for (url in sources) {
            try {
                val json = httpGet(url)
                parseStix2(json, packageIds, processNames, fileNames, hashes, domains)
                okCount++
            } catch (e: Exception) {
                AlertLog.write(context, "ERROR", "Indicator fetch failed for $url: ${e.message}")
            }
        }

        if (okCount == 0) {
            AlertLog.write(context, "ERROR", "Indicator update failed: none of ${sources.size} feeds reachable")
            return
        }

        val set = IndicatorSet(packageIds, processNames, fileNames, hashes, domains, okCount)
        persist(context, set)

        val prefs = context.getSharedPreferences(GuardianPrefs.INDICATORS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putLong(GuardianPrefs.KEY_LAST_INDICATOR_UPDATE, System.currentTimeMillis()).apply()

        AlertLog.write(
            context, "INFO",
            "Indicator update complete: $okCount/${sources.size} feeds, " +
                "${packageIds.size} package IDs, ${hashes.size} file hashes, " +
                "${fileNames.size} file names, ${domains.size} domains"
        )
    }

    fun load(context: Context): IndicatorSet {
        val prefs = context.getSharedPreferences(GuardianPrefs.INDICATORS_FILE, Context.MODE_PRIVATE)
        return IndicatorSet(
            prefs.getStringSet("package_ids", emptySet()) ?: emptySet(),
            prefs.getStringSet("process_names", emptySet()) ?: emptySet(),
            prefs.getStringSet("file_names", emptySet()) ?: emptySet(),
            prefs.getStringSet("sha256_hashes", emptySet()) ?: emptySet(),
            prefs.getStringSet("domains", emptySet()) ?: emptySet(),
            prefs.getInt("source_count", 0)
        )
    }

    fun sha256OfFile(path: String): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            File(path).inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun persist(context: Context, set: IndicatorSet) {
        val prefs = context.getSharedPreferences(GuardianPrefs.INDICATORS_FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("package_ids", set.packageIds)
            .putStringSet("process_names", set.processNames)
            .putStringSet("file_names", set.fileNames)
            .putStringSet("sha256_hashes", set.sha256Hashes)
            .putStringSet("domains", set.domains)
            .putInt("source_count", set.sourceCount)
            .apply()
    }

    private fun fetchSourceList(): List<String> {
        val yaml = httpGet(INDEX_URL)
        val urls = mutableListOf<String>()
        var owner = ""
        var repo = ""
        var branch = ""
        for (rawLine in yaml.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("owner:") -> owner = line.substringAfter(":").trim()
                line.startsWith("repo:") -> repo = line.substringAfter(":").trim()
                line.startsWith("branch:") -> branch = line.substringAfter(":").trim()
                line.startsWith("path:") -> {
                    val path = line.substringAfter(":").trim()
                    if (owner.isNotEmpty() && repo.isNotEmpty() && branch.isNotEmpty()) {
                        urls.add("https://raw.githubusercontent.com/$owner/$repo/$branch/$path")
                    }
                }
            }
        }
        return urls
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "guardian-app")
        conn.inputStream.use { stream ->
            return stream.bufferedReader().readText()
        }
    }

    private fun parseStix2(
        json: String,
        packageIds: MutableSet<String>,
        processNames: MutableSet<String>,
        fileNames: MutableSet<String>,
        hashes: MutableSet<String>,
        domains: MutableSet<String>
    ) {
        val root = JSONObject(json)
        val objects: JSONArray = if (root.has("objects")) root.getJSONArray("objects") else JSONArray()
        for (i in 0 until objects.length()) {
            val obj = objects.optJSONObject(i) ?: continue
            if (obj.optString("type") != "indicator") continue
            val pattern = obj.optString("pattern", "")
            for (match in PATTERN_REGEX.findAll(pattern)) {
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                when (key) {
                    "app:id" -> packageIds.add(value.lowercase())
                    "process:name" -> processNames.add(value)
                    "file:name" -> fileNames.add(value)
                    "file:hashes.sha256" -> hashes.add(value.lowercase())
                    "domain-name:value" -> domains.add(value.lowercase())
                }
            }
        }
    }
}
