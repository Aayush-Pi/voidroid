package com.example.voidroid

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Root dark container
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(64, 64, 64, 64)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Title
        val titleView = TextView(this).apply {
            text = "Voidroid"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        // Status Subtitle
        val subtitleView = TextView(this).apply {
            text = "Android-to-Linux Continuity Bridge"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }

        // Notification Access Button
        val btnPermission = Button(this).apply {
            text = "Enable Notification Access"
            setTextColor(Color.parseColor("#0F172A"))
            setBackgroundColor(Color.parseColor("#38BDF8"))
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        // Space between buttons
        val spacer = TextView(this).apply {
            setPadding(0, 0, 0, 24)
        }

        // View on GitHub Button
        val btnGithub = Button(this).apply {
            text = "View on GitHub"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setOnClickListener {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/Aayush-Pi/Voidroid")
                )
                startActivity(intent)
            }
        }

        // Assemble views
        rootLayout.addView(titleView)
        rootLayout.addView(subtitleView)
        rootLayout.addView(btnPermission)
        rootLayout.addView(spacer)
        rootLayout.addView(btnGithub)

        setContentView(rootLayout)
    }
}
