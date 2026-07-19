package com.gary.guardian

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import java.io.File

object SecurityChecklist {

    val items: List<SecurityCheckItem> = listOf(
        SecurityCheckItem(
            "Screen lock",
            "Without a PIN, pattern, password, or biometric lock, anyone who picks up your phone has full access to it, including installing spyware.",
            Settings.ACTION_SECURITY_SETTINGS
        ) { ctx ->
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (km.isDeviceSecure) {
                SecurityCheckResult(CheckStatus.SAFE, "A screen lock is set")
            } else {
                SecurityCheckResult(CheckStatus.WARNING, "No screen lock is set")
            }
        },

        SecurityCheckItem(
            "USB debugging",
            "USB debugging (ADB) lets any connected computer install apps, read app data, and run commands on your phone without a prompt on some setups. Leave it off unless you're actively developing.",
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        ) { ctx ->
            val enabled = Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
            if (enabled) {
                SecurityCheckResult(CheckStatus.WARNING, "USB debugging is currently ON")
            } else {
                SecurityCheckResult(CheckStatus.SAFE, "USB debugging is off")
            }
        },

        SecurityCheckItem(
            "Unknown app sources",
            "Installing apps from outside the Play Store skips Google's malware scanning. Fine for one-off sideloads you trust, risky if left broadly enabled.",
            Settings.ACTION_SECURITY_SETTINGS
        ) { ctx ->
            val value = Settings.Secure.getString(ctx.contentResolver, "install_non_market_apps")
            if (value == "1") {
                SecurityCheckResult(CheckStatus.WARNING, "Installing from unknown sources is allowed system-wide")
            } else {
                SecurityCheckResult(CheckStatus.INFO, "Controlled per-app on this Android version, nothing globally enabled")
            }
        },

        SecurityCheckItem(
            "Google Play Protect",
            "Play Protect scans apps for malware before and after install. Check Play Store > profile icon > Play Protect to confirm scanning is on.",
            Settings.ACTION_SECURITY_SETTINGS
        ) { ctx ->
            val value = Settings.Global.getString(ctx.contentResolver, "package_verifier_enable")
            if (value == "0") {
                SecurityCheckResult(CheckStatus.WARNING, "APK verification has been explicitly disabled")
            } else {
                SecurityCheckResult(CheckStatus.INFO, "No explicit override detected, verify manually in Play Store")
            }
        },

        SecurityCheckItem(
            "Accessibility services",
            "Any app with accessibility access can read your screen and simulate taps. This is the single most common way phone spyware operates. Review what's listed and remove anything you don't recognize.",
            Settings.ACTION_ACCESSIBILITY_SETTINGS
        ) { ctx ->
            val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val services = raw.split(":").filter { it.isNotBlank() }
            if (services.isEmpty()) {
                SecurityCheckResult(CheckStatus.SAFE, "No accessibility services enabled")
            } else {
                SecurityCheckResult(CheckStatus.WARNING, "${services.size} enabled: ${services.joinToString(", ")}")
            }
        },

        SecurityCheckItem(
            "Notification access",
            "Apps with notification access can read the text of every notification you receive, including 2FA codes and message previews.",
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
        ) { ctx ->
            val raw = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
            val listeners = raw.split(":").filter { it.isNotBlank() }
            if (listeners.isEmpty()) {
                SecurityCheckResult(CheckStatus.SAFE, "No notification listeners enabled")
            } else {
                SecurityCheckResult(CheckStatus.INFO, "${listeners.size} app(s) can read your notifications, review the list")
            }
        },

        SecurityCheckItem(
            "Device admin apps",
            "Device admin apps can lock your screen, wipe your device, or enforce policies remotely. Only trust apps you specifically set this up for (like a company MDM or Samsung's own Knox features).",
            Settings.ACTION_SECURITY_SETTINGS
        ) { ctx ->
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val count = dpm.activeAdmins?.size ?: 0
            if (count == 0) {
                SecurityCheckResult(CheckStatus.SAFE, "No device admin apps active")
            } else {
                SecurityCheckResult(CheckStatus.INFO, "$count active, review in Guardian's app log")
            }
        },

        SecurityCheckItem(
            "Guardian battery exemption",
            "Samsung and other OEMs aggressively kill background apps to save battery. If Guardian isn't exempted, it can stop monitoring without you noticing.",
            "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
        ) { ctx ->
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                SecurityCheckResult(CheckStatus.SAFE, "Guardian is exempt from battery optimization")
            } else {
                SecurityCheckResult(CheckStatus.WARNING, "Guardian may be killed in the background")
            }
        },

        SecurityCheckItem(
            "Usage access for Guardian",
            "Needed for Guardian's network-usage anomaly detection, spotting apps that suddenly start sending a lot more data than usual.",
            Settings.ACTION_USAGE_ACCESS_SETTINGS
        ) { ctx ->
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            if (mode == AppOpsManager.MODE_ALLOWED) {
                SecurityCheckResult(CheckStatus.SAFE, "Usage access granted")
            } else {
                SecurityCheckResult(CheckStatus.WARNING, "Not granted, network anomaly detection is off")
            }
        },

        SecurityCheckItem(
            "Security patch age",
            "Most real-world compromise doesn't need a true zero-day, it exploits known vulnerabilities on phones that haven't installed the fix yet. Staying current on patches closes that window.",
            "android.settings.SYSTEM_UPDATE_SETTINGS"
        ) { _ ->
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val patchDate = sdf.parse(Build.VERSION.SECURITY_PATCH)
                if (patchDate == null) {
                    SecurityCheckResult(CheckStatus.INFO, "Could not read patch date")
                } else {
                    val days = (System.currentTimeMillis() - patchDate.time) / (1000L * 60 * 60 * 24)
                    if (days > 90) {
                        SecurityCheckResult(CheckStatus.WARNING, "Patch is $days days old (${Build.VERSION.SECURITY_PATCH})")
                    } else {
                        SecurityCheckResult(CheckStatus.SAFE, "Patch is $days days old (${Build.VERSION.SECURITY_PATCH})")
                    }
                }
            } catch (e: Exception) {
                SecurityCheckResult(CheckStatus.INFO, "Could not determine patch age")
            }
        },

        SecurityCheckItem(
            "Root status",
            "A rooted device can hide malware from every app on it, including Guardian. This is a best-effort check only, a determined attacker can hide root from this kind of scan.",
            null
        ) { _ ->
            val suPaths = listOf(
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/su/bin/su"
            )
            val rooted = Build.TAGS?.contains("test-keys") == true || suPaths.any { File(it).exists() }
            if (rooted) {
                SecurityCheckResult(CheckStatus.WARNING, "Possible root indicators found")
            } else {
                SecurityCheckResult(CheckStatus.SAFE, "No root indicators found")
            }
        }
    )
}
