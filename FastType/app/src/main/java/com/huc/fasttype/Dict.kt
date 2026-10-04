package com.huc.fasttype

import android.content.Context
import java.io.BufferedReader
import java.util.Locale

/**
 * Word predictions and auto-correction.
 *
 * The old list held thirty thousand words per language. For English that is thin;
 * for Arabic it is nothing, because Arabic carries its grammar inside the word —
 * "و", "ف", "ب", "ك", "ل" and "ال" stick to the front and the pronouns stick to the
 * back, so one root turns into dozens of written forms and almost none of them were
 * in the list. The result was a keyboard that offered few guesses and corrected
 * almost nothing, since a word it has never heard of is a word it cannot repair.
 *
 * Now each language ships about a fifth of a million forms, taken in frequency order
 * from real text, plus a hand-written set of Iraqi words that no corpus carries. At
 * that size the old layout — three arrays of Strings and a HashMap — would cost a few
 * hundred megabytes, so the words live as one block of UTF-8 bytes, sorted by their
 * folded spelling, searched in place. Nothing is decoded until there is an answer
 * to hand back.
 *
 * File shape, per language:
 *   dict_xx.bin   one entry per line, sorted. "folded" alone, or "folded\tshown"
 *                 when the two differ (أحمد folds to احمد and must still be shown
 *                 with its hamza).
 *   dict_xx.rnk   two bytes per entry, big endian: where that word sits in the
 *                 frequency order, capped at 65535.
 */
object Dict {

    private const val EN_LETTERS = "abcdefghijklmnopqrstuvwxyz"
    private const val AR_LETTERS = "ابتثجحخدذرزسشصضطظعغفقكلمنهويچگپڤ"

    private const val NL = '\n'.code.toByte()
    private const val TAB = '\t'.code.toByte()

    private class Lang(
        val blob: ByteArray,
        /** Byte offset of each entry; [count] + 1 long, so start[i+1]-1 ends entry i. */
        val start: IntArray,
        val rnk: ByteArray,
        val count: Int
    ) {
        fun rank(i: Int): Int =
            ((rnk[i * 2].toInt() and 0xFF) shl 8) or (rnk[i * 2 + 1].toInt() and 0xFF)
    }

    @Volatile private var en: Lang? = null
    @Volatile private var ar: Lang? = null
    @Volatile private var loading = false

    /** Folded word -> the words that commonly follow it, best first. */
    @Volatile private var nextEn: HashMap<String, List<String>> = HashMap()
    @Volatile private var nextAr: HashMap<String, List<String>> = HashMap()

