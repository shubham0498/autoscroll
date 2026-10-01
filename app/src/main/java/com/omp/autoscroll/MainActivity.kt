package com.omp.autoscroll

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }

        root.addView(text("AutoScroll for Reels & Shorts", 22f, true))
        status = text("", 16f, true)
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        val sec = prefs.getInt(Prefs.KEY_FALLBACK_SEC, Prefs.DEFAULT_FALLBACK_SEC)
        val label = text("Timer mode: swipe after $sec sec", 15f, true)
        root.addView(label)
        root.addView(SeekBar(this).apply {
            max = 85                      // 5..90 seconds
            progress = sec - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + 5
                    label.text = "Timer mode: swipe after $v sec"
                    prefs.edit().putInt(Prefs.KEY_FALLBACK_SEC, v).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        root.addView(text(
            "How it works\n" +
            "• Open Instagram Reels or YouTube Shorts — a floating button appears.\n" +
            "• Tap it to turn auto-scroll ON / OFF. Long-press = skip now. Drag to move.\n" +
            "• If the app shows a progress bar, it swipes exactly when the video ends.\n" +
            "• Otherwise it uses the timer above. Swiping manually resets the timer.\n" +
            "• It switches itself OFF when you leave Instagram/YouTube.\n\n" +
            "Can't enable it? (Android 13+) Go to Settings → Apps → AutoScroll → ⋮ menu → " +
            "\"Allow restricted settings\", then try again."
        ))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("$packageName/") == true
        status.text = if (enabled) "✅ Service is ON" else "❌ Service is OFF — enable it below"
    }
}
