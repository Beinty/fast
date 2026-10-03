package com.huc.glass

import android.content.Context
import android.content.SharedPreferences

/** Everything the home screen remembers between launches. */
object GStore {

    private const val PREF = "huc_glass"
    private var p: SharedPreferences? = null

    fun init(c: Context) {
        if (p == null) {
            p = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }
    }

    /** "pkg/class" entries, in dock order. Empty means "work it out from the phone". */
    var dock: List<String>
        get() {
            val raw = p?.getString("dock", "") ?: ""
            if (raw.isEmpty()) return emptyList()
            return raw.split("|").filter { it.isNotEmpty() }
        }
        set(v) { p?.edit()?.putString("dock", v.joinToString("|"))?.apply() }

    var labels: Boolean
        get() = p?.getBoolean("labels", true) ?: true
        set(v) { p?.edit()?.putBoolean("labels", v)?.apply() }
}
