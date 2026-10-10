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
    private const val K_VOICE_EN = "caller_voice_en"
    private const val K_LATIN = "caller_latin"
    private const val K_VOICE_AUTO = "caller_voice_auto"
    private const val K_REC_HUSH = "rec_hush"
    private const val K_REC_MIN = "rec_hush_min"
    private const val K_HUSH_N = "hush_saved_notif"
    private const val K_HUSH_S = "hush_saved_system"
    private const val K_CALL_APPS = "caller_apps"
    private const val K_KB_DOT = "kb_dotkey"

    private const val K_KB_THEME = "kb_theme"
    private const val K_KB_H = "kb_h"
    private const val K_KB_GAP = "kb_gap"
    private const val K_KB_RAD = "kb_rad"
    private const val K_KB_WEIGHT = "kb_weight"
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
    private const val K_KB_ALTS = "kb_alts"
    private const val K_KB_DOTS = "kb_double_space"
    private const val K_TR_SRC = "kb_tr_src"
    private const val K_TR_DST = "kb_tr_dst"
    private const val K_KB_PICS = "kb_pics"
    private const val K_KB_FINGERY = "kb_finger_y"
    private const val K_KB_PEEK = "kb_peek"
    private const val K_KB_SHADE = "kb_shade"
    private const val K_KB_WARM = "kb_warm"
    private const val K_KB_ARFONT = "kb_ar_font"
    private const val K_KB_GLASS = "kb_glass"
    private const val K_KB_GLASS_P = "kb_glass_panel"
    private const val K_KB_GLASS_K = "kb_glass_key"

    // automatic replies
    private const val K_AR_ON = "ar_on"
    private const val K_AR_WA = "ar_whats"
    private const val K_AR_TG = "ar_tg"
    private const val K_AR_SMS = "ar_sms"
    private const val K_AR_MODE = "ar_mode"
    private const val K_AR_LIST = "ar_list"
    private const val K_AR_GROUPS = "ar_groups"
    private const val K_AR_ONCE = "ar_once"
    private const val K_AR_DELAY = "ar_delay"
    private const val K_AR_HOURS = "ar_hours"
    private const val K_AR_FROM = "ar_from"
    private const val K_AR_TO = "ar_to"
    private const val K_AR_STYLE = "ar_style"
    private const val K_AR_PERSONA = "ar_persona"
    private const val K_AR_STOP = "ar_stop"
    private const val K_AR_LOG = "ar_log"
    private const val K_AR_OFFLINE = "ar_offline"
    private const val K_AR_OFFMSG = "ar_offline_msg"
    private const val K_AR_SEARCH = "ar_search"

    @Volatile
    var items: List<Shortcut> = emptyList()
        private set

    @Volatile
    var ordered: List<Shortcut> = emptyList()
        private set

    /**
     * Shortcuts grouped by the last letter of their trigger.
     *
     * Every single keypress asks whether a shortcut just completed. Walking the whole
     * list to ask that is work repeated hundreds of times a minute for an answer that
     * is almost always no. A trigger can only have completed if its final letter is
     * the one just typed, so that letter picks the handful worth checking.
     */
    @Volatile
    var byLast: Map<Char, List<Shortcut>> = emptyMap()
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

    /** The voice that reads Latin-letter names, when the engine has one. */
    @Volatile
    var callerVoiceEn: String = ""
        private set

    /** What happens to a Latin name that is not in the name table — see Phon.Mode. */
    @Volatile
    var callerLatin: Int = 0
        private set

    /** The full stop between the space bar and the action key. */
    @Volatile
    var kbDotKey: Boolean = true
        private set

    /** Silence the notification chime while any app has the microphone open. */
    @Volatile
    var recHush: Boolean = false
        private set

    /** How long the silence may last before it is lifted anyway. */
    @Volatile
    var recHushMinutes: Int = 5
        private set

    /**
     * The levels to put back after a recording, written down the moment they are
     * taken. On disk rather than in memory, so a phone whose app was killed
     * mid-recording still gets its sound back the next time anything of ours runs.
     * -1 means nothing is owed.
     */
    @Volatile
    var hushNotif: Int = -1
        private set

    @Volatile
    var hushSystem: Int = -1
        private set

    /** Announce calls that come in through WhatsApp and the like. */
    @Volatile
    var callerApps: Boolean = true
        private set

    /** False until the app has chosen a voice once on his behalf. */
    @Volatile
    var callerVoiceAuto: Boolean = false
        private set

    @Volatile var kbTheme: String = "iosCrisp"
        private set
    @Volatile var kbKeyHeight: Int = 44
        private set
    @Volatile var kbGap: Int = 5
        private set
    @Volatile var kbRadius: Int = 6

    /**
     * How heavy the letters on the keys are, 300 to 700.
     *
     * The keyboard drew them at 400, which is why its letters looked lighter than
     * an iPhone's beside it. Left as a number rather than a switch because the
     * right weight depends on the theme and on his eyes, not on mine.
     */
    @Volatile var kbWeight: Int = 600
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

    /** A long press on a letter offers its alternates — أ إ آ and the rest. */
    @Volatile var kbAlts: Boolean = true
        private set

    /** Two taps on space become a full stop and a space. */
    @Volatile var kbDoubleSpace: Boolean = true
        private set

    /** The clipboard key also holds the last screenshot or copied picture. */
    @Volatile var kbPics: Boolean = false
        private set

    /** The language the translate panel reads from; "auto" lets it work that out. */
    @Volatile var kbTrSrc: String = "auto"
        private set

    /** The language the translate panel writes in. */
    @Volatile var kbTrDst: String = "en"
        private set

    /**
     * Minutes before an unpinned clip is forgotten; 0 keeps them. Defaults to an
     * hour, because what people copy is often a password or a payment number.
     */
    @Volatile var kbClipExpire: Int = 60
        private set

    // ---------- automatic replies ----------
    //
    // Off until he turns it on. Everything here sends a message in his name with
    // no chance to read it first, so nothing in this block defaults to active.

    @Volatile var arOn: Boolean = false
        private set

    @Volatile var arWhats: Boolean = true
        private set
    @Volatile var arTg: Boolean = true
        private set
    @Volatile var arSms: Boolean = false
        private set

    /** 0 everyone · 1 only the named · 2 everyone except the named. */
    @Volatile var arMode: Int = 0
        private set

    /** Names to match against the notification title, comma separated. */
    @Volatile var arList: String = ""
        private set

    @Volatile var arGroups: Boolean = false
        private set

    /** One reply per conversation until he opens the chat himself. */
    @Volatile var arOnce: Boolean = true
        private set

    /** Seconds to wait before sending; an instant answer reads as a machine. */
    @Volatile var arDelay: Int = 3
        private set

    /**
     * Send a fixed line when the model cannot be reached at all.
     *
     * The model runs on Google's servers, so with no network there is no reply
     * to generate. This is the only thing that can go out instead.
     */
    @Volatile var arOffline: Boolean = false
        private set

    @Volatile var arOfflineMsg: String = "مشغول هسّه، أرد عليك بعد شوية"
        private set

    /** Let the model search Google before answering. Slower, and uses quota. */
    @Volatile var arSearch: Boolean = false
        private set

    @Volatile var arHours: Boolean = false
        private set
    @Volatile var arFrom: Int = 9
        private set
    @Volatile var arTo: Int = 17
        private set

    /** 0 match the incoming message · 1 Iraqi · 2 standard Arabic. */
    @Volatile var arStyle: Int = 0
        private set

    @Volatile var arPersona: String = ""
        private set

    /**
     * Any of these in an incoming message and nothing is sent.
     *
     * The case this is for: someone posing as a friend asking for a transfer. A
     * machine answering that in his name is the one failure with a real cost.
     */
    @Volatile var arStop: String = "فلوس,تحويل,حوّل,رقم سري,كود,حواله,حوالة,رمز,otp,password"
        private set

    /** The last [LOG_MAX] replies, newest first, as JSON. */
    @Volatile var arLog: String = "[]"
        private set

    const val LOG_MAX = 50

    /**
     * How far above the touch the keyboard reads a tap, in hundredths of a key
     * height. A finger aims with the tip and lands with the pad. 0 turns it off.
     */
    @Volatile var kbFingerY: Int = 12
        private set

    /** Lift a copy of the letter above the finger while a key is held. */
    @Volatile var kbPeek: Boolean = true
        private set

    /**
     * How far the whole keyboard is veiled, 0-60. Works over any theme, because
     * the glare comes from how much light the panel puts out, not from which
     * white it was painted.
     */
    @Volatile var kbShade: Int = 0
        private set

    /** Amber laid over the keyboard, 0-40. Takes the blue out rather than the light. */
    @Volatile var kbWarm: Int = 0
        private set

    /**
     * Draw Arabic in the bundled face rather than the system one.
     *
     * Arabic only: the file carries no Latin letters, no digits and no emoji,
     * so those keep the system face whatever this is set to.
     */
    @Volatile var kbArFont: Boolean = false
        private set

    /**
     * Let the app behind show through the keyboard.
     *
     * Translucency only. The system's blur cannot be used here: it blurs
     * everything behind the window, and an IME's window is the whole screen.
     */
    @Volatile var kbGlass: Boolean = false
        private set

    /** How solid the panel stays, 20-100. Lower lets more through. */
    @Volatile var kbGlassPanel: Int = 62
        private set

    /** The same for the key faces. Kept higher: they are what he aims at. */
    @Volatile var kbGlassKey: Int = 78
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
        callerVoiceEn = p.getString(K_VOICE_EN, "") ?: ""
        callerLatin = p.getInt(K_LATIN, 0).coerceIn(0, 2)
        callerVoiceAuto = p.getBoolean(K_VOICE_AUTO, false)
        recHush = p.getBoolean(K_REC_HUSH, false)
        recHushMinutes = p.getInt(K_REC_MIN, 2).coerceIn(1, 30)
        hushNotif = p.getInt(K_HUSH_N, -1)
        hushSystem = p.getInt(K_HUSH_S, -1)
        callerApps = p.getBoolean(K_CALL_APPS, true)
        kbDotKey = p.getBoolean(K_KB_DOT, true)

        kbTheme = p.getString(K_KB_THEME, "iosCrisp") ?: "iosCrisp"
        kbKeyHeight = p.getInt(K_KB_H, 44).coerceIn(34, 58)
        kbGap = p.getInt(K_KB_GAP, 5).coerceIn(2, 10)
        kbRadius = p.getInt(K_KB_RAD, 6).coerceIn(2, 18)
        kbWeight = p.getInt(K_KB_WEIGHT, 600).coerceIn(300, 700)
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
        kbAlts = p.getBoolean(K_KB_ALTS, true)
        kbDoubleSpace = p.getBoolean(K_KB_DOTS, true)
        kbPics = p.getBoolean(K_KB_PICS, false)
        kbTrSrc = p.getString(K_TR_SRC, "auto") ?: "auto"
        kbTrDst = p.getString(K_TR_DST, "en") ?: "en"
        kbClipExpire = p.getInt(K_KB_CLIPEXP, 60).coerceIn(0, 1440)
        kbFingerY = p.getInt(K_KB_FINGERY, 12).coerceIn(0, 30)
        kbPeek = p.getBoolean(K_KB_PEEK, true)
        kbShade = p.getInt(K_KB_SHADE, 0).coerceIn(0, 60)
        kbWarm = p.getInt(K_KB_WARM, 0).coerceIn(0, 40)
        kbArFont = p.getBoolean(K_KB_ARFONT, false)
        kbGlass = p.getBoolean(K_KB_GLASS, false)
        kbGlassPanel = p.getInt(K_KB_GLASS_P, 62).coerceIn(20, 100)
        kbGlassKey = p.getInt(K_KB_GLASS_K, 78).coerceIn(20, 100)

        arOn = p.getBoolean(K_AR_ON, false)
        arWhats = p.getBoolean(K_AR_WA, true)
        arTg = p.getBoolean(K_AR_TG, true)
        arSms = p.getBoolean(K_AR_SMS, false)
        arMode = p.getInt(K_AR_MODE, 0).coerceIn(0, 2)
        arList = p.getString(K_AR_LIST, "") ?: ""
        arGroups = p.getBoolean(K_AR_GROUPS, false)
        arOnce = p.getBoolean(K_AR_ONCE, true)
        arDelay = p.getInt(K_AR_DELAY, 3).coerceIn(0, 120)
        arOffline = p.getBoolean(K_AR_OFFLINE, false)
        arOfflineMsg = p.getString(K_AR_OFFMSG, arOfflineMsg) ?: arOfflineMsg
        arSearch = p.getBoolean(K_AR_SEARCH, false)
        arHours = p.getBoolean(K_AR_HOURS, false)
        arFrom = p.getInt(K_AR_FROM, 9).coerceIn(0, 23)
        arTo = p.getInt(K_AR_TO, 17).coerceIn(0, 23)
        arStyle = p.getInt(K_AR_STYLE, 0).coerceIn(0, 2)
        arPersona = p.getString(K_AR_PERSONA, "") ?: ""
        arStop = p.getString(K_AR_STOP, arStop) ?: arStop
        arLog = p.getString(K_AR_LOG, "[]") ?: "[]"
    }

    fun setArFlag(ctx: Context, which: String, v: Boolean) {
        val e = prefs(ctx).edit()
        when (which) {
            "on" -> { arOn = v; e.putBoolean(K_AR_ON, v) }
            "wa" -> { arWhats = v; e.putBoolean(K_AR_WA, v) }
            "tg" -> { arTg = v; e.putBoolean(K_AR_TG, v) }
            "sms" -> { arSms = v; e.putBoolean(K_AR_SMS, v) }
            "groups" -> { arGroups = v; e.putBoolean(K_AR_GROUPS, v) }
            "once" -> { arOnce = v; e.putBoolean(K_AR_ONCE, v) }
            "hours" -> { arHours = v; e.putBoolean(K_AR_HOURS, v) }
            "offline" -> { arOffline = v; e.putBoolean(K_AR_OFFLINE, v) }
            "search" -> { arSearch = v; e.putBoolean(K_AR_SEARCH, v) }
        }
        e.apply()
    }

    fun setArInt(ctx: Context, which: String, v: Int) {
        val e = prefs(ctx).edit()
        when (which) {
            "mode" -> { arMode = v.coerceIn(0, 2); e.putInt(K_AR_MODE, arMode) }
            "delay" -> { arDelay = v.coerceIn(0, 120); e.putInt(K_AR_DELAY, arDelay) }
            "from" -> { arFrom = v.coerceIn(0, 23); e.putInt(K_AR_FROM, arFrom) }
            "to" -> { arTo = v.coerceIn(0, 23); e.putInt(K_AR_TO, arTo) }
            "style" -> { arStyle = v.coerceIn(0, 2); e.putInt(K_AR_STYLE, arStyle) }
        }
        e.apply()
    }

    fun setArText(ctx: Context, which: String, v: String) {
        val e = prefs(ctx).edit()
        when (which) {
            "list" -> { arList = v; e.putString(K_AR_LIST, v) }
            "persona" -> { arPersona = v; e.putString(K_AR_PERSONA, v) }
            "stop" -> { arStop = v; e.putString(K_AR_STOP, v) }
            "offmsg" -> { arOfflineMsg = v; e.putString(K_AR_OFFMSG, v) }
        }
        e.apply()
    }

    /**
     * Records one reply attempt, sent or not.
     *
     * Anything that goes out in his name has to be readable afterwards, so the
     * blocked and failed attempts are kept here too, not only the successes.
     */
    fun addReplyLog(ctx: Context, pkg: String, who: String, inq: String, out: String, ok: Boolean) {
        val arr = try { JSONArray(arLog) } catch (_: Throwable) { JSONArray() }
        val row = JSONObject()
        row.put("t", System.currentTimeMillis())
        row.put("app", pkg)
        row.put("who", who)
        row.put("in", inq)
        row.put("out", out)
        row.put("ok", ok)
        val next = JSONArray()
        next.put(row)
        var i = 0
        while (i < arr.length() && next.length() < LOG_MAX) {
            next.put(arr.get(i)); i++
        }
        arLog = next.toString()
        prefs(ctx).edit().putString(K_AR_LOG, arLog).apply()
    }

    fun clearReplyLog(ctx: Context) {
        arLog = "[]"
        prefs(ctx).edit().putString(K_AR_LOG, arLog).apply()
    }

    private fun setItems(list: List<Shortcut>) {
        items = list
        ordered = list.filter { it.on && it.trigger.isNotEmpty() }
            .sortedByDescending { it.trigger.length }
        val m = HashMap<Char, MutableList<Shortcut>>(64)
        for (s in ordered) {
            m.getOrPut(s.trigger[s.trigger.length - 1]) { ArrayList(4) }.add(s)
        }
        byLast = m
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

    fun setCallerVoiceEn(ctx: Context, v: String) {
        callerVoiceEn = v
        prefs(ctx).edit().putString(K_VOICE_EN, v).apply()
    }

    fun setCallerLatin(ctx: Context, v: Int) {
        callerLatin = v.coerceIn(0, 2)
        prefs(ctx).edit().putInt(K_LATIN, callerLatin).apply()
    }

    fun setHushSaved(ctx: Context, notif: Int, system: Int) {
        hushNotif = notif
        hushSystem = system
        prefs(ctx).edit().putInt(K_HUSH_N, notif).putInt(K_HUSH_S, system).apply()
    }

    fun clearHushSaved(ctx: Context) {
        hushNotif = -1
        hushSystem = -1
        prefs(ctx).edit().putInt(K_HUSH_N, -1).putInt(K_HUSH_S, -1).apply()
    }

    fun setCallerApps(ctx: Context, v: Boolean) {
        callerApps = v
        prefs(ctx).edit().putBoolean(K_CALL_APPS, v).apply()
    }

    fun setRecHush(ctx: Context, v: Boolean) {
        recHush = v
        prefs(ctx).edit().putBoolean(K_REC_HUSH, v).apply()
    }

    fun setRecHushMinutes(ctx: Context, v: Int) {
        recHushMinutes = v.coerceIn(1, 30)
        prefs(ctx).edit().putInt(K_REC_MIN, recHushMinutes).apply()
    }

    fun setCallerVoiceAuto(ctx: Context, v: Boolean) {
        callerVoiceAuto = v
        prefs(ctx).edit().putBoolean(K_VOICE_AUTO, v).apply()
    }

    fun setCallerRate(ctx: Context, v: Float) {
        callerRate = v.coerceIn(0.5f, 2.0f)
        prefs(ctx).edit().putFloat(K_RATE, callerRate).apply()
    }

    fun setCallerPitch(ctx: Context, v: Float) {
        callerPitch = v.coerceIn(0.5f, 2.0f)
        prefs(ctx).edit().putFloat(K_PITCH, callerPitch).apply()
    }

    /**
     * Picks a theme, and lets a glass one bring its own settings with it.
     *
     * A glass theme is not just colours: it needs transparency on and at a depth
     * that suits it — milky glass at 94 and clear glass at 74 are two different
     * things, and neither is findable by dragging a slider blind. So picking one
     * switches transparency on and sets its depth, and picking a solid theme
     * afterwards switches it back off. Both stay editable: these are starting
     * points, not locks.
     */
    fun setKbTheme(ctx: Context, v: String) {
        val was = Themes.byId(kbTheme).glass
        kbTheme = v
        val t = Themes.byId(v)
        val e = prefs(ctx).edit().putString(K_KB_THEME, v)
        if (t.glass) {
            kbGlass = true
            e.putBoolean(K_KB_GLASS, true)
            if (t.glassPanel > 0) {
                kbGlassPanel = t.glassPanel.coerceIn(20, 100); e.putInt(K_KB_GLASS_P, kbGlassPanel)
            }
            if (t.glassKey > 0) {
                kbGlassKey = t.glassKey.coerceIn(20, 100); e.putInt(K_KB_GLASS_K, kbGlassKey)
            }
            if (t.inset >= 0) {
                kbInset = t.inset.coerceIn(0, 14); e.putInt(K_KB_INSET, kbInset)
            }
            if (t.panelRadius >= 0) {
                kbPanelRadius = t.panelRadius.coerceIn(0, 44); e.putInt(K_KB_PRAD, kbPanelRadius)
            }
        } else if (was) {
            // leaving glass behind: a solid theme under a half-transparent panel
            // looks like a bug, not a choice
            kbGlass = false
            e.putBoolean(K_KB_GLASS, false)
        }
        e.apply()
    }

    fun setTrLang(ctx: Context, dst: Boolean, code: String) {
        if (dst) {
            kbTrDst = code; prefs(ctx).edit().putString(K_TR_DST, code).apply()
        } else {
            kbTrSrc = code; prefs(ctx).edit().putString(K_TR_SRC, code).apply()
        }
    }
    fun setKbInt(ctx: Context, which: String, v: Int) {
        when (which) {
            "h" -> { kbKeyHeight = v.coerceIn(34, 58); prefs(ctx).edit().putInt(K_KB_H, kbKeyHeight).apply() }
            "gap" -> { kbGap = v.coerceIn(2, 10); prefs(ctx).edit().putInt(K_KB_GAP, kbGap).apply() }
            "rad" -> { kbRadius = v.coerceIn(2, 18); prefs(ctx).edit().putInt(K_KB_RAD, kbRadius).apply() }
            "weight" -> { kbWeight = v.coerceIn(300, 700); prefs(ctx).edit().putInt(K_KB_WEIGHT, kbWeight).apply() }
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
            "fingery" -> { kbFingerY = v.coerceIn(0, 30); prefs(ctx).edit().putInt(K_KB_FINGERY, kbFingerY).apply() }
            "glassp" -> { kbGlassPanel = v.coerceIn(20, 100); prefs(ctx).edit().putInt(K_KB_GLASS_P, kbGlassPanel).apply() }
            "glassk" -> { kbGlassKey = v.coerceIn(20, 100); prefs(ctx).edit().putInt(K_KB_GLASS_K, kbGlassKey).apply() }
            "shade" -> { kbShade = v.coerceIn(0, 60); prefs(ctx).edit().putInt(K_KB_SHADE, kbShade).apply() }
            "warm" -> { kbWarm = v.coerceIn(0, 40); prefs(ctx).edit().putInt(K_KB_WARM, kbWarm).apply() }
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
            "dotkey" -> { kbDotKey = v; e.putBoolean(K_KB_DOT, v) }
            "pressfx" -> { kbPressFx = v; e.putBoolean(K_KB_PRESSFX, v) }
            "predict" -> { kbPredict = v; e.putBoolean(K_KB_PREDICT, v) }
            "correct" -> { kbCorrect = v; e.putBoolean(K_KB_CORRECT, v) }
            "clear" -> { kbClearBottom = v; e.putBoolean(K_KB_CLEAR, v) }
            "learn" -> { kbLearn = v; e.putBoolean(K_KB_LEARN, v) }
            "blank" -> { kbBlankHold = v; e.putBoolean(K_KB_BLANK, v) }
            "follow" -> { kbFollowSystem = v; e.putBoolean(K_KB_FOLLOW, v) }
            "clip" -> { kbClip = v; e.putBoolean(K_KB_CLIP, v) }
            "alts" -> { kbAlts = v; e.putBoolean(K_KB_ALTS, v) }
            "dots" -> { kbDoubleSpace = v; e.putBoolean(K_KB_DOTS, v) }
            "pics" -> { kbPics = v; e.putBoolean(K_KB_PICS, v) }
            "peek" -> { kbPeek = v; e.putBoolean(K_KB_PEEK, v) }
            "arfont" -> { kbArFont = v; e.putBoolean(K_KB_ARFONT, v) }
            "glass" -> { kbGlass = v; e.putBoolean(K_KB_GLASS, v) }
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
