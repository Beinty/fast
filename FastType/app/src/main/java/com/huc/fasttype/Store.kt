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
