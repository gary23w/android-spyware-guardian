package com.gary.guardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.io.File

class MonitorService : Service() {

    private lateinit var prefs: SharedPreferences
    @Volatile private var running = false
    private var worker: Thread? = null

    companion object {
        const val CHANNEL_ID = "guardian_monitor"
        const val NOTIF_ID = 1
        const val CHECK_INTERVAL_MS = 60_000L

        val DANGEROUS_PERMS = setOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.SEND_SMS",
            "android.permission.READ_CALL_LOG",
            "android.permission.READ_CONTACTS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.CALL_PHONE",
            "android.permission.REQUEST_INSTALL_PACKAGES"
        )
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(GuardianPrefs.STATE_FILE, Context.MODE_PRIVATE)
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        AlertLog.write(this, "INFO", "MonitorService started")
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker == null) startLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        AlertLog.write(this, "INFO", "MonitorService stopped")
        super.onDestroy()
    }

    private fun startLoop() {
        running = true
        val t = Thread {
            runChecks()
            while (running) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
                if (running) runChecks()
            }
        }
        t.isDaemon = true
        t.start()
        worker = t
    }

    private fun runChecks() {
        try {
            if (IndicatorUpdater.shouldUpdate(this)) {
                IndicatorUpdater.update(this)
            }
            checkAccessibility()
            checkNotificationListeners()
            checkDeviceAdmins()
            checkPackagesAndPermissions()
            checkIocMatches()
            checkHiddenDangerousApps()
        } catch (e: Exception) {
            AlertLog.write(this, "ERROR", "Check cycle failed: ${e.message}")
        }
    }

    // ---- change-diffing checks ----

    private fun diffAndStore(key: String, current: Set<String>, label: String) {
        val previous = prefs.getStringSet(key, null)
        if (previous == null) {
            prefs.edit().putStringSet(key, HashSet(current)).apply()
            AlertLog.write(this, "INFO", "Baseline recorded for $label: ${current.size} entries")
            return
        }
        val added = current - previous
        val removed = previous - current
        for (a in added) {
            AlertLog.write(this, "HIGH", "$label: NEW -> $a")
            notifyInfo("Guardian Alert", "$label: $a")
        }
        for (r in removed) {
            AlertLog.write(this, "INFO", "$label: removed -> $r")
        }
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            prefs.edit().putStringSet(key, HashSet(current)).apply()
        }
    }

    private fun checkAccessibility() {
        val raw = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val set = raw.split(":").filter { it.isNotBlank() }.toSet()
        diffAndStore("accessibility_services", set, "Accessibility service")
    }

    private fun checkNotificationListeners() {
        val raw = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val set = raw.split(":").filter { it.isNotBlank() }.toSet()
        diffAndStore("notification_listeners", set, "Notification listener")
    }

    private fun checkDeviceAdmins() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admins = dpm.activeAdmins?.map { it.flattenToString() }?.toSet() ?: emptySet()
        diffAndStore("device_admins", admins, "Device admin")
    }

    private fun checkPackagesAndPermissions() {
        val pm = packageManager
        val grants = mutableSetOf<String>()
        val installed = mutableSetOf<String>()
        val pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        for (p in pkgs) {
            installed.add(p.packageName)
            val requested = p.requestedPermissions ?: continue
            val flags = p.requestedPermissionsFlags ?: continue
            for (i in requested.indices) {
                if (i >= flags.size) continue
                if (requested[i] in DANGEROUS_PERMS &&
                    (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                ) {
                    grants.add("${p.packageName}:${requested[i]}")
                }
            }
        }
        diffAndStore("installed_packages", installed, "Installed package")
        diffAndStore("dangerous_grants", grants, "Dangerous permission grant")
    }

    // ---- spyware indicator + hidden app checks ----

    private fun checkIocMatches() {
        val indicators = IndicatorUpdater.load(this)
        if (indicators.packageIds.isEmpty() && indicators.sha256Hashes.isEmpty() && indicators.fileNames.isEmpty()) {
            return
        }
        val pm = packageManager
        for (p in pm.getInstalledPackages(0)) {
            val appInfo = p.applicationInfo ?: continue
            val pkgName = p.packageName

            if (pkgName in indicators.packageIds || pkgName in indicators.processNames) {
                handleThreatDetected(pkgName, "Matches known spyware indicator (package ID)")
                continue
            }

            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystemApp) continue

            val apkPath = appInfo.sourceDir ?: continue
            val apkFileName = File(apkPath).name
            if (indicators.fileNames.contains(apkFileName)) {
                handleThreatDetected(pkgName, "Matches known spyware indicator (file name: $apkFileName)")
                continue
            }
            if (indicators.sha256Hashes.isNotEmpty()) {
                val hash = IndicatorUpdater.sha256OfFile(apkPath)
                if (hash != null && indicators.sha256Hashes.contains(hash)) {
                    handleThreatDetected(pkgName, "Matches known spyware indicator (APK hash)")
                }
            }
        }
    }

    private fun checkHiddenDangerousApps() {
        val pm = packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { it.activityInfo?.packageName }.toSet()

        for (p in pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            val appInfo = p.applicationInfo ?: continue
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystemApp) continue
            if (p.packageName in launchable) continue

            val requested = p.requestedPermissions ?: continue
            val flags = p.requestedPermissionsFlags ?: continue
            var hasDangerous = false
            for (i in requested.indices) {
                if (i >= flags.size) continue
                if (requested[i] in DANGEROUS_PERMS && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                    hasDangerous = true
                    break
                }
            }
            if (hasDangerous) {
                handleThreatDetected(p.packageName, "Hidden app (no launcher icon) holding sensitive permissions")
            }
        }
    }

    private fun isDecided(pkg: String): Boolean {
        val decisions = getSharedPreferences(GuardianPrefs.DECISIONS_FILE, Context.MODE_PRIVATE)
        return decisions.getStringSet(GuardianPrefs.KEY_DECIDED, emptySet())?.contains(pkg) == true
    }

    private fun handleThreatDetected(pkg: String, reason: String) {
        if (isDecided(pkg)) return

        AlertLog.write(this, "CRITICAL", "THREAT: $pkg -> $reason")

        val sentryOn = getSharedPreferences(GuardianPrefs.SETTINGS_FILE, Context.MODE_PRIVATE)
            .getBoolean(GuardianPrefs.KEY_SENTRY_MODE, false)

        val decisions = getSharedPreferences(GuardianPrefs.DECISIONS_FILE, Context.MODE_PRIVATE)

        if (sentryOn) {
            AlertActionReceiver.markDecided(decisions, pkg)
            AlertLog.write(this, "CRITICAL", "Sentry Mode: auto-launching removal for $pkg")
            launchUninstall(pkg)
            notifyInfo("Guardian Sentry Mode", "Requested removal of $pkg ($reason). Confirm in the system prompt.")
        } else {
            notifyKeepRemove(pkg, reason)
        }
    }

    private fun launchUninstall(pkg: String) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    private fun notifyKeepRemove(pkg: String, reason: String) {
        val decisions = getSharedPreferences(GuardianPrefs.DECISIONS_FILE, Context.MODE_PRIVATE)
        AlertActionReceiver.markDecided(decisions, pkg)

        val keepIntent = Intent(this, AlertActionReceiver::class.java).apply {
            action = AlertActionReceiver.ACTION_KEEP
            putExtra(AlertActionReceiver.EXTRA_PACKAGE, pkg)
        }
        val removeIntent = Intent(this, AlertActionReceiver::class.java).apply {
            action = AlertActionReceiver.ACTION_REMOVE
            putExtra(AlertActionReceiver.EXTRA_PACKAGE, pkg)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val keepPending = PendingIntent.getBroadcast(this, pkg.hashCode(), keepIntent, flags)
        val removePending = PendingIntent.getBroadcast(this, pkg.hashCode() + 1, removeIntent, flags)

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Guardian: suspicious app found")
            .setContentText("$pkg - $reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$pkg - $reason"))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .addAction(0, "Keep", keepPending)
            .addAction(0, "Remove", removePending)
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(pkg.hashCode(), notif)
    }

    // ---- notification plumbing ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Guardian Monitor", NotificationManager.IMPORTANCE_DEFAULT)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Guardian is watching")
            .setContentText("Monitoring for new apps, permission and accessibility changes")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
    }

    private fun notifyInfo(title: String, text: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify((System.currentTimeMillis() % 100000).toInt(), notif)
    }
}
