package com.gary.guardian

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class FlaggedAppsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(container)
        setContentView(scroll)
        title = "Flagged Apps"

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        val pad = (12 * resources.displayMetrics.density).toInt()
        val entries = FlaggedApps.getAll(this)

        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Nothing flagged yet. This list fills in as Guardian finds things worth reviewing."
                textSize = 14f
            })
            return
        }

        for (entry in entries) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(Color.parseColor("#1A000000"))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, pad)
                layoutParams = lp
            }

            val statusText = when (entry.status) {
                FlagStatus.PENDING -> "⚠️ Needs review"
                FlagStatus.KEPT -> "✅ Kept"
                FlagStatus.REMOVAL_REQUESTED -> "🗑️ Removal requested"
            }

            card.addView(TextView(this).apply {
                text = entry.packageName
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = entry.reason
                textSize = 13f
                setPadding(0, pad / 4, 0, pad / 4)
            })
            card.addView(TextView(this).apply {
                text = statusText
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.ITALIC)
            })

            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = pad / 2
                layoutParams = lp
            }
            buttonRow.addView(Button(this).apply {
                text = "Keep"
                setOnClickListener {
                    FlaggedApps.setStatus(this@FlaggedAppsActivity, entry.packageName, FlagStatus.KEPT)
                    render()
                }
            })
            buttonRow.addView(Button(this).apply {
                text = "Remove"
                setOnClickListener {
                    FlaggedApps.setStatus(this@FlaggedAppsActivity, entry.packageName, FlagStatus.REMOVAL_REQUESTED)
                    launchUninstall(entry.packageName)
                }
            })
            buttonRow.addView(Button(this).apply {
                text = "App Info"
                setOnClickListener { openAppInfo(entry.packageName) }
            })
            card.addView(buttonRow)

            container.addView(card)
        }
    }

    private fun launchUninstall(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Couldn't open the uninstall prompt, try App Info instead", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppInfo(pkg: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Couldn't open App Info on this device", Toast.LENGTH_LONG).show()
        }
    }
}
