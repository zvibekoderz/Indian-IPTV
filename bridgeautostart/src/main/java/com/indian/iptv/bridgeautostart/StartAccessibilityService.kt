package com.indian.iptv.bridgeautostart

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Presses Bridge's own "Start Server" button using only the standard Android
 * accessibility tree — no Bridge code, assets, reflection or private APIs.
 * After the click it sends HOME so Bridge's UI never stays on screen.
 */
class StartAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastActionAt = 0L
    private var homePosted = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != Bridge.PACKAGE) return
        val now = System.currentTimeMillis()
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return

        val root = rootInActiveWindow ?: return
        val startNode = findStartButton(root)
        if (startNode != null) {
            val target = clickableAncestor(startNode) ?: startNode
            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "Pressed Bridge Start Server button (clicked=$clicked)")
            if (clicked) {
                lastActionAt = now
                postHome()
            }
        } else if (findText(root, "server is running") || findText(root, "server running")) {
            // Already running — just hide Bridge's UI.
            lastActionAt = now
            postHome()
        }
    }

    private fun findStartButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = listOfNotNull(node.text, node.contentDescription)
            .joinToString(" ")
            .trim()
            .lowercase()
        if (label.contains("start") && label.contains("server")) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findStartButton(child)
            if (found != null) return found
        }
        return null
    }

    private fun findText(node: AccessibilityNodeInfo, needle: String): Boolean {
        val label = listOfNotNull(node.text, node.contentDescription)
            .joinToString(" ").lowercase()
        if (label.contains(needle)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findText(child, needle)) return true
        }
        return false
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops++ < 8) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
        }
        return null
    }

    private fun postHome() {
        if (homePosted) return
        homePosted = true
        handler.postDelayed({
            runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
                .onFailure { Log.w(TAG, "Could not return home", it) }
            homePosted = false
        }, HOME_DELAY_MS)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    private companion object {
        const val TAG = "BridgeStartA11y"
        const val ACTION_DEBOUNCE_MS = 10_000L
        const val HOME_DELAY_MS = 2_500L
    }
}
