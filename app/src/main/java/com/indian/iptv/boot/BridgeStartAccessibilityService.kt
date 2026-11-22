package com.indian.iptv.boot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Presses CNCVerse Bridge's own "Start Server" button after our boot receiver
 * opens Bridge's UI.
 *
 * This deliberately does not modify, embed or reflect into Bridge. It only
 * reads the standard Android accessibility tree and performs a normal click on
 * a visible button, exactly as a person using the remote would. Bridge stays a
 * completely unmodified, separately installed app.
 */
class BridgeStartAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastClickAt = 0L
    private var returnHomePosted = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        Log.i(TAG, "Bridge auto-start accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != BRIDGE_PACKAGE) return

        val root = rootInActiveWindow ?: return
        val startNode = findStartButton(root) ?: return
        val clickTarget = findClickableAncestor(startNode) ?: startNode

        val now = System.currentTimeMillis()
        if (now - lastClickAt < CLICK_DEBOUNCE_MS) return
        lastClickAt = now

        val clicked = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "Pressed Bridge Start Server button (clicked=$clicked)")

        root.recycle()
        if (clicked && !returnHomePosted) {
            returnHomePosted = true
            handler.postDelayed({ openOurApp() }, RETURN_HOME_DELAY_MS)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Bridge auto-start accessibility service interrupted")
    }

    private fun findStartButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim()
        if (label.equals("Start Server", ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findStartButton(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops++ < MAX_ANCESTOR_HOPS) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
        }
        return null
    }

    private fun openOurApp() {
        runCatching {
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launch)
            }
            Log.i(TAG, "Returned to IPTV app after starting Bridge")
        }.onFailure { Log.w(TAG, "Could not return to IPTV app", it) }
        returnHomePosted = false
    }

    private companion object {
        const val TAG = "BridgeStartA11y"
        const val BRIDGE_PACKAGE = "com.cncverse.stremiobridge"
        const val CLICK_DEBOUNCE_MS = 8_000L
        const val RETURN_HOME_DELAY_MS = 2_500L
        const val MAX_ANCESTOR_HOPS = 8
    }
}
