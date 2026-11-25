package com.indian.iptv.bridgeautostart

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** One-time setup: enable the accessibility service, then auto-start needs no interaction. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)

        val padding = 48
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(padding, padding, padding, padding)
        }
        val heading = TextView(this).apply {
            text = "CNCVerse Bridge — automatic server start"
            textSize = 26f
            setTextColor(0xFFF4F4F6.toInt())
            setPadding(0, 0, 0, 32)
        }
        status = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFFB4B7C0.toInt())
            setPadding(0, 0, 0, 40)
        }
        val a11y = button("1. Enable auto-start service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val test = button("2. Test: start Bridge now") {
            if (!Bridge.isInstalled(this)) {
                toast("CNCVerse Bridge is not installed")
            } else {
                startForegroundService(Intent(this, LaunchService::class.java))
                toast("Starting Bridge…")
            }
        }
        toggle = button("") { toggleAutoStart() }
        val note = TextView(this).apply {
            text = "Bridge stays an unmodified, separately installed app. " +
                "The server is started by pressing Bridge's own Start Server button " +
                "through Android accessibility — no Bridge code is embedded here."
            textSize = 14f
            setTextColor(0xFF80838C.toInt())
            setPadding(0, 48, 0, 0)
        }
        container.addView(heading)
        container.addView(status)
        container.addView(a11y)
        container.addView(test)
        container.addView(toggle)
        container.addView(note)
        setContentView(ScrollView(this).apply { addView(container) })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val installed = Bridge.isInstalled(this)
        val a11yOn = isAccessibilityEnabled()
        val enabled = Prefs.enabled(this)
        status.text = buildString {
            append("Bridge installed: ").append(if (installed) "yes" else "NO").append('\n')
            append("Auto-start service enabled: ").append(if (a11yOn) "yes" else "NO — tap button 1").append('\n')
            append("Auto-start after boot: ").append(if (enabled) "on" else "off")
        }
        toggle.text = if (enabled) "Turn auto-start OFF" else "Turn auto-start ON"
    }

    private fun toggleAutoStart() {
        val next = !Prefs.enabled(this)
        Prefs.setEnabled(this, next)
        toast(if (next) "Auto-start enabled" else "Auto-start disabled")
        refresh()
    }

    private fun isAccessibilityEnabled(): Boolean = runCatching {
        val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        val services = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        enabled && services.split(':').any {
            it.contains(packageName) && it.contains("StartAccessibilityService")
        }
    }.getOrDefault(false)

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF2A2D35.toInt())
        setPadding(24, 20, 24, 20)
        setOnClickListener { onClick() }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
