package com.jarvis.personal

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// Level 2. This only does something when I have just confirmed an action inside Jarvis.
// It never reads chats, it only looks for the Send or Voice call button and taps it once.
class WhatsAppService : AccessibilityService() {

    enum class Kind { SEND, CALL }

    class Pending(val kind: Kind, val expect: String, val until: Long)

    companion object {
        @Volatile var pending: Pending? = null
        @Volatile var connected = false

        fun arm(kind: Kind, expect: String = "") {
            pending = Pending(kind, expect, System.currentTimeMillis() + 20_000)
        }
    }

    override fun onServiceConnected() {
        connected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        pending = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val p = pending ?: return
        if (System.currentTimeMillis() > p.until) {
            pending = null
            return
        }
        if (event?.packageName?.toString() != "com.whatsapp") return
        val root = rootInActiveWindow ?: return
        val done = when (p.kind) {
            Kind.SEND -> sendIfReady(root, p.expect)
            Kind.CALL -> tap(find(root) { it.contentDescription?.toString().equals("Voice call", true) })
        }
        if (done) pending = null
    }

    private fun sendIfReady(root: AccessibilityNodeInfo, expect: String): Boolean {
        // I only press Send when the message box holds exactly what I confirmed, never anything else
        if (expect.isBlank()) return false
        val box = find(root) { it.className?.toString() == "android.widget.EditText" } ?: return false
        val typed = box.text?.toString()?.trim() ?: return false
        if (!typed.startsWith(expect.trim().take(30))) return false
        val send = find(root) {
            it.viewIdResourceName == "com.whatsapp:id/send" || it.contentDescription?.toString().equals("Send", true)
        }
        return tap(send)
    }

    private fun tap(node: AccessibilityNodeInfo?): Boolean {
        var n = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun find(n: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (test(n)) return n
        for (i in 0 until n.childCount) {
            find(n.getChild(i), test)?.let { return it }
        }
        return null
    }
}
