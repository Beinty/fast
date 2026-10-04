package com.huc.fasttype

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
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
            if (key == "last_event") return@OnSharedPreferenceChangeListener
            Store.load(this)
            // the switch in settings takes effect without him restarting anything
            if (Store.recHush) startMicWatch() else stopMicWatch()
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
                speak(listOf(Phon.Part("مكالمة واردة", true)), "$source: نص عام")
            }
        }, delay)
    }

    private fun endRing() {
        ringing = false
        handler.removeCallbacksAndMessages(null)
        Speaker.stop()
        // An utterance handed over a moment ago may only reach the speaker now, and
        // stop() cannot cancel what has not started. Two more sweeps catch it.
        handler.postDelayed({ if (!ringing) Speaker.stop() }, 260)
        handler.postDelayed({ if (!ringing) Speaker.stop() }, 900)
    }

    private class Found(val name: String?, val number: String?)

    private fun announceFor(number: String?, screenName: String?, source: String) {
        if (Store.callerRespectSilent && isSilent()) {
            note("$source: صامت — ما نطق")
            return
        }

        // the first announcement is also the first chance to settle on a voice
        Speaker.autoPickVoice(this)

        val prefix = Store.callerPrefix.trim()
        val saved = Speaker.contactName(this, number)

        val who = when {
            !saved.isNullOrBlank() -> saved
            !screenName.isNullOrBlank() -> screenName
            Store.callerSayNumber && !number.isNullOrBlank() -> Speaker.spellNumber(number)
            else -> null
        }

        if (who.isNullOrBlank()) {
            speak(listOf(Phon.Part("مكالمة واردة", true)), "$source: بدون اسم")
            return
        }

        // A number is already digits with spaces between them; only a name needs
        // working out how it should sound.
        val isNumber = saved.isNullOrBlank() && screenName.isNullOrBlank()
        val parts = ArrayList<Phon.Part>(4)
        if (prefix.isNotEmpty()) parts.add(Phon.Part(prefix, true))
        if (isNumber) parts.add(Phon.Part(who, true))
        else parts.addAll(Phon.parts(who, Store.callerLatin))

        if (parts.isEmpty()) parts.add(Phon.Part("مكالمة واردة", true))
        speak(parts, "$source: $who → " + parts.joinToString(" ") { it.text })
    }

    private fun speak(parts: List<Phon.Part>, logMsg: String) {
        if (Store.callerRespectSilent && isSilent()) {
            note("$logMsg — لكن الجهاز صامت")
            return
        }
        spokenAt = System.currentTimeMillis()
        note(logMsg)
        Speaker.announceParts(this, parts, Store.callerRepeat)
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

    // ================= the microphone =================

    /**
     * Watches for any app opening the microphone.
     *
     * The system reports that a recording is running and what it is recording
     * for — never who is recording, and never a single sample of it. That is
     * exactly enough: the moment something starts, the chime is taken out of the
     * way, and the moment it stops, it is put back.
     */
    private var micOn = false
    private var recCb: Any? = null

    private val micSources = intArrayOf(
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.DEFAULT,
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.UNPROCESSED
    )

    private fun isMic(src: Int): Boolean {
        for (s in micSources) if (s == src) return true
        return false
    }

    // ================= calls that do not come through the network =============

    /**
     * A WhatsApp call is not a phone call as far as Android is concerned — no
     * number, no state change, nothing the telephony side ever hears about. What
     * it does do is post a notification with a screen attached to it, and that
     * notification carries the caller's name in its title.
     *
     * So the name is read from there. A full-screen notification from a calling
     * app is an incoming call and almost nothing else is, and the wording is
     * checked as well for the apps that do not set one.
     */
    private val callApps = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus",
        "com.instagram.android", "com.facebook.orca", "com.facebook.mlite",
        "com.viber.voip", "com.imo.android.imoim", "com.imo.android.imoimbeta",
        "com.skype.raider", "com.google.android.apps.tachyon",
        "com.microsoft.teams", "us.zoom.videomeetings", "com.discord",
        "com.signal.app", "org.thoughtcrime.securesms", "com.bbm", "jp.naver.line.android"
    )

    private val callWords = arrayOf(
        "incoming", "calling", "voice call", "video call", "ringing",
        "مكالمة", "يتصل", "اتصال", "يرن", "تتصل", "مكالمه"
    )

    /** Words that mean a missed or ended call, which must not be announced. */
    private val notCallWords = arrayOf(
        "missed", "ongoing", "فائتة", "فائته", "لم يرد", "جارية", "منتهية",
        "declined", "ended"
    )

    private var lastAppCall = ""
    private var lastAppCallAt = 0L

    private fun appCall(event: AccessibilityEvent) {
        if (!Store.callerSpeak || !Store.callerApps) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in callApps) return

        val n = event.parcelableData as? android.app.Notification ?: return
        val x = n.extras ?: return
        val title = x.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim()
            ?: return
        if (title.isEmpty()) return
        val body = (x.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: "")
            .lowercase()

        for (w in notCallWords) if (body.contains(w)) return

        val fullScreen = n.fullScreenIntent != null
        var worded = false
        for (w in callWords) if (body.contains(w)) { worded = true; break }
        if (!fullScreen && !worded) return

        // it reposts the same notification the whole time it rings
        val now = System.currentTimeMillis()
        if (title == lastAppCall && now - lastAppCallAt < 25_000L) return
        lastAppCall = title
        lastAppCallAt = now

        Speaker.autoPickVoice(this)
        val prefix = Store.callerPrefix.trim()
        val parts = ArrayList<Phon.Part>(4)
        if (prefix.isNotEmpty()) parts.add(Phon.Part(prefix, true))
        parts.addAll(Phon.parts(title, Store.callerLatin))
        if (parts.isEmpty()) return
        speak(parts, "مكالمة تطبيق: $title")
    }

    private fun micChanged(recording: Boolean) {
        if (recording == micOn) return
        micOn = recording
        if (recording) {
            if (!Hush.ours) Hush.on(this)
        } else {
            Hush.off(this)
        }
    }

    private fun startMicWatch() {
        if (recCb != null) return
        if (!Store.recHush) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(
                    configs: MutableList<AudioRecordingConfiguration>?
                ) {
                    var live = false
                    if (configs != null) {
                        for (c in configs) if (isMic(c.clientAudioSource)) { live = true; break }
                    }
                    handler.post { micChanged(live) }
                }
            }
            am.registerAudioRecordingCallback(cb, handler)
            recCb = cb
            // something may already be recording when the service starts
            var live = false
            for (c in am.activeRecordingConfigurations) {
                if (isMic(c.clientAudioSource)) { live = true; break }
            }
            micChanged(live)
        } catch (e: Exception) {
            note("ما كدرت أراقب المايك: ${e.javaClass.simpleName}")
        }
    }

    private fun stopMicWatch() {
        val cb = recCb as? AudioManager.AudioRecordingCallback ?: return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.unregisterAudioRecordingCallback(cb)
        } catch (_: Exception) {
        }
        recCb = null
        micOn = false
        Hush.off(this)
    }

    // ================= lifecycle =================

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.load(this)
        // whatever happened last time, the sound comes back first
        Hush.recover(this)
        startMicWatch()
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
        stopMicWatch()
        handler.removeCallbacksAndMessages(null)
        Speaker.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ================= text expansion =================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            try { appCall(event) } catch (_: Throwable) {}
            return
        }
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
