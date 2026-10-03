package com.huc.fasttype

import android.content.Context
import org.json.JSONObject

/**
 * The words this person actually writes.
 *
 * Every finished word is counted. A word written a few times starts showing up in the
 * predictions ahead of the general dictionary, and auto-correction stops touching it.
 * Undoing a correction marks the word as deliberate straight away, so the keyboard
 * never fights the same spelling twice.
 */
object UserDict {

    private const val PREF = "huc_userdict"
    private const val K_COUNTS = "counts"
    private const val K_KEEP = "keep"
    private const val K_PAIRS = "pairs"

    /** Beyond this the rarest entries are dropped, so the file cannot grow forever. */
    private const val MAX = 4000
    private const val TRIM_TO = 3000

    /** Seen this many times, a word is treated as the person's own spelling. */
    private const val OWN = 2

    private val counts = HashMap<String, Int>(512)

    /** "prev\u0000next" -> how often this person put those two words together. */
    private val pairs = HashMap<String, Int>(512)
    private const val MAX_PAIRS = 6000
    private val keep = HashSet<String>(128)

    @Volatile private var loaded = false
    @Volatile private var dirty = false
    private var ctx: Context? = null

    fun load(c: Context) {
        if (loaded) return
        ctx = c.applicationContext
        try {
            val p = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            JSONObject(p.getString(K_COUNTS, "{}") ?: "{}").let { o ->
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    counts[k] = o.optInt(k, 1)
                }
            }
            (p.getString(K_KEEP, "") ?: "").split('\n').forEach {
                if (it.isNotBlank()) keep.add(it)
            }
            JSONObject(p.getString(K_PAIRS, "{}") ?: "{}").let { o ->
                val it2 = o.keys()
                while (it2.hasNext()) {
                    val k = it2.next()
                    pairs[k] = o.optInt(k, 1)
                }
            }
        } catch (_: Exception) {
        }
        loaded = true
    }

    /** Counts one finished word. Cheap enough to call on every space. */
    fun seen(word: String, arabic: Boolean) {
        if (word.length < 2 || word.length > 24) return
        if (!isWordy(word, arabic)) return
        val k = Dict.fold(word, arabic)
        counts[k] = (counts[k] ?: 0) + 1
        dirty = true
        if (counts.size > MAX) trim()
    }

    /** Records that [next] followed [prev] in this person's own writing. */
    fun seenPair(prev: String, next: String, arabic: Boolean) {
        if (prev.length < 2 || next.length < 2) return
        if (!isWordy(prev, arabic) || !isWordy(next, arabic)) return
        val k = Dict.fold(prev, arabic) + "\u0000" + Dict.fold(next, arabic)
        pairs[k] = (pairs[k] ?: 0) + 1
        dirty = true
        if (pairs.size > MAX_PAIRS) trimPairs()
    }

    /** The words this person usually writes after [prev], most used first. */
    fun next(prev: String, arabic: Boolean, n: Int): List<String> {
        if (prev.isEmpty() || pairs.isEmpty()) return emptyList()
        val head = Dict.fold(prev, arabic) + "\u0000"
        val hits = ArrayList<Pair<String, Int>>(8)
        for ((k, c) in pairs) {
            if (k.startsWith(head)) hits.add(k.substring(head.length) to c)
        }
        if (hits.isEmpty()) return emptyList()
        hits.sortByDescending { it.second }
        return hits.take(n).map { it.first }
    }

    private fun trimPairs() {
        val keepers = pairs.entries.sortedByDescending { it.value }.take(MAX_PAIRS * 3 / 4)
        pairs.clear()
        for (e in keepers) pairs[e.key] = e.value
    }

    /** Marks a spelling as deliberate — used when a correction is undone. */
    fun keepAsIs(word: String, arabic: Boolean) {
        val k = Dict.fold(word, arabic)
        keep.add(k)
        counts[k] = (counts[k] ?: 0) + OWN
        dirty = true
        save()
    }

    /** True when this spelling must be left alone by auto-correction. */
    fun isOwn(word: String, arabic: Boolean): Boolean {
        val k = Dict.fold(word, arabic)
        if (keep.contains(k)) return true
        return (counts[k] ?: 0) >= OWN
    }

    /**
     * The person's own words starting with [prefix], the ones they write most first.
     * These go in front of the general dictionary's guesses.
     */
    fun predict(prefix: String, arabic: Boolean, n: Int): List<String> {
        if (prefix.isEmpty() || counts.isEmpty()) return emptyList()
        val p = Dict.fold(prefix, arabic)
        val hits = ArrayList<Pair<String, Int>>(16)
        for ((w, c) in counts) {
            if (c < OWN || w.length <= p.length) continue
            if (w.startsWith(p)) hits.add(w to c)
            if (hits.size > 200) break
        }
        if (hits.isEmpty()) return emptyList()
        hits.sortByDescending { it.second }
        return hits.take(n).map { it.first }
    }

    /** The person's most-written word one edit away from [word], if any. */
    fun correct(word: String, arabic: Boolean): String? {
        if (counts.isEmpty() || word.length < 3) return null
        val w = Dict.fold(word, arabic)
        var best: String? = null
        var bestCount = OWN + 1
        for ((cand, c) in counts) {
            if (c < bestCount) continue
            if (kotlin.math.abs(cand.length - w.length) > 1) continue
            if (!oneEdit(w, cand)) continue
            best = cand
            bestCount = c
        }
        return best
    }

    /** True when [a] and [b] differ by one insert, delete or replace. */
    private fun oneEdit(a: String, b: String): Boolean {
        if (a == b) return false
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > 1) return false
        var i = 0
        var j = 0
        var slips = 0
        while (i < la && j < lb) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++slips > 1) return false
            when {
                la > lb -> i++
                la < lb -> j++
                else -> { i++; j++ }
            }
        }
        if (i < la || j < lb) slips++
        return slips <= 1
    }

    private fun isWordy(s: String, arabic: Boolean): Boolean {
        for (c in s) {
            val ok = if (arabic) c in 'ء'..'ي' else c.isLetter() || c == '\''
            if (!ok) return false
        }
        return true
    }

    private fun trim() {
        val keepers = counts.entries.sortedByDescending { it.value }.take(TRIM_TO)
        counts.clear()
        for (e in keepers) counts[e.key] = e.value
    }

    /** Writes to disk only when something changed. Call when the keyboard closes. */
    fun save() {
        if (!dirty) return
        val c = ctx ?: return
        dirty = false
        try {
            val o = JSONObject()
            for ((k, v) in counts) o.put(k, v)
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(K_COUNTS, o.toString())
                .putString(K_KEEP, keep.joinToString("\n"))
                .putString(K_PAIRS, JSONObject().also { pj ->
                    for ((k, v) in pairs) pj.put(k, v)
                }.toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    /** How many words the keyboard has picked up — shown in the settings screen. */
    fun learned(): Int = counts.count { it.value >= OWN }

    fun forgetAll() {
        counts.clear()
        pairs.clear()
        keep.clear()
        dirty = true
        save()
    }
}
