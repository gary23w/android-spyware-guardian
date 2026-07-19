package com.gary.guardian

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        startMonitoring()
        refreshLog()
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
