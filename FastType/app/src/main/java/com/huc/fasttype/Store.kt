package com.huc.fasttype

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Shortcut(
    val trigger: String,
    val phrase: String,
    val on: Boolean = true
)

object Store {

    const val PREF = "huc_fasttype"

    private const val K_ITEMS = "items"
    private const val K_INSTANT = "instant"
    private const val K_ENABLED = "enabled"

    private const val K_CALLER = "caller_speak"
    private const val K_PREFIX = "caller_prefix"
    private const val K_REPEAT = "caller_repeat"
    private const val K_SAY_NUM = "caller_say_number"
    private const val K_SILENT = "caller_respect_silent"
    private const val K_LAST_EVENT = "last_event"
    private const val K_VOICE = "caller_voice"
    private const val K_RATE = "caller_rate"
    private const val K_PITCH = "caller_pitch"

    private const val K_KB_THEME = "kb_theme"
    private const val K_KB_H = "kb_h"
    private const val K_KB_GAP = "kb_gap"
    private const val K_KB_RAD = "kb_rad"
    private const val K_KB_PRAD = "kb_prad"
    private const val K_KB_NUM = "kb_num"
    private const val K_KB_SUGG = "kb_sugg"
    private const val K_KB_SOUND = "kb_sound"
    private const val K_KB_VIB = "kb_vib"
    private const val K_KB_AR = "kb_ar_first"
    private const val K_KB_EXP = "kb_expand"
    private const val K_KB_EXP_INST = "kb_expand_instant"
    private const val K_KB_OUTER = "kb_outer_h"
    private const val K_KB_BOTTOM = "kb_bottom_pad"
    private const val K_KB_FAST = "kb_fast"
    private const val K_KB_INSET = "kb_inset"

    @Volatile
    var items: List<Shortcut> = emptyList()
        private set

    @Volatile
    var ordered: List<Shortcut> = emptyList()
        private set

    @Volatile
    var instant: Boolean = true
        private set

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    var callerSpeak: Boolean = false
        private set

    @Volatile
    var callerPrefix: String = "مكالمة من"
        private set

    @Volatile
    var callerRepeat: Int = 2
        private set

    @Volatile
    var callerSayNumber: Boolean = true
        private set

    @Volatile
    var callerRespectSilent: Boolean = true
        private set

    @Volatile
    var lastEvent: String = ""
        private set

    @Volatile
    var callerVoice: String = ""
        private set

    @Volatile
    var callerRate: Float = 1.0f
        private set

    @Volatile
    var callerPitch: Float = 1.0f
        private set

    @Volatile var kbTheme: String = "iosLight"
        private set
    @Volatile var kbKeyHeight: Int = 44
        private set
    @Volatile var kbGap: Int = 5
        private set
    @Volatile var kbRadius: Int = 5
        private set
    @Volatile var kbPanelRadius: Int = 0
        private set
    @Volatile var kbNumberRow: Boolean = false
        private set
    @Volatile var kbSuggBar: Boolean = true
        private set
    @Volatile var kbSound: Boolean = false
        private set
    @Volatile var kbVibrate: Boolean = false
        private set
    @Volatile var kbArabicFirst: Boolean = true
        private set
    @Volatile var kbExpand: Boolean = true
        private set
    @Volatile var kbExpandInstant: Boolean = false
        private set

    /** Height of the globe/mic strip under the panel. */
    @Volatile var kbOuterH: Int = 40
        private set

    /** Clear space under everything, so the strip never touches the system bar. */
    @Volatile var kbBottomPad: Int = 10
        private set

    /** Commit the character on finger-down instead of finger-up. */
    @Volatile var kbFast: Boolean = true
        private set

    /** Margin around the panel; 0 makes it reach the screen edges. */
    @Volatile var kbInset: Int = 0
        private set

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        setItems(parse(p.getString(K_ITEMS, "[]") ?: "[]"))
        instant = p.getBoolean(K_INSTANT, true)
        enabled = p.getBoolean(K_ENABLED, true)

        callerSpeak = p.getBoolean(K_CALLER, false)
        callerPrefix = p.getString(K_PREFIX, "مكالمة من") ?: "مكالمة من"
        callerRepeat = p.getInt(K_REPEAT, 2).coerceIn(1, 5)
        callerSayNumber = p.getBoolean(K_SAY_NUM, true)
        callerRespectSilent = p.getBoolean(K_SILENT, true)
        lastEvent = p.getString(K_LAST_EVENT, "") ?: ""
        callerVoice = p.getString(K_VOICE, "") ?: ""
        callerRate = p.getFloat(K_RATE, 1.0f)
        callerPitch = p.getFloat(K_PITCH, 1.0f)

