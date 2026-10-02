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
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class ExpanderService : AccessibilityService() {

    private val TAG = "HUCFT"

    private var lastSelfText: String? = null
    private var lastSelfAt: Long = 0L

    private var receiverOn = false
    private var telCb: Any? = null

    private var spokenAt: Long = 0L
    private var ringing = false
    private val handler = Handler(Looper.getMainLooper())

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != "last_event") Store.load(this)
        }

    // ================= call announcement =================

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

            if (state != TelephonyManager.EXTRA_STATE_RINGING) {
                endRing()
                return
            }
            startRing(number, "بث")
        }
    }

    private fun registerTelephony() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val tm = getSystemService(TelephonyManager::class.java) ?: return
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    if (state == TelephonyManager.CALL_STATE_RINGING) startRing(null, "مستمع")
                    else endRing()
                }
            }
            tm.registerTelephonyCallback(mainExecutor, cb)
            telCb = cb
        } catch (e: Exception) {
            note("فشل تسجيل المستمع: ${e.javaClass.simpleName}")
        }
    }

    private fun startRing(number: String?, source: String) {
        if (!Store.callerSpeak) return
        if (System.currentTimeMillis() - spokenAt < 6000) return

        ringing = true

        if (!number.isNullOrBlank()) {
            note("$source: رقم من النظام")
            announceFor(number, null, source)
            return
        }

        // No number from the system — read the incoming-call screen instead.
        scheduleScan(600, source)
        scheduleScan(1400, source)
        scheduleScan(2400, source)
        scheduleScan(3600, source)
    }

    private fun scheduleScan(delay: Long, source: String) {
        handler.postDelayed({
            if (!ringing) return@postDelayed
            if (System.currentTimeMillis() - spokenAt < 6000) return@postDelayed

            val found = scanCallScreen()
            if (found != null) {
                announceFor(found.number, found.name, "$source+شاشة")
            } else if (delay >= 3600) {
                note("$source: ما لكيت اسم بالشاشة")
                speak("مكالمة واردة", "$source: نص عام")
            }
        }, delay)
    }

    private fun endRing() {
        ringing = false
        handler.removeCallbacksAndMessages(null)
        Speaker.stop()
    }

    private class Found(val name: String?, val number: String?)

    private fun announceFor(number: String?, screenName: String?, source: String) {
        if (Store.callerRespectSilent && isSilent()) {
            note("$source: صامت — ما نطق")
            return
        }

        val prefix = Store.callerPrefix.trim()
        val saved = Speaker.contactName(this, number)

        val who = when {
            !saved.isNullOrBlank() -> saved
            !screenName.isNullOrBlank() -> screenName
            Store.callerSayNumber && !number.isNullOrBlank() -> Speaker.spellNumber(number)
            else -> null
        }

        val text = if (who.isNullOrBlank()) "مكالمة واردة"
        else if (prefix.isEmpty()) who else "$prefix $who"

        speak(text, "$source: ${if (who.isNullOrBlank()) "بدون اسم" else who}")
    }

    private fun speak(text: String, logMsg: String) {
        if (Store.callerRespectSilent && isSilent()) {
            note("$logMsg — لكن الجهاز صامت")
            return
        }
        spokenAt = System.currentTimeMillis()
        note(logMsg)
        Speaker.announce(this, text, Store.callerRepeat)
    }

    // --------- read the incoming-call screen ---------

    private val uiWords = setOf(
        "مكالمة واردة", "رفض", "قبول", "رد", "تجاهل", "إسكات", "رسالة", "تذكير",
        "incoming call", "decline", "accept", "answer", "ignore", "silence",
        "message", "remind", "reject", "slide to answer", "swipe up to answer",
        "calling", "mobile", "جوال", "هاتف", "منزل", "عمل", "work", "home"
    )

    private val numberRe = Regex("^[+]?[0-9][0-9 \\-()]{5,}$")

    private fun scanCallScreen(): Found? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                roots.add(r)
            }
        } catch (_: Exception) {
        }
        try {
            rootInActiveWindow?.let { roots.add(it) }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) return null

        var idName: String? = null
        var number: String? = null
        val others = ArrayList<String>()

        for (root in roots) {
            val pkg = root.packageName?.toString() ?: ""
            val isCallUi = pkg.contains("incallui", true) || pkg.contains("dialer", true) ||
                pkg.contains("telecom", true) || pkg.contains("phone", true) ||
                pkg.contains("contacts", true)

            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var seen = 0

            while (queue.isNotEmpty() && seen < 500) {
                val n = queue.poll() ?: continue
                seen++

                val raw = (n.text ?: n.contentDescription)?.toString()?.trim()
                if (!raw.isNullOrBlank() && raw.length <= 60) {
                    val low = raw.lowercase(Locale.ROOT)
                    val isUi = uiWords.any { low == it || low.contains(it) }
                    val vid = n.viewIdResourceName ?: ""

                    if (numberRe.matches(raw)) {
                        if (number == null) number = raw
                    } else if (!isUi && raw.any { it.isLetter() }) {
                        if (vid.contains("name", true) || vid.contains("caller", true)) {
                            if (idName == null) idName = raw
                        } else if (isCallUi) {
                            others.add(raw)
                        }
                    }
                }

                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { queue.add(it) }
                }
            }
        }

        val name = idName ?: others.firstOrNull()
        if (name == null && number == null) return null
        return Found(name, number)
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

    // ================= lifecycle =================

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
            } catch (e: Exception) {
                note("فشل تسجيل البث: ${e.javaClass.simpleName}")
            }
        }

        registerTelephony()
        note("الخدمة اشتغلت")
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
        handler.removeCallbacksAndMessages(null)
        Speaker.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ================= text expansion =================

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
