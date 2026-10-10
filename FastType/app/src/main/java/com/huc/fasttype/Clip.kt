package com.huc.fasttype

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What this person has copied lately.
 *
 * Android only lets an app read the clipboard while it has focus, and the active
 * input method counts — but only while it is on screen. So the history fills up as
 * the keyboard is used, and anything copied before it was ever opened is simply not
 * seen. Every other keyboard lives with the same limit.
 *
 * Nothing here leaves the phone.
 */
object Clip {

    private const val PREF = "huc_clip"
    private const val K_ITEMS = "items"
    private const val MAX = 90
    private const val MAX_LEN = 5000

    class Entry(val text: String, val at: Long, var pinned: Boolean)

    private val items = ArrayList<Entry>(MAX)

    @Volatile private var loaded = false
    private var ctx: Context? = null

    /** Set when something new was copied and not used yet — the key tints for it. */
    @Volatile var fresh = false
        private set

    val all: List<Entry> get() = items

    fun load(c: Context) {
        if (loaded) return
        ctx = c.applicationContext
        try {
            val raw = c.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(K_ITEMS, "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t")
                if (t.isEmpty()) continue
                items.add(Entry(t, o.optLong("a"), o.optBoolean("p", false)))
            }
        } catch (_: Exception) {
        }
        loaded = true
        expire()
    }

    /** Drops unpinned entries older than the chosen window. Pinned ones stay. */
    fun expire() {
        val mins = Store.kbClipExpire
        if (mins <= 0) return
        val cutoff = System.currentTimeMillis() - mins * 60_000L
        val before = items.size
        items.removeAll { !it.pinned && it.at < cutoff }
        if (items.size != before) save()
    }

    /** Set while the field on screen is a password or code box. */
    @Volatile var blocked = false

    /** Reads whatever is on the clipboard right now and files it. */
    fun capture(c: Context) {
        if (!Store.kbClip || blocked) return
        try {
            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(c)?.toString()?.trim()
                ?: return
            add(text)
        } catch (_: Exception) {
        }
    }

    fun add(text: String) {
        if (text.isEmpty() || text.length > MAX_LEN) return
        if (items.firstOrNull()?.text == text) return
        // the same thing copied again moves to the front rather than duplicating
        val existing = items.indexOfFirst { it.text == text }
        if (existing >= 0) {
            val e = items.removeAt(existing)
            items.add(0, Entry(text, System.currentTimeMillis(), e.pinned))
        } else {
            items.add(0, Entry(text, System.currentTimeMillis(), false))
        }
        while (items.size > MAX) {
            val last = items.indexOfLast { !it.pinned }
            if (last < 0) break
            items.removeAt(last)
        }
        fresh = true
        save()
    }

    // ---- what a clipping is ----
    //
    // Forty clippings in one list is a list nobody reads: he scrolls it looking
    // for the one he wants, which is the thing he was trying to avoid by having
    // a history at all. Sorting them by what they are costs nothing — the text
    // already says which it is — and turns the scroll into one tap.

    const val TEXT = 0
    const val LINK = 1
    const val NUM = 2
    const val CODE = 3

    private val reUrl = Regex("""(https?://|www\.)[^\s]+""")
    private val reMail = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val reNum = Regex("""[0-9\u0660-\u0669][0-9\u0660-\u0669 \-]{4,}""")

    fun kindOf(t: String): Int {
        val s = t.trim()
        if (reUrl.containsMatchIn(s) || reMail.containsMatchIn(s)) return LINK
        // a line of code: brackets, slashes and no Arabic in it
        if (s.none { it in '\u0600'..'\u06FF' } &&
            (s.contains('{') || s.contains('(') || s.contains(';') ||
                s.contains("&&") || s.contains("/") && s.contains(" "))
        ) return CODE
        val digits = s.count { it.isDigit() || it in '\u0660'..'\u0669' }
        if (digits >= 4 && digits >= s.length / 2) return NUM
        return TEXT
    }

    /**
     * The useful pieces inside a clipping.
     *
     * He copies a whole message to get one number out of it, pastes the lot and
     * deletes the rest. These are the parts worth pasting on their own; a piece
     * that is the whole clipping is not one.
     */
    fun bits(t: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in reUrl.findAll(t)) out.add(m.value.trim('.', '،', ')', '('))
        for (m in reMail.findAll(t)) out.add(m.value)
        for (m in reNum.findAll(t)) {
            val v = m.value.trim()
            if (v.replace(" ", "").replace("-", "").length >= 5) out.add(v)
        }
        val whole = t.trim()
        return out.filter { it != whole }.take(6)
    }

    /** Indices into [all], pinned first, narrowed by a kind and a search. */
    fun view(filter: Int, query: String): List<Int> {
        val q = query.trim()
        val idx = items.indices.filter { i ->
            val e = items[i]
            val okKind = when (filter) {
                -1 -> true
                -2 -> e.pinned
                else -> kindOf(e.text) == filter
            }
            okKind && (q.isEmpty() || e.text.contains(q, ignoreCase = true))
        }
        return idx.sortedByDescending { items[it].pinned }
    }

    fun countOf(filter: Int): Int = view(filter, "").size

    /** The queued clippings, pasted one per tap of the clipboard key. */
    val queue = ArrayList<Int>(6)

    fun queueToggle(i: Int) {
        if (!queue.remove(i)) queue.add(i)
    }

    fun queueNext(): String? {
        while (queue.isNotEmpty()) {
            val i = queue.removeAt(0)
            if (i in items.indices) return items[i].text
        }
        return null
    }

    fun latest(): String? = items.firstOrNull()?.text

    fun used() { fresh = false }

    fun togglePin(i: Int) {
        val e = items.getOrNull(i) ?: return
        e.pinned = !e.pinned
        save()
    }

    fun remove(i: Int) {
        if (i < 0 || i >= items.size) return
        items.removeAt(i)
        save()
    }

    /** Clears everything except what was pinned on purpose. */
    fun clearUnpinned() {
        items.removeAll { !it.pinned }
        fresh = false
        save()
    }

    private fun save() {
        val c = ctx ?: return
        try {
            val arr = JSONArray()
            for (e in items) {
                arr.put(JSONObject().put("t", e.text).put("a", e.at).put("p", e.pinned))
            }
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(K_ITEMS, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    /** "قبل دقيقة", "قبل ٣ ساعات" — short enough for a list row. */
    fun ago(at: Long): String {
        val m = ((System.currentTimeMillis() - at) / 60_000L).toInt()
        return when {
            m < 1 -> "هسه"
            m < 60 -> "قبل $m دقيقة"
            m < 1440 -> "قبل ${m / 60} ساعة"
            else -> "قبل ${m / 1440} يوم"
        }
    }
}
