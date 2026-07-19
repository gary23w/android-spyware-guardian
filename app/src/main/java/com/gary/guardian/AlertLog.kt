package com.gary.guardian

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AlertLog {
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var listener: ((String) -> Unit)? = null

    fun setListener(l: ((String) -> Unit)?) {
        listener = l
    }

    fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, "alerts.log")
    }

    @Synchronized
    fun write(context: Context, severity: String, message: String) {
        val line = "${sdf.format(Date())} [$severity] $message\n"
        file(context).appendText(line)
        listener?.let { l -> mainHandler.post { l(line) } }
    }

    fun readAll(context: Context): String {
        val f = file(context)
        return if (f.exists()) f.readText() else ""
    }

    @Synchronized
    fun clear(context: Context) {
        val f = file(context)
        if (f.exists()) f.writeText("")
    }
}
