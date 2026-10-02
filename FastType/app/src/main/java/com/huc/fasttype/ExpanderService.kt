package com.huc.fasttype

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ExpanderService : AccessibilityService() {

    private var lastSelfText: String? = null
    private var lastSelfAt: Long = 0L

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> Store.load(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.load(this)
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onDestroy() {
        try {
            Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (!Store.enabled || Store.ordered.isEmpty()) return

        val node: AccessibilityNodeInfo = event.source ?: return
        if (!node.isEditable || node.isPassword) return

        val text = node.text?.toString() ?: return
        if (text.isEmpty()) return

        if (text == lastSelfText && System.currentTimeMillis() - lastSelfAt < 2000) return

        var cursor = node.textSelectionEnd
        if (cursor < 0 || cursor > text.length) cursor = text.length
        if (cursor == 0) return

        val typed = text.substring(0, cursor)
        val hit = findMatch(typed) ?: return

        val newText = text.substring(0, hit.start) + hit.replacement + text.substring(cursor)
        val newCursor = hit.start + hit.replacement.length

        lastSelfText = newText
        lastSelfAt = System.currentTimeMillis()

        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            newText
        )
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return

        val sel = Bundle()
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    private class Hit(val start: Int, val replacement: String)

    private fun findMatch(typed: String): Hit? {
        var body = typed
        var tail = ""

        if (!Store.instant) {
            val last = typed.last()
            if (!isTriggerChar(last)) return null
            body = typed.dropLast(1)
            tail = last.toString()
            if (body.isEmpty()) return null
        }

        for (s in Store.ordered) {
            if (!body.endsWith(s.trigger)) continue
            val start = body.length - s.trigger.length
            if (start > 0 && !isBoundary(body[start - 1])) continue
            return Hit(start, s.phrase + tail)
        }
        return null
    }

    private fun isTriggerChar(c: Char): Boolean =
        c == ' ' || c == '\n' || c == '\t' || c == '.' || c == ',' || c == '!' ||
            c == '?' || c == '؟' || c == '،' || c == ':' || c == ';' || c == '-'

    private fun isBoundary(c: Char): Boolean = !c.isLetterOrDigit() && c != '_'
}
