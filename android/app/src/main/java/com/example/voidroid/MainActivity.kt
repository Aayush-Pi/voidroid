package com.example.voidroid

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : Activity() {

    // Neutral dark palette. Contrast ratios are against BG, all WCAG AA or better.
    private val BG = Color.parseColor("#121212")
    private val TEXT_PRIMARY = Color.parseColor("#EDEDED")       // ~15:1
    private val TEXT_SECONDARY = Color.parseColor("#A3A3A3")     // ~7.5:1
    private val BTN_PRIMARY_FILL = Color.parseColor("#E5E5E5")   // text on it is BG, ~14:1
    private val BTN_SECONDARY_FILL = Color.parseColor("#1F1F1F") // text on it ~14:1
    private val BORDER = Color.parseColor("#737373")             // ~3.9:1 (non-text needs 3:1)
    private val OK = Color.parseColor("#4ADE80")                 // ~10:1
    private val WARN = Color.parseColor("#FBBF24")               // ~11:1

    private lateinit var statusView: TextView
    private lateinit var btnPermission: Button

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun buttonBg(fill: Int, strokeColor: Int? = null): RippleDrawable {
        val shape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(fill)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }
        val rippleMask = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#33808080")),
            shape,
            rippleMask
        )
    }

    private fun styleButton(btn: Button, textColor: Int, fill: Int, stroke: Int? = null) {
        btn.apply {
            isAllCaps = false
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            stateListAnimator = null
            elevation = 0f
            background = buttonBg(fill, stroke)
            minHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(
                dp(280),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // Small underlined text link with a 48dp touch target
    private fun textLink(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(TEXT_SECONDARY)
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = BG
        window.navigationBarColor = BG

        // Title
        val titleView = TextView(this).apply {
            text = "voidroid"
            textSize = 32f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.05f
            setTextColor(TEXT_PRIMARY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }

        // Subtitle
        val subtitleView = TextView(this).apply {
            text = "Android-to-Linux Continuity Bridge"
            textSize = 14f
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(48))
        }

        // Notification Access Button
        btnPermission = Button(this).apply {
            text = "Enable Notification Access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        styleButton(btnPermission, BG, BTN_PRIMARY_FILL)

        // Status line (small text, updated in onResume)
        statusView = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(28)
            }
        }

        // View on GitHub Button
        val btnGithub = Button(this).apply {
            text = "View on GitHub"
            setOnClickListener {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/Aayush-Pi/Voidroid")
                )
                startActivity(intent)
            }
        }
        styleButton(btnGithub, TEXT_PRIMARY, BTN_SECONDARY_FILL, BORDER)

        // Text links under the buttons
        val linkBattery = textLink("Battery optimization") { openBatterySettings() }
        val linkTest = textLink("Send test notification") { sendTestNotification() }
        val linkLogs = textLink("View RAM logs") { showLogsDialog() }

        val linksRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            addView(linkBattery)
            addView(linkTest)
            addView(linkLogs)
        }

        // Setup hint
        val hintView = TextView(this).apply {
            text = "Connect via USB, then run on your PC:\nadb reverse tcp:5000 tcp:5000"
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, 0)
        }

        // Root container
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            fitsSystemWindows = true
        }

        rootLayout.addView(titleView)
        rootLayout.addView(subtitleView)
        rootLayout.addView(btnPermission)
        rootLayout.addView(statusView)
        rootLayout.addView(btnGithub)
        rootLayout.addView(linksRow)
        rootLayout.addView(hintView)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(
                rootLayout,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // Stop vendor "force dark" / auto-colour algorithms from re-tinting our colours
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scroll.isForceDarkAllowed = false
            rootLayout.isForceDarkAllowed = false
            btnPermission.isForceDarkAllowed = false
            btnGithub.isForceDarkAllowed = false
        }

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.contains(packageName) == true
    }

    private fun updateStatus() {
        if (isNotificationAccessGranted()) {
            statusView.text = "Notification access: granted"
            statusView.setTextColor(OK)
            btnPermission.text = "Manage Notification Access"
        } else {
            statusView.text = "Notification access: not granted"
            statusView.setTextColor(WARN)
            btnPermission.text = "Enable Notification Access"
        }
    }

    // Opens the system list where you can set this app to "Not optimized" / "Unrestricted"
    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            // Fallback: this app's own settings page
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e2: Exception) {
                Toast.makeText(this, "Could not open battery settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Shows the in-memory circular log buffer (up to 50 recent events, no disk writes)
    private fun showLogsDialog() {
        val logView = TextView(this).apply {
            text = NotificationLogStore.dumpNewestFirst()
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(TEXT_PRIMARY)
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val scrollView = ScrollView(this).apply {
            addView(logView)
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("RAM Logs (last 50, newest first)")
            .setView(scrollView)
            .setPositiveButton("Refresh", null)
            .setNeutralButton("Copy", null)
            .setNegativeButton("Clear", null)
            .create()

        dialog.show()

        // Override button click listeners after show() so Refresh/Copy/Clear keep the dialog open
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            logView.text = NotificationLogStore.dumpNewestFirst()
        }
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            val text = NotificationLogStore.dumpNewestFirst()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("voidroid-logs", text))
            Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
        }
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
            NotificationLogStore.clear()
            logView.text = NotificationLogStore.dumpNewestFirst()
        }
    }

    // Sends a test payload through the same socket the service uses.
    // Format matches the service: pkg|appName|title|text
    private fun sendTestNotification() {
        thread {
            val ok = try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress("127.0.0.1", 5000), 2000)
                    socket.soTimeout = 2000
                    val payload = "$packageName|voidroid|Test|Hello from voidroid\u0000"
                    socket.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                    val ack = socket.getInputStream().read()
                    if (ack == 'O'.code) {
                        NotificationLogStore.log("TEST", "SENT_OK")
                        true
                    } else {
                        NotificationLogStore.log("TEST", "SOCK_ERR no_ack(read=$ack)")
                        false
                    }
                }
            } catch (e: Exception) {
                NotificationLogStore.log(
                    "TEST",
                    "SOCK_ERR ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                )
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Test sent" else "Failed: check USB and that the PC daemon is running",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