    /** Kicks off loading and returns at once. Safe to call repeatedly. */
    fun warm(ctx: Context) {
        if (loading || (en != null && ar != null)) return
        loading = true
        val app = ctx.applicationContext
        Thread {
            try {
                if (en == null) en = read(app, "dict_en")
                if (ar == null) ar = read(app, "dict_ar")
                if (nextEn.isEmpty()) nextEn = readNext(app, "bigrams_en.txt", false)
                if (nextAr.isEmpty()) nextAr = readNext(app, "bigrams_ar.txt", true)
            } catch (_: Throwable) {
            } finally {
                loading = false
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    val ready: Boolean get() = en != null || ar != null

    private fun readAll(ctx: Context, name: String): ByteArray =
        ctx.assets.open(name).use { it.readBytes() }

    private fun read(ctx: Context, base: String): Lang {
        val blob = readAll(ctx, "$base.bin")
        val rnk = readAll(ctx, "$base.rnk")

        var lines = 1
        for (b in blob) if (b == NL) lines++
        val start = IntArray(lines + 1)
        var n = 0
        start[n++] = 0
        for (i in blob.indices) {
            if (blob[i] == NL) start[n++] = i + 1
        }
        start[n] = blob.size + 1
        val count = minOf(n, rnk.size / 2)
        return Lang(blob, start, rnk, count)
    }

    /** One line per entry: the word, then the words that usually follow it. */
    private fun readNext(
        ctx: Context, name: String, arabic: Boolean
    ): HashMap<String, List<String>> {
        val map = HashMap<String, List<String>>(512)
        try {
            ctx.assets.open(name).use { input ->
                BufferedReader(input.reader(Charsets.UTF_8), 1 shl 14).use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
                        if (parts.size >= 2) {
                            map[fold(parts[0], arabic)] = parts.drop(1)
                        }
                        line = r.readLine()
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return map
    }

    /** Words that commonly follow [prev]. Empty when nothing is known about it. */
    fun nextWords(prev: String, arabic: Boolean, n: Int = 3): List<String> {
        if (prev.isEmpty()) return emptyList()
        val m = if (arabic) nextAr else nextEn
        return m[fold(prev, arabic)]?.take(n) ?: emptyList()
    }

    private fun lang(arabic: Boolean): Lang? = if (arabic) ar else en

    /** Lower-cases English; strips Arabic diacritics and unifies hamza shapes. */
    fun fold(s: String, arabic: Boolean): String {
        if (!arabic) return s.lowercase(Locale.ROOT)
        val b = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                'أ', 'إ', 'آ', 'ٱ' -> b.append('ا')
                'ى' -> b.append('ي')
                'ؤ' -> b.append('و')
                'ئ' -> b.append('ي')
                'ة' -> b.append('ه')
                'ـ' -> {}
                in 'ً'..'ْ' -> {}
                else -> b.append(c)
            }
        }
        return b.toString()
    }

    // ---- searching the block, without decoding it --------------------------

    /** Compares entry [i]'s folded part against [q], the way strings compare. */
    private fun cmp(l: Lang, i: Int, q: ByteArray, qLen: Int): Int {
        var a = l.start[i]
        val end = l.start[i + 1] - 1
        var b = 0
        while (a < end && b < qLen) {
            val c = l.blob[a]
            if (c == TAB) break
            val d = (c.toInt() and 0xFF) - (q[b].toInt() and 0xFF)
            if (d != 0) return d
            a++
            b++
        }
        val aDone = a >= end || l.blob[a] == TAB
        val bDone = b >= qLen
        return if (aDone && bDone) 0 else if (aDone) -1 else 1
    }

    private fun startsWith(l: Lang, i: Int, q: ByteArray, qLen: Int): Boolean {
        var a = l.start[i]
        val end = l.start[i + 1] - 1
        var b = 0
        while (b < qLen) {
            if (a >= end) return false
            val c = l.blob[a]
            if (c == TAB || c != q[b]) return false
            a++
            b++
        }
        return true
    }

    private fun lowerBound(l: Lang, q: ByteArray, qLen: Int): Int {
        var lo = 0
        var hi = l.count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cmp(l, mid, q, qLen) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun find(l: Lang, q: ByteArray, qLen: Int): Int {
        val i = lowerBound(l, q, qLen)
        if (i >= l.count) return -1
        return if (cmp(l, i, q, qLen) == 0) i else -1
    }

    /** The spelling to show: after the tab when there is one, else the whole entry. */
    private fun shownAt(l: Lang, i: Int): String {
        val from = l.start[i]
        val to = l.start[i + 1] - 1
        var t = from
        while (t < to && l.blob[t] != TAB) t++
        return if (t < to) String(l.blob, t + 1, to - t - 1, Charsets.UTF_8)
        else String(l.blob, from, to - from, Charsets.UTF_8)
    }

    /** UTF-8 for anything up to U+07FF, which covers Latin and Arabic. */
    private fun encode(src: CharArray, len: Int, out: ByteArray): Int {
        var o = 0
        for (i in 0 until len) {
            val c = src[i].code
            if (c < 0x80) {
                out[o++] = c.toByte()
            } else {
                out[o++] = (0xC0 or (c shr 6)).toByte()
                out[o++] = (0x80 or (c and 0x3F)).toByte()
            }
        }
        return o
    }

    // ---- what the keyboard asks for ----------------------------------------

    /** Up to [n] words that start with [prefix], most common first. */
    fun predict(prefix: String, arabic: Boolean, n: Int = 3): List<String> {
        if (prefix.isEmpty() || n <= 0) return emptyList()
        val l = lang(arabic) ?: return emptyList()
        val p = fold(prefix, arabic)
        if (p.isEmpty()) return emptyList()
        val q = p.toByteArray(Charsets.UTF_8)

        var i = lowerBound(l, q, q.size)
        val idx = IntArray(n)
        val rk = IntArray(n)
        var have = 0
        var worst = Int.MAX_VALUE
        var scanned = 0

        while (i < l.count && scanned < 150000) {
            if (!startsWith(l, i, q, q.size)) break
            val r = l.rank(i)
            // Most of a common prefix is rare words. Rejecting on the rank first
            // means the usual answer is found without touching the text at all.
            if (have < n || r < worst) {
                var j = have
                while (j > 0 && rk[j - 1] > r) {
                    if (j < n) { rk[j] = rk[j - 1]; idx[j] = idx[j - 1] }
                    j--
                }
                if (j < n) { rk[j] = r; idx[j] = i }
                if (have < n) have++
                worst = rk[have - 1]
            }
            i++
            scanned++
        }

        if (have == 0) return emptyList()
        val out = ArrayList<String>(have)
        for (k in 0 until have) {
            val w = shownAt(l, idx[k])
            if (w != prefix) out.add(w)
        }
        return out
    }

    /** True when the word is spelled the way the dictionary has it. */
    fun known(word: String, arabic: Boolean): Boolean {
        val l = lang(arabic) ?: return true
        val q = fold(word, arabic).toByteArray(Charsets.UTF_8)
        if (q.isEmpty()) return true
        return find(l, q, q.size) >= 0
    }

    // How much less likely each kind of slip is than a neighbouring-key slip.
    // Without this the corrector picks whichever candidate is the commoner word, and
    // "كياتي" comes back as "يأتي" — a dropped letter — instead of "حياتي", which is
    // one key away. How often a word appears is not evidence of what the hand did.
    private const val W_NEAR = 1.0f
    private const val W_FAR = 2.6f
    private const val W_SWAP = 1.8f
    private const val W_DROP = 3.6f
    private const val W_ADD = 5.0f

    // Raised from 9000 when the dictionary grew: with two hundred thousand words a
    // perfectly ordinary word like "مشكور" sits at rank 27000, and the old ceiling
    // put it out of reach. Measured against thirty-one dialect words and names, this
    // repairs ten typos in twelve and damages none of them.
    private const val CUT = 30000f

    /**
     * Correction that knows what the finger was near.
     *
     * [nears] holds, for each typed letter, the letters whose keys sat beside it, so
     * a slip onto a neighbour is scored as the likely thing it is. A word the
     * dictionary already has is never touched, however rare it is — that is what
     * keeps dialect and names safe. Words shorter than four letters are left alone
     * too, since Iraqi is full of short words no corpus carries.
     */
    fun correctNear(word: String, nears: List<String>, arabic: Boolean): String? {
        val l = lang(arabic) ?: return null
        if (word.length < 4 || word.length > 18) return null
        val f = fold(word, arabic)
        val n = f.length
        if (n < 4) return null

        val base = CharArray(n + 1)
        for (i in 0 until n) base[i] = f[i]
        val buf = CharArray(n + 1)
        val bytes = ByteArray((n + 1) * 2)

        fun look(len: Int): Int {
            val bl = encode(buf, len, bytes)
            return find(l, bytes, bl)
        }

        // already a word he could have meant
        System.arraycopy(base, 0, buf, 0, n)
        if (look(n) >= 0) return null

        val letters = if (arabic) AR_LETTERS else EN_LETTERS
        var bestIdx = -1
        var bestScore = CUT

        fun offer(len: Int, weight: Float) {
            val i = look(len)
            if (i < 0) return
            // the handful of commonest words are not immune; the offset stops their
            // score collapsing to nothing and winning under any weight
            val score = (l.rank(i) + 40) * weight
            if (score < bestScore) { bestScore = score; bestIdx = i }
        }

        // one letter replaced by another
        for (i in 0 until n) {
            System.arraycopy(base, 0, buf, 0, n)
            val near = nears.getOrNull(i) ?: ""
            for (c in letters) {
                if (c == base[i]) continue
                buf[i] = c
                offer(n, if (near.indexOf(c) >= 0) W_NEAR else W_FAR)
            }
        }

        // two letters the wrong way round
        for (i in 0 until n - 1) {
            System.arraycopy(base, 0, buf, 0, n)
            buf[i] = base[i + 1]
            buf[i + 1] = base[i]
            offer(n, W_SWAP)
        }

        // one letter too many
        if (n > 4) {
            for (i in 0 until n) {
                System.arraycopy(base, 0, buf, 0, i)
                System.arraycopy(base, i + 1, buf, i, n - i - 1)
                offer(n - 1, W_DROP)
            }
        }

        // one letter missing
        for (i in 0..n) {
            System.arraycopy(base, 0, buf, 0, i)
            System.arraycopy(base, i, buf, i + 1, n - i)
            for (c in letters) {
                buf[i] = c
                offer(n + 1, W_ADD)
            }
        }

        if (bestIdx < 0) return null
        val b = shownAt(l, bestIdx)
        return if (b == word) null else b
    }
}
