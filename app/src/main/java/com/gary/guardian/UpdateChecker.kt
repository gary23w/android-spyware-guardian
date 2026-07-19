package com.gary.guardian

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val API_URL = "https://api.github.com/repos/gary23w/android-spyware-guardian/releases/latest"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun shouldCheck(context: Context): Boolean {
        val prefs = context.getSharedPreferences(GuardianPrefs.SETTINGS_FILE, Context.MODE_PRIVATE)
        val last = prefs.getLong("last_update_check", 0L)
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS
    }

    fun check(context: Context): File? {
        val prefs = context.getSharedPreferences(GuardianPrefs.SETTINGS_FILE, Context.MODE_PRIVATE)
        prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()

        return try {
            val json = httpGet(API_URL)
            val obj = JSONObject(json)
            val tagName = obj.optString("tag_name", "").removePrefix("v")
            if (tagName.isEmpty()) return null

            val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
            if (!isNewer(tagName, currentVersion)) return null

            val assets = obj.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isNullOrEmpty()) return null

            val apkFile = downloadApk(context, apkUrl)
            AlertLog.write(context, "INFO", "Update available: v$tagName downloaded, ready to install")
            apkFile
        } catch (e: Exception) {
            AlertLog.write(context, "ERROR", "Update check failed: ${e.message}")
            null
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun downloadApk(context: Context, url: String): File {
        val dir = File(context.getExternalFilesDir(null), "updates")
        dir.mkdirs()
        val file = File(dir, "guardian-update.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        conn.inputStream.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    fun installIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        return intent
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "guardian-app")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.inputStream.use { stream -> return stream.bufferedReader().readText() }
    }
}
