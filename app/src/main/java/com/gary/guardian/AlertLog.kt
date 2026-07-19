package com.gary.guardian

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AlertLog {
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "alerts.log")
    }

    @Synchronized
    fun write(context: Context, severity: String, message: String) {
        val line = "${sdf.format(Date())} [$severity] $message\n"
        file(context).appendText(line)
    }

    fun readAll(context: Context): String {
        val f = file(context)
        return if (f.exists()) f.readText() else "No alerts yet."
    }
}
