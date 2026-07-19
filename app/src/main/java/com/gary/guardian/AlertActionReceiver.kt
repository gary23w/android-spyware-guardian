package com.gary.guardian

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri

class AlertActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_KEEP = "com.gary.guardian.ACTION_KEEP"
        const val ACTION_REMOVE = "com.gary.guardian.ACTION_REMOVE"
        const val EXTRA_PACKAGE = "extra_package"

        fun markDecided(prefs: SharedPreferences, pkg: String) {
            val set = HashSet(prefs.getStringSet(GuardianPrefs.KEY_DECIDED, emptySet()) ?: emptySet())
            set.add(pkg)
            prefs.edit().putStringSet(GuardianPrefs.KEY_DECIDED, set).apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.cancel(pkg.hashCode())

        val decisions = context.getSharedPreferences(GuardianPrefs.DECISIONS_FILE, Context.MODE_PRIVATE)
        markDecided(decisions, pkg)

        when (intent.action) {
            ACTION_KEEP -> {
                AlertLog.write(context, "INFO", "User chose KEEP for $pkg")
                FlaggedApps.setStatus(context, pkg, FlagStatus.KEPT)
            }
            ACTION_REMOVE -> {
                AlertLog.write(context, "INFO", "User chose REMOVE for $pkg, launching uninstall")
                FlaggedApps.setStatus(context, pkg, FlagStatus.REMOVAL_REQUESTED)
                val uninstall = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                uninstall.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(uninstall)
            }
        }
    }
}