        kbTheme = p.getString(K_KB_THEME, "iosLight") ?: "iosLight"
        kbKeyHeight = p.getInt(K_KB_H, 44).coerceIn(34, 58)
        kbGap = p.getInt(K_KB_GAP, 5).coerceIn(2, 10)
        kbRadius = p.getInt(K_KB_RAD, 5).coerceIn(2, 18)
        kbPanelRadius = p.getInt(K_KB_PRAD, 0).coerceIn(0, 34)
        kbNumberRow = p.getBoolean(K_KB_NUM, false)
        kbSuggBar = p.getBoolean(K_KB_SUGG, true)
        kbSound = p.getBoolean(K_KB_SOUND, false)
        kbVibrate = p.getBoolean(K_KB_VIB, false)
        kbArabicFirst = p.getBoolean(K_KB_AR, true)
        kbExpand = p.getBoolean(K_KB_EXP, true)
        kbExpandInstant = p.getBoolean(K_KB_EXP_INST, false)
        kbOuterH = p.getInt(K_KB_OUTER, 40).coerceIn(0, 60)
        kbBottomPad = p.getInt(K_KB_BOTTOM, 10).coerceIn(0, 48)
        kbFast = p.getBoolean(K_KB_FAST, true)
        kbInset = p.getInt(K_KB_INSET, 0).coerceIn(0, 14)
    }

    private fun setItems(list: List<Shortcut>) {
        items = list
        ordered = list.filter { it.on && it.trigger.isNotEmpty() }
            .sortedByDescending { it.trigger.length }
    }

    fun saveItems(ctx: Context, list: List<Shortcut>) {
        prefs(ctx).edit().putString(K_ITEMS, serialize(list)).apply()
        setItems(list)
    }

    fun setInstant(ctx: Context, v: Boolean) {
        instant = v
        prefs(ctx).edit().putBoolean(K_INSTANT, v).apply()
    }

    fun setEnabled(ctx: Context, v: Boolean) {
        enabled = v
        prefs(ctx).edit().putBoolean(K_ENABLED, v).apply()
    }

    fun setCallerSpeak(ctx: Context, v: Boolean) {
        callerSpeak = v
        prefs(ctx).edit().putBoolean(K_CALLER, v).apply()
    }

    fun setCallerPrefix(ctx: Context, v: String) {
        callerPrefix = v
        prefs(ctx).edit().putString(K_PREFIX, v).apply()
    }

    fun setCallerRepeat(ctx: Context, v: Int) {
        callerRepeat = v.coerceIn(1, 5)
        prefs(ctx).edit().putInt(K_REPEAT, callerRepeat).apply()
    }

    fun setCallerSayNumber(ctx: Context, v: Boolean) {
        callerSayNumber = v
        prefs(ctx).edit().putBoolean(K_SAY_NUM, v).apply()
    }

    fun setCallerRespectSilent(ctx: Context, v: Boolean) {
        callerRespectSilent = v
        prefs(ctx).edit().putBoolean(K_SILENT, v).apply()
    }

    fun setCallerVoice(ctx: Context, v: String) {
        callerVoice = v
        prefs(ctx).edit().putString(K_VOICE, v).apply()
    }

    fun setCallerRate(ctx: Context, v: Float) {
        callerRate = v.coerceIn(0.5f, 2.0f)
        prefs(ctx).edit().putFloat(K_RATE, callerRate).apply()
    }

    fun setCallerPitch(ctx: Context, v: Float) {
        callerPitch = v.coerceIn(0.5f, 2.0f)
        prefs(ctx).edit().putFloat(K_PITCH, callerPitch).apply()
    }

    fun setKbTheme(ctx: Context, v: String) {
        kbTheme = v; prefs(ctx).edit().putString(K_KB_THEME, v).apply()
    }
    fun setKbInt(ctx: Context, which: String, v: Int) {
        when (which) {
            "h" -> { kbKeyHeight = v.coerceIn(34, 58); prefs(ctx).edit().putInt(K_KB_H, kbKeyHeight).apply() }
            "gap" -> { kbGap = v.coerceIn(2, 10); prefs(ctx).edit().putInt(K_KB_GAP, kbGap).apply() }
            "rad" -> { kbRadius = v.coerceIn(2, 18); prefs(ctx).edit().putInt(K_KB_RAD, kbRadius).apply() }
            "prad" -> { kbPanelRadius = v.coerceIn(0, 34); prefs(ctx).edit().putInt(K_KB_PRAD, kbPanelRadius).apply() }
            "outer" -> { kbOuterH = v.coerceIn(0, 60); prefs(ctx).edit().putInt(K_KB_OUTER, kbOuterH).apply() }
            "bottom" -> { kbBottomPad = v.coerceIn(0, 48); prefs(ctx).edit().putInt(K_KB_BOTTOM, kbBottomPad).apply() }
            "inset" -> { kbInset = v.coerceIn(0, 14); prefs(ctx).edit().putInt(K_KB_INSET, kbInset).apply() }
        }
    }
    fun setKbFlag(ctx: Context, which: String, v: Boolean) {
        val e = prefs(ctx).edit()
        when (which) {
            "num" -> { kbNumberRow = v; e.putBoolean(K_KB_NUM, v) }
            "sugg" -> { kbSuggBar = v; e.putBoolean(K_KB_SUGG, v) }
            "sound" -> { kbSound = v; e.putBoolean(K_KB_SOUND, v) }
            "vib" -> { kbVibrate = v; e.putBoolean(K_KB_VIB, v) }
            "arfirst" -> { kbArabicFirst = v; e.putBoolean(K_KB_AR, v) }
            "expand" -> { kbExpand = v; e.putBoolean(K_KB_EXP, v) }
            "inst" -> { kbExpandInstant = v; e.putBoolean(K_KB_EXP_INST, v) }
            "fast" -> { kbFast = v; e.putBoolean(K_KB_FAST, v) }
        }
        e.apply()
    }

    fun setLastEvent(ctx: Context, v: String) {
        lastEvent = v
        prefs(ctx).edit().putString(K_LAST_EVENT, v).apply()
    }

    fun serialize(list: List<Shortcut>): String {
        val arr = JSONArray()
        for (s in list) {
            val o = JSONObject()
            o.put("trigger", s.trigger)
            o.put("phrase", s.phrase)
            o.put("on", s.on)
            arr.put(o)
        }
        return arr.toString(2)
    }

    fun parse(json: String): List<Shortcut> {
        val out = ArrayList<Shortcut>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("trigger").trim()
                val p = o.optString("phrase")
                if (t.isEmpty() || p.isEmpty()) continue
                out.add(Shortcut(t, p, o.optBoolean("on", true)))
            }
        } catch (_: Exception) {
        }
        return out
    }
}
