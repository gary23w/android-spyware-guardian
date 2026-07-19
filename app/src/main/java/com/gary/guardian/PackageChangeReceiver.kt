package com.gary.guardian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkgName = intent.data?.schemeSpecificPart ?: return
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (replacing) {
                    AlertLog.write(context, "INFO", "Package updated: $pkgName")
                } else {
                    val dangerous = grantedDangerousPerms(context, pkgName)
                    AlertLog.write(context, "HIGH", "NEW APP INSTALLED: $pkgName dangerousPerms=$dangerous")
                }
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                if (!replacing) {
                    AlertLog.write(context, "INFO", "Package removed: $pkgName")
                }
            }
        }

        val svcIntent = Intent(context, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svcIntent)
        } else {
            context.startService(svcIntent)
        }
    }

    private fun grantedDangerousPerms(context: Context, pkgName: String): List<String> {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(pkgName, PackageManager.GET_PERMISSIONS)
            val requested = info.requestedPermissions ?: return emptyList()
            val flags = info.requestedPermissionsFlags ?: return emptyList()
            val list = mutableListOf<String>()
            for (i in requested.indices) {
                if (i >= flags.size) continue
                if (requested[i] in MonitorService.DANGEROUS_PERMS &&
                    (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                ) {
                    list.add(requested[i])
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
