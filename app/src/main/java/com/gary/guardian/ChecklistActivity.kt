package com.gary.guardian

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ChecklistActivity : AppCompatActivity() {

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
        title = "Guardian Settings Walkthrough"

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        val pad = (12 * resources.displayMetrics.density).toInt()

        for (item in SecurityChecklist.items) {
            val result = item.check(this)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(Color.parseColor("#1A000000"))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, pad)
                layoutParams = lp
            }

            val statusIcon = when (result.status) {
                CheckStatus.SAFE -> "✅"
                CheckStatus.WARNING -> "⚠️"
                CheckStatus.INFO -> "ℹ️"
            }

            val titleView = TextView(this).apply {
                text = "$statusIcon ${item.title}"
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            card.addView(titleView)

            val explanationView = TextView(this).apply {
                text = item.explanation
                textSize = 13f
                setPadding(0, pad / 4, 0, pad / 4)
            }
            card.addView(explanationView)

            val detailView = TextView(this).apply {
                text = result.detail
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.ITALIC)
            }
            card.addView(detailView)

            if (item.fixSettingsAction != null && result.status != CheckStatus.SAFE) {
                val fixButton = Button(this).apply {
                    text = "Open setting"
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.gravity = Gravity.START; it.topMargin = pad / 2 }
                    setOnClickListener { openSetting(item.fixSettingsAction) }
                }
                card.addView(fixButton)
            }

            container.addView(card)
        }
    }

    private fun openSetting(action: String) {
        val attempts = listOf(
            Intent(action),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                continue
            }
        }
        Toast.makeText(this, "Couldn't open that settings screen on this device", Toast.LENGTH_LONG).show()
    }
}
