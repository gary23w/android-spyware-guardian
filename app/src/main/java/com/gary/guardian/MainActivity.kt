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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var sentrySwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        sentrySwitch = findViewById(R.id.sentrySwitch)

        findViewById<Button>(R.id.startButton).setOnClickListener { startMonitoring() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopMonitoring() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { refreshLog() }
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
        logView.text = AlertLog.readAll(this)
    }
}
