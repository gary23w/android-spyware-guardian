package com.gary.guardian

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var sentrySwitch: Switch
    private var logHasContent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        sentrySwitch = findViewById(R.id.sentrySwitch)

        findViewById<Button>(R.id.startButton).setOnClickListener { startMonitoring() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopMonitoring() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { refreshLog() }
        findViewById<Button>(R.id.exportLogButton).setOnClickListener { exportLog() }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener { clearLog() }
        findViewById<Button>(R.id.usageAccessButton).setOnClickListener { openUsageAccessSettings() }
        findViewById<Button>(R.id.checklistButton).setOnClickListener {
            startActivity(Intent(this, ChecklistActivity::class.java))
        }
        findViewById<Button>(R.id.flaggedAppsButton).setOnClickListener {
            startActivity(Intent(this, FlaggedAppsActivity::class.java))
        }
        findViewById<Button>(R.id.allowUpdatesButton).setOnClickListener { openInstallUnknownAppsSettings() }

        val settings = getSharedPreferences(GuardianPrefs.SETTINGS_FILE, Context.MODE_PRIVATE)
        sentrySwitch.isChecked = settings.getBoolean(GuardianPrefs.KEY_SENTRY_MODE, false)
        sentrySwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.edit().putBoolean(GuardianPrefs.KEY_SENTRY_MODE, isChecked).apply()
            val msg = if (isChecked) {
                "Sentry Mode ON: flagged apps will have removal auto-requested"
            } else {
                "Sentry Mode OFF: you'll be asked before anything is removed"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        requestRuntimePermissions()
        startMonitoring()
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
        AlertLog.setListener { line -> appendLiveLine(line) }
    }

    override fun onPause() {
        super.onPause()
        AlertLog.setListener(null)
    }

    private fun appendLiveLine(line: String) {
        if (!logHasContent) {
            logView.text = ""
            logHasContent = true
        }
        logView.append(line)
        logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
        if (!hasUsageAccess()) {
            Toast.makeText(
                this,
                "Tap 'Usage Access' below to let Guardian watch for network usage anomalies",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun openUsageAccessSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openInstallUnknownAppsSettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    }

    private fun startMonitoring() {
        val intent = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopMonitoring() {
        stopService(Intent(this, MonitorService::class.java))
    }

    private fun refreshLog() {
        val content = AlertLog.readAll(this)
        logHasContent = content.isNotEmpty()
        logView.text = if (logHasContent) content else "No alerts yet."
        logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun exportLog() {
        val file = AlertLog.file(this)
        if (!file.exists()) {
            Toast.makeText(this, "Nothing to export yet", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export Guardian log"))
    }

    private fun clearLog() {
        AlertLog.clear(this)
        refreshLog()
        AlertLog.write(this, "INFO", "Log cleared by user")
    }
}
