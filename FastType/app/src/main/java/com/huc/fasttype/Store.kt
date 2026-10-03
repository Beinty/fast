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
    private const val K_KB_SRAD = "kb_sugg_rad"
    private const val K_KB_SH = "kb_sugg_h"
    private const val K_KB_HAIR = "kb_hair"
    private const val K_KB_HAIRH = "kb_hair_h"
    private const val K_KB_HAIRW = "kb_hair_w"
    private const val K_KB_MICSTRIP = "kb_mic_strip"
    private const val K_KB_GLOBEROW = "kb_globe_row"
    private const val K_KB_LETTER = "kb_letter"
    private const val K_KB_PRESSFX = "kb_press_fx"
    private const val K_KB_PREDICT = "kb_predict"
    private const val K_KB_CORRECT = "kb_correct"
    private const val K_KB_CLEAR = "kb_clear_bottom"
    private const val K_KB_LEARN = "kb_learn"
    private const val K_KB_BLANK = "kb_blank_hold"
    private const val K_KB_FOLLOW = "kb_follow_system"
    private const val K_KB_CLIP = "kb_clip"
    private const val K_KB_CLIPEXP = "kb_clip_expire"

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

    @Volatile var kbTheme: String = "iosCrisp"
        private set
    @Volatile var kbKeyHeight: Int = 44
        private set
    @Volatile var kbGap: Int = 5
        private set
    @Volatile var kbRadius: Int = 6
        private set
    @Volatile var kbPanelRadius: Int = 28
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
    @Volatile var kbOuterH: Int = 0
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

    /** Corner radius of the suggestion strip; 0 makes it flat like iOS. */
    @Volatile var kbSuggRad: Int = 0
        private set

    /** Height of the suggestion strip. */
    @Volatile var kbSuggH: Int = 44
        private set

    /** Draw the two thin dividers in the prediction strip. */
    @Volatile var kbHair: Boolean = true
        private set

    /** Divider length, as a percentage of the strip height. Measured: 54. */
    @Volatile var kbHairH: Int = 54
        private set

    /** Divider thickness in dp. */
    @Volatile var kbHairW: Int = 1
        private set

    /** Put the mic at the right end of the prediction strip. */
    @Volatile var kbMicStrip: Boolean = true
        private set

    /** Put the language key next to the emoji key and drop the bottom strip. */
    @Volatile var kbGlobeRow: Boolean = true
        private set

    /** Letter size as a percentage of key height. Measured: 49. */
    @Volatile var kbLetter: Int = 49
        private set

    /** Highlight a key while it is held. Off is the fastest possible tap. */
    @Volatile var kbPressFx: Boolean = true
        private set

    /** Offer word predictions in the strip while typing. */
    @Volatile var kbPredict: Boolean = true
        private set

    /** Fix a misspelt word when a space or punctuation ends it. */
    @Volatile var kbCorrect: Boolean = true
        private set

    /**
     * Leave the clear space under the panel unpainted. Off by default: the rounded
     * corners already show the app, and a matching band under the keys reads better
     * than a see-through gap.
     */
    @Volatile var kbClearBottom: Boolean = false
        private set

    /** Learn the words this person writes and favour them. */
    @Volatile var kbLearn: Boolean = true
        private set

    /** A long press on space hides every label until the next key press. */
    @Volatile var kbBlankHold: Boolean = true
        private set

    /** Swap to the chosen theme's twin when the phone switches to dark or light. */
    @Volatile var kbFollowSystem: Boolean = true
        private set

    /** Keep a clipboard key in the strip and remember what was copied. */
    @Volatile var kbClip: Boolean = true
        private set

    /**
     * Minutes before an unpinned clip is forgotten; 0 keeps them. Defaults to an
     * hour, because what people copy is often a password or a payment number.
     */
    @Volatile var kbClipExpire: Int = 60
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

        kbTheme = p.getString(K_KB_THEME, "iosCrisp") ?: "iosCrisp"
        kbKeyHeight = p.getInt(K_KB_H, 44).coerceIn(34, 58)
        kbGap = p.getInt(K_KB_GAP, 5).coerceIn(2, 10)
        kbRadius = p.getInt(K_KB_RAD, 6).coerceIn(2, 18)
        kbPanelRadius = p.getInt(K_KB_PRAD, 28).coerceIn(0, 44)
        kbNumberRow = p.getBoolean(K_KB_NUM, false)
        kbSuggBar = p.getBoolean(K_KB_SUGG, true)
        kbSound = p.getBoolean(K_KB_SOUND, false)
        kbVibrate = p.getBoolean(K_KB_VIB, false)
        kbArabicFirst = p.getBoolean(K_KB_AR, true)
        kbExpand = p.getBoolean(K_KB_EXP, true)
        kbExpandInstant = p.getBoolean(K_KB_EXP_INST, false)
        kbOuterH = p.getInt(K_KB_OUTER, 0).coerceIn(0, 60)
        kbBottomPad = p.getInt(K_KB_BOTTOM, 10).coerceIn(0, 48)
        kbFast = p.getBoolean(K_KB_FAST, true)
        kbInset = p.getInt(K_KB_INSET, 0).coerceIn(0, 14)
        kbSuggRad = p.getInt(K_KB_SRAD, 0).coerceIn(0, 22)
        kbSuggH = p.getInt(K_KB_SH, 44).coerceIn(22, 60)
        kbHair = p.getBoolean(K_KB_HAIR, true)
        kbHairH = p.getInt(K_KB_HAIRH, 54).coerceIn(20, 90)
        kbHairW = p.getInt(K_KB_HAIRW, 1).coerceIn(1, 4)
        kbMicStrip = p.getBoolean(K_KB_MICSTRIP, true)
        kbGlobeRow = p.getBoolean(K_KB_GLOBEROW, true)
        kbLetter = p.getInt(K_KB_LETTER, 49).coerceIn(40, 58)
        kbPressFx = p.getBoolean(K_KB_PRESSFX, true)
        kbPredict = p.getBoolean(K_KB_PREDICT, true)
        kbCorrect = p.getBoolean(K_KB_CORRECT, true)
        kbClearBottom = p.getBoolean(K_KB_CLEAR, false)
        kbLearn = p.getBoolean(K_KB_LEARN, true)
        kbBlankHold = p.getBoolean(K_KB_BLANK, true)
        kbFollowSystem = p.getBoolean(K_KB_FOLLOW, true)
        kbClip = p.getBoolean(K_KB_CLIP, true)
        kbClipExpire = p.getInt(K_KB_CLIPEXP, 60).coerceIn(0, 1440)
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
            "prad" -> { kbPanelRadius = v.coerceIn(0, 44); prefs(ctx).edit().putInt(K_KB_PRAD, kbPanelRadius).apply() }
            "outer" -> { kbOuterH = v.coerceIn(0, 60); prefs(ctx).edit().putInt(K_KB_OUTER, kbOuterH).apply() }
            "bottom" -> { kbBottomPad = v.coerceIn(0, 48); prefs(ctx).edit().putInt(K_KB_BOTTOM, kbBottomPad).apply() }
            "inset" -> { kbInset = v.coerceIn(0, 14); prefs(ctx).edit().putInt(K_KB_INSET, kbInset).apply() }
            "srad" -> { kbSuggRad = v.coerceIn(0, 22); prefs(ctx).edit().putInt(K_KB_SRAD, kbSuggRad).apply() }
            "sh" -> { kbSuggH = v.coerceIn(22, 60); prefs(ctx).edit().putInt(K_KB_SH, kbSuggH).apply() }
            "hairh" -> { kbHairH = v.coerceIn(20, 90); prefs(ctx).edit().putInt(K_KB_HAIRH, kbHairH).apply() }
            "hairw" -> { kbHairW = v.coerceIn(1, 4); prefs(ctx).edit().putInt(K_KB_HAIRW, kbHairW).apply() }
            "letter" -> { kbLetter = v.coerceIn(40, 58); prefs(ctx).edit().putInt(K_KB_LETTER, kbLetter).apply() }
            "clipexp" -> { kbClipExpire = v.coerceIn(0, 1440); prefs(ctx).edit().putInt(K_KB_CLIPEXP, kbClipExpire).apply() }
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
            "hair" -> { kbHair = v; e.putBoolean(K_KB_HAIR, v) }
            "micstrip" -> { kbMicStrip = v; e.putBoolean(K_KB_MICSTRIP, v) }
            "globerow" -> { kbGlobeRow = v; e.putBoolean(K_KB_GLOBEROW, v) }
            "pressfx" -> { kbPressFx = v; e.putBoolean(K_KB_PRESSFX, v) }
            "predict" -> { kbPredict = v; e.putBoolean(K_KB_PREDICT, v) }
            "correct" -> { kbCorrect = v; e.putBoolean(K_KB_CORRECT, v) }
            "clear" -> { kbClearBottom = v; e.putBoolean(K_KB_CLEAR, v) }
            "learn" -> { kbLearn = v; e.putBoolean(K_KB_LEARN, v) }
            "blank" -> { kbBlankHold = v; e.putBoolean(K_KB_BLANK, v) }
            "follow" -> { kbFollowSystem = v; e.putBoolean(K_KB_FOLLOW, v) }
            "clip" -> { kbClip = v; e.putBoolean(K_KB_CLIP, v) }
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
