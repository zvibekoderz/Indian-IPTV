package com.indian.iptv.addonserver

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup screen for the local add-on server. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var repoSummary: TextView
    private lateinit var autoButton: Button
    private lateinit var startButton: Button
    private lateinit var repoList: LinearLayout
    private lateinit var upstreamList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 48)
        }
        status = label("", 20f)
        repoSummary = label("", 14f)
        root.addView(label("CNC Add-on Server", 28f))
        root.addView(status)
        root.addView(repoSummary)

        root.addView(label("CloudStream repo JSON URLs", 18f))
        val repoInput = input("https://raw.githubusercontent.com/.../CNC.json")
        root.addView(repoInput)
        root.addView(row(
            button("Add repo") { addUrl(repoInput, Prefs.repos(this), true) },
            button("Refresh catalogs") { refreshCatalogs() },
        ))
        repoList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(repoList)

        root.addView(label("Add-on configure URLs (or manifest URLs)", 18f))
        val upstreamInput = input("Enter add-on configure or manifest URL")
        root.addView(upstreamInput)
        root.addView(button("Add add-on URL") { addUrl(upstreamInput, Prefs.upstreams(this), false) })
        upstreamList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(upstreamList)

        startButton = button("Start server") { toggleServer() }
        autoButton = button("") { toggleAutoStart() }
        root.addView(row(startButton, autoButton))
        root.addView(button("Clear dead streams") { StreamFilter.clearDead(this); render() })
        root.addView(label(
            "Paste this into Indian IPTV → Settings → Add-ons (use Local URL on this TV): " +
                ServerService.localUrl() + "  /  " + ServerService.serverUrl(this),
            14f,
        ))
        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7) }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val running = ServerService.server(this).isRunning
        val repos = ServerService.server(this).repos(this)
        val pluginCount = ServerService.server(this).plugins(this).size
        repoSummary.text = "Repos (${repos.size}) · Plugins (${pluginCount}): " +
            repos.take(6).joinToString(", ") { it.name } +
            if (repos.size > 6) " …" else ""
        status.text = buildString {
            append("Local URL: ").append(ServerService.localUrl()).append('\n')
            append("LAN URL: ").append(ServerService.serverUrl(this@MainActivity)).append('\n')
            append("Status: ").append(if (running) "RUNNING" else "stopped").append('\n')
            append("CloudStream repos: ").append(Prefs.repos(this@MainActivity).size).append('\n')
            append("Upstream add-ons: ").append(Prefs.upstreams(this@MainActivity).size).append('\n')
            append("Dead streams: ").append(StreamFilter.deadUrls(this@MainActivity).size)
        }
        startButton.text = if (running) "Stop server" else "Start server"
        autoButton.text = if (Prefs.autoStart(this)) "Auto-start: ON" else "Auto-start: OFF"

        repoList.removeAllViews()
        Prefs.repos(this).forEach { url -> repoList.addView(removableRow(url) { Prefs.setRepos(this, Prefs.repos(this) - url); render() }) }
        upstreamList.removeAllViews()
        Prefs.upstreams(this).forEach { url -> upstreamList.addView(removableRow(url) { Prefs.setUpstreams(this, Prefs.upstreams(this) - url); render() }) }
    }

    private fun addUrl(input: EditText, current: List<String>, isRepo: Boolean) {
        val value = input.text.toString().trim()
        if (value.isEmpty()) return
        if (isRepo) Prefs.setRepos(this, current + value) else Prefs.setUpstreams(this, current + value)
        input.text.clear()
        refreshCatalogs()
        render()
        toast("Added")
    }

    private fun refreshCatalogs() {
        ServerService.server(this).refresh(this)
        toast("Catalogs refreshed")
    }

    private fun toggleServer() {
        if (ServerService.server(this).isRunning) {
            stopService(Intent(this, ServerService::class.java))
            toast("Server stopped")
        } else {
            val intent = Intent(this, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            toast("Server started")
        }
        render()
    }

    private fun toggleAutoStart() {
        Prefs.setAutoStart(this, !Prefs.autoStart(this))
        render()
    }

    private fun label(text: String, size: Float = 16f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(0xFFE8E9EE.toInt())
        setPadding(0, 16, 0, 16)
    }

    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint
        setTextColor(0xFFFFFFFF.toInt())
        setHintTextColor(0xFF777A84.toInt())
        isSingleLine = true
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF2A2D35.toInt())
        setPadding(20, 16, 20, 16)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    private fun removableRow(text: String, onRemove: () -> Unit) = row(
        TextView(this).apply {
            this.text = text.take(80)
            textSize = 13f
            setTextColor(0xFFB4B7C0.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        },
        button("Remove", onRemove),
    )

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
