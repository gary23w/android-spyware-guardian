package com.gary.guardian

import android.Manifest
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
            checkVersionDowngrades()
            checkIocMatches()
            checkHiddenDangerousApps()
            checkSecuritySettings()
            checkInterceptionReceivers()
            checkDefaultRoles()
            checkSimState()
            checkRootIndicators()
            checkNetworkAnomalies()
            checkPatchStaleness()
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

    private fun checkVersionDowngrades() {
        val pm = packageManager
        val stored = prefs.getStringSet("package_versions", emptySet()) ?: emptySet()
        val prevMap = HashMap<String, Long>()
        for (entry in stored) {
            val idx = entry.lastIndexOf(':')
            if (idx < 0) continue
            prevMap[entry.substring(0, idx)] = entry.substring(idx + 1).toLongOrNull() ?: 0L
        }
        val newSet = mutableSetOf<String>()
        for (p in pm.getInstalledPackages(0)) {
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode else p.versionCode.toLong()
            newSet.add("${p.packageName}:$versionCode")
            val prev = prevMap[p.packageName]
            if (prev != null && versionCode < prev) {
                AlertLog.write(this, "HIGH", "Version downgrade: ${p.packageName} $prev -> $versionCode")
                notifyInfo("Guardian Alert", "${p.packageName} was downgraded ($prev -> $versionCode)")
            }
        }
        prefs.edit().putStringSet("package_versions", newSet).apply()
    }

    // ---- MVT-derived checks: settings tampering, interception, root, SIM ----

    private val WATCHED_SECURITY_SETTINGS = listOf(
        "global" to "package_verifier_enable",
        "global" to "package_verifier_state",
        "global" to "upload_apk_enable",
        "global" to "verifier_verify_adb_installs",
        "global" to "adb_install_need_confirm",
        "secure" to "package_verifier_user_consent",
        "secure" to "install_non_market_apps"
    )

    private fun checkSecuritySettings() {
        val snapshot = mutableSetOf<String>()
        for ((scope, key) in WATCHED_SECURITY_SETTINGS) {
            val value = if (scope == "global") {
                Settings.Global.getString(contentResolver, key)
            } else {
                Settings.Secure.getString(contentResolver, key)
            }
            snapshot.add("$key=$value")
        }
        diffAndStore("security_settings", snapshot, "Security setting")
    }

    private val INTERCEPTION_ACTIONS = listOf(
        "android.provider.Telephony.SMS_RECEIVED",
        "android.provider.Telephony.SMS_DELIVER",
        "android.provider.Telephony.DATA_SMS_RECEIVED",
        "android.intent.action.NEW_OUTGOING_CALL",
        "android.intent.action.PHONE_STATE"
    )

    private fun checkInterceptionReceivers() {
        val pm = packageManager
        val defaultSms = try { Telephony.Sms.getDefaultSmsPackage(this) } catch (e: Exception) { null }
        val defaultDialer = try {
            (getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        } catch (e: Exception) { null }

        val flagged = mutableSetOf<String>()
        for (action in INTERCEPTION_ACTIONS) {
            val receivers = try { pm.queryBroadcastReceivers(Intent(action), 0) } catch (e: Exception) { emptyList() }
            for (r in receivers) {
                val pkg = r.activityInfo?.packageName ?: continue
                if (pkg == packageName || pkg == defaultSms || pkg == defaultDialer) continue
                val appInfo = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { null } ?: continue
                if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
                flagged.add("$pkg:$action")
            }
        }
        diffAndStore("interception_receivers", flagged, "SMS/call interception receiver")
    }

    private fun checkDefaultRoles() {
        val defaultSms = try { Telephony.Sms.getDefaultSmsPackage(this) } catch (e: Exception) { null } ?: "none"
        val defaultDialer = try {
            (getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        } catch (e: Exception) { null } ?: "none"
        diffAndStore("default_sms_app", setOf(defaultSms), "Default SMS app")
        diffAndStore("default_dialer_app", setOf(defaultDialer), "Default dialer app")
    }

    private fun checkSimState() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val snapshot = setOf(
                "operator=${tm.simOperatorName}",
                "country=${tm.simCountryIso}",
                "phoneType=${tm.phoneType}",
                "simState=${tm.simState}"
            )
            diffAndStore("sim_state", snapshot, "SIM/carrier state")
        } catch (e: Exception) {
            AlertLog.write(this, "ERROR", "SIM state check failed: ${e.message}")
        }
    }

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su",
        "/su/bin/su", "/data/local/xbin/su", "/data/local/bin/su", "/system/bin/.ext/.su"
    )
    private val ROOT_PACKAGES = listOf(
        "com.topjohnwu.magisk", "eu.chainfire.supersu", "com.noshufou.android.su",
        "com.koushikdutta.superuser", "com.kingroot.kinguser", "com.kingo.root"
    )

    private fun checkRootIndicators() {
        val indicators = mutableSetOf<String>()
        if (Build.TAGS?.contains("test-keys") == true) indicators.add("build-tags-test-keys")
        for (path in SU_PATHS) {
            if (File(path).exists()) indicators.add("su-binary:$path")
        }
        val pm = packageManager
        for (pkg in ROOT_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                indicators.add("root-app:$pkg")
            } catch (e: PackageManager.NameNotFoundException) {
            }
        }
        diffAndStore("root_indicators", indicators, "Root indicator")
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun bytesForUid(nsm: NetworkStatsManager, networkType: Int, uid: Int, start: Long, end: Long): Long {
        return try {
            val bucket = android.app.usage.NetworkStats.Bucket()
            var total = 0L
            val stats = nsm.queryDetailsForUid(networkType, null, start, end, uid)
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                total += bucket.rxBytes + bucket.txBytes
            }
            stats.close()
            total
        } catch (e: Exception) {
            0L
        }
    }

    private fun checkNetworkAnomalies() {
        if (!hasUsageAccess()) return
        val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return
        val pm = packageManager
        val now = System.currentTimeMillis()
        val dayAgo = now - 24L * 60 * 60 * 1000

        val prevRaw = prefs.getStringSet("net_usage_baseline", emptySet()) ?: emptySet()
        val prevMap = HashMap<String, Long>()
        for (entry in prevRaw) {
            val idx = entry.lastIndexOf(':')
            if (idx < 0) continue
            prevMap[entry.substring(0, idx)] = entry.substring(idx + 1).toLongOrNull() ?: 0L
        }

        val newRaw = mutableSetOf<String>()
        for (p in pm.getInstalledPackages(0)) {
            val appInfo = p.applicationInfo ?: continue
            val uid = appInfo.uid
            val wifi = bytesForUid(nsm, ConnectivityManager.TYPE_WIFI, uid, dayAgo, now)
            val mobile = bytesForUid(nsm, ConnectivityManager.TYPE_MOBILE, uid, dayAgo, now)
            val total = wifi + mobile
            newRaw.add("${p.packageName}:$total")

            val prev = prevMap[p.packageName]
            if (prev != null && prev > 200_000L && total > prev * 5 && total > 5_000_000L) {
                AlertLog.write(this, "HIGH", "Network usage spike: ${p.packageName} ${prev / 1024}KB -> ${total / 1024}KB/24h")
                notifyInfo("Guardian Alert", "${p.packageName} network usage jumped sharply in the last 24h")
            }
        }
        prefs.edit().putStringSet("net_usage_baseline", newRaw).apply()
    }

    private fun checkPatchStaleness() {
        try {
            val patchDateStr = Build.VERSION.SECURITY_PATCH
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val patchDate = sdf.parse(patchDateStr) ?: return
            val daysStale = (System.currentTimeMillis() - patchDate.time) / (1000L * 60 * 60 * 24)
            val thresholdDays = 90L
            val lastAlertKey = "patch_staleness_last_alert"
            val lastAlert = prefs.getLong(lastAlertKey, 0L)
            val dayInMs = 24L * 60 * 60 * 1000
            if (daysStale > thresholdDays && System.currentTimeMillis() - lastAlert > dayInMs) {
                AlertLog.write(this, "HIGH", "Security patch is $daysStale days old ($patchDateStr)")
                notifyInfo("Guardian Alert", "Security patch is $daysStale days old, check Settings for a system update")
                prefs.edit().putLong(lastAlertKey, System.currentTimeMillis()).apply()
            }
        } catch (e: Exception) {
            AlertLog.write(this, "ERROR", "Patch staleness check failed: ${e.message}")
        }
    }

    private fun isDecided(pkg: String): Boolean {
        val decisions = getSharedPreferences(GuardianPrefs.DECISIONS_FILE, Context.MODE_PRIVATE)
        return decisions.getStringSet(GuardianPrefs.KEY_DECIDED, emptySet())?.contains(pkg) == true
    }

    private fun handleThreatDetected(pkg: String, reason: String) {
        FlaggedApps.record(this, pkg, reason)
        if (isDecided(pkg)) return

        AlertLog.write(this, "CRITICAL", "THREAT: $pkg -> $reason")

        val sentryOn = getSharedPreferences(GuardianPrefs.SETTINGS_FILE, Context.MODE_PRIVATE)
            .getBoolean(GuardianPrefs.KEY_SENTRY_MODE, false)

        val decisions = getSharedPreferences(GuardianPrefs.DECISIONS_FILE, Context.MODE_PRIVATE)

        if (sentryOn) {
            AlertActionReceiver.markDecided(decisions, pkg)
            FlaggedApps.setStatus(this, pkg, FlagStatus.REMOVAL_REQUESTED)
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
