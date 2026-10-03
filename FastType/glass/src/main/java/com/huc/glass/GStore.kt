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

    private fun str(k: String, d: String): String = p?.getString(k, d) ?: d
    private fun putStr(k: String, v: String) { p?.edit()?.putString(k, v)?.apply() }
    private fun int(k: String, d: Int): Int = p?.getInt(k, d) ?: d
    private fun putInt(k: String, v: Int) { p?.edit()?.putInt(k, v)?.apply() }
    private fun bool(k: String, d: Boolean): Boolean = p?.getBoolean(k, d) ?: d
    private fun putBool(k: String, v: Boolean) { p?.edit()?.putBoolean(k, v)?.apply() }

    /** "pkg/class" entries, in dock order. Empty means "work it out from the phone". */
    var dock: List<String>
        get() {
            val raw = str("dock", "")
            if (raw.isEmpty()) return emptyList()
            return raw.split("|").filter { it.isNotEmpty() }
        }
        set(v) { putStr("dock", v.joinToString("|")) }

    /** 0 = light frost, 1 = medium, 2 = heavy. */
    var glass: Int
        get() = int("glass", 1)
        set(v) { putInt("glass", if (v < 0) 0 else if (v > 2) 2 else v) }

    var labels: Boolean
        get() = bool("labels", true)
        set(v) { putBool("labels", v) }

    /** True once a photo of his own has been set as the background. */
    var ownWall: Boolean
        get() = bool("ownWall", false)
        set(v) { putBool("ownWall", v) }
}
