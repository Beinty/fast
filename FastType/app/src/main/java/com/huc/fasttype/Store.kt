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

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        setItems(parse(p.getString(K_ITEMS, "[]") ?: "[]"))
        instant = p.getBoolean(K_INSTANT, true)
        enabled = p.getBoolean(K_ENABLED, true)
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
