package com.huc.fasttype

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExpanderService : AccessibilityService() {

    private val TAG = "HUCFT"

    private var lastSelfText: String? = null
    private var lastSelfAt: Long = 0L

    private var receiverOn = false
    private var telCb: Any? = null

    private var spokenAt: Long = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != "last_event") Store.load(this)
        }

    // ---------- call announcement ----------

    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: "?"
            val number = try {
                intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            } catch (e: Exception) {
                null
            }

            note("بث: $state | رقم: ${if (number.isNullOrBlank()) "لا" else "نعم"}")

            if (state != TelephonyManager.EXTRA_STATE_RINGING) {
                Speaker.stop()
                return
            }
            onRinging(number, "بث")
        }
    }

    private fun registerTelephony() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val tm = getSystemService(TelephonyManager::class.java) ?: return
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING -> {
                            note("مستمع: رنين")
                            handler.postDelayed({
                                if (System.currentTimeMillis() - spokenAt > 2500) {
                                    onRinging(null, "مستمع")
                                }
                            }, 1300)
                        }

                        else -> Speaker.stop()
                    }
                }
            }
            tm.registerTelephonyCallback(mainExecutor, cb)
            telCb = cb
            note("المستمع مسجّل")
        } catch (e: Exception) {
            note("فشل تسجيل المستمع: ${e.javaClass.simpleName}")
        }
    }

    private fun onRinging(number: String?, source: String) {
        if (!Store.callerSpeak) {
            note("$source: الميزة مطفية")
            return
        }

        val now = System.currentTimeMillis()
        if (now - spokenAt < 2500) {
            note("$source: تجاهل (مكرر)")
            return
        }

        if (Store.callerRespectSilent && isSilent()) {
            note("$source: صامت — ما نطق")
            return
        }

        val text = Speaker.buildAnnouncement(this, number)
        spokenAt = now
        note("$source: ينطق → $text")
        Speaker.announce(this, text, Store.callerRepeat)
    }

    private fun isSilent(): Boolean {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode != AudioManager.RINGER_MODE_NORMAL
        } catch (_: Exception) {
            false
        }
    }

    private fun note(msg: String) {
        Log.d(TAG, msg)
        try {
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            Store.setLastEvent(this, "$t — $msg")
        } catch (_: Exception) {
        }
    }

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.load(this)
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)

        if (!receiverOn) {
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(callReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(callReceiver, filter)
                }
                receiverOn = true
                note("الخدمة اشتغلت — البث مسجّل")
            } catch (e: Exception) {
                note("فشل تسجيل البث: ${e.javaClass.simpleName}")
            }
        }

        registerTelephony()
    }

    override fun onDestroy() {
        try {
            Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Exception) {
        }
        if (receiverOn) {
            try {
                unregisterReceiver(callReceiver)
            } catch (_: Exception) {
            }
            receiverOn = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val tm = getSystemService(TelephonyManager::class.java)
                (telCb as? TelephonyCallback)?.let { tm?.unregisterTelephonyCallback(it) }
            } catch (_: Exception) {
            }
        }
        telCb = null
        Speaker.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------- text expansion ----------

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
