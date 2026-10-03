package com.huc.fasttype

import android.content.Context
import java.io.BufferedReader
import java.util.Locale

/**
 * Word predictions and auto-correction.
 *
 * Each language ships one asset file: 30,000 words ordered by how common they are,
 * so a word's line number is its rank. Arabic is written with several spellings of
 * the same sound, so every lookup happens on a folded form while the dictionary
 * spelling is what gets shown. Both lists are built once on a background thread the
 * first time the keyboard needs them.
 */
object Dict {

    private const val CAP = 30000
    private const val EN_LETTERS = "abcdefghijklmnopqrstuvwxyz"
    private const val AR_LETTERS = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"

    /** [folded] is sorted; [shown] and [rank] line up with it index for index. */
    private class Lang(
        val folded: Array<String>,
        val shown: Array<String>,
        val rank: IntArray,
        val byFolded: HashMap<String, Int>
    )

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
                if (en == null) en = read(app, "dict_en.txt", false)
                if (ar == null) ar = read(app, "dict_ar.txt", true)
                if (nextEn.isEmpty()) nextEn = readNext(app, "bigrams_en.txt", false)
                if (nextAr.isEmpty()) nextAr = readNext(app, "bigrams_ar.txt", true)
            } catch (_: Throwable) {
            } finally {
                loading = false
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    val ready: Boolean get() = en != null || ar != null

    private fun read(ctx: Context, name: String, arabic: Boolean): Lang {
        val words = ArrayList<String>(CAP)
        ctx.assets.open(name).use { input ->
            BufferedReader(input.reader(Charsets.UTF_8), 1 shl 16).use { r ->
                var line = r.readLine()
                while (line != null && words.size < CAP) {
                    val w = line.trim()
                    if (w.isNotEmpty()) words.add(w)
                    line = r.readLine()
                }
            }
        }
        val n = words.size
        val order = (0 until n).sortedBy { fold(words[it], arabic) }
        val folded = Array(n) { fold(words[order[it]], arabic) }
        val shown = Array(n) { words[order[it]] }
        val rank = IntArray(n) { order[it] }
        val byFolded = HashMap<String, Int>(n * 2)
        for (i in 0 until n) {
            val cur = byFolded[folded[i]]
            if (cur == null || rank[i] < rank[cur]) byFolded[folded[i]] = i
        }
        return Lang(folded, shown, rank, byFolded)
    }

    /** One line per entry: the word, then the words that usually follow it. */
    private fun readNext(
        ctx: Context, name: String, arabic: Boolean
    ): HashMap<String, List<String>> {
        val map = HashMap<String, List<String>>(256)
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

    private fun lowerBound(a: Array<String>, key: String): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Up to [n] words that start with [prefix], most common first. */
    fun predict(prefix: String, arabic: Boolean, n: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val l = lang(arabic) ?: return emptyList()
        val p = fold(prefix, arabic)
        var i = lowerBound(l.folded, p)
        val hits = ArrayList<Int>(64)
        while (i < l.folded.size && l.folded[i].startsWith(p)) {
            hits.add(i)
            if (hits.size >= 250) break
            i++
        }
        if (hits.isEmpty()) return emptyList()
        hits.sortBy { l.rank[it] }
        val out = ArrayList<String>(n)
        for (idx in hits) {
            val w = l.shown[idx]
            if (w == prefix) continue
            out.add(w)
            if (out.size == n) break
        }
        return out
    }

    /** True when the word is spelled the way the dictionary has it. */
    fun known(word: String, arabic: Boolean): Boolean {
        val l = lang(arabic) ?: return true
        return l.byFolded.containsKey(fold(word, arabic))
    }

    /**
     * The most common word one edit away from [word], or null when the word is already
     * known, too short, or nothing close enough exists. Deliberately conservative — a
     * correction that fires on a word the writer meant is worse than no correction.
     */
    // How much less likely each kind of slip is than a neighbouring-key slip.
    // Without this the corrector picked whichever candidate was the commoner word,
    // so "كياتي" came back as "يأتي" — a dropped letter — instead of "حياتي", which
    // is one key away. Frequency alone is not evidence of what the hand did.
    private const val W_NEAR = 1.0f    // the key beside the one he wanted
    private const val W_FAR = 2.6f     // some other letter entirely
    private const val W_SWAP = 1.8f    // two letters the wrong way round
    private const val W_DROP = 3.6f    // a letter typed twice or one too many
    private const val W_ADD = 5.0f     // a letter missed out
    private const val CUT = 9000f      // past this, leave the word alone

    /**
     * Correction that knows what the finger was near.
     *
     * [nears] holds, for each typed letter, the letters whose keys sat beside it, so
     * a slip onto a neighbour is scored as the likely thing it is. Words shorter than
     * four letters are never touched: Iraqi dialect is full of short words no
     * dictionary has, and mangling those is worse than leaving a typo alone.
     */
    fun correctNear(word: String, nears: List<String>, arabic: Boolean): String? {
        val l = lang(arabic) ?: return null
        if (word.length < 4 || word.length > 18) return null
        val w = fold(word, arabic)
        if (w.isEmpty() || l.byFolded.containsKey(w)) return null

        val letters = if (arabic) AR_LETTERS else EN_LETTERS
        var bestIdx = -1
        var bestScore = CUT

        fun offer(cand: String, weight: Float) {
            val i = l.byFolded[cand] ?: return
            // the forty most common words are not immune; the offset keeps their
            // score from collapsing to nothing and winning on any weight
            val score = (l.rank[i] + 40) * weight
            if (score < bestScore) { bestScore = score; bestIdx = i }
        }

        for (i in w.indices) {
            val near = nears.getOrNull(i) ?: ""
            for (c in letters) {
                if (c == w[i]) continue
                offer(w.substring(0, i) + c + w.substring(i + 1),
                    if (near.indexOf(c) >= 0) W_NEAR else W_FAR)
            }
        }
        for (i in 0 until w.length - 1) {
            offer(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2), W_SWAP)
        }
        for (i in w.indices) offer(w.substring(0, i) + w.substring(i + 1), W_DROP)
        for (i in 0..w.length) {
            for (c in letters) offer(w.substring(0, i) + c + w.substring(i), W_ADD)
        }

        if (bestIdx < 0) return null
        val b = l.shown[bestIdx]
        return if (b == word) null else b
    }

    fun correct(word: String, arabic: Boolean): String? {
        val l = lang(arabic) ?: return null
        if (word.length < 3 || word.length > 18) return null
        val w = fold(word, arabic)
        if (l.byFolded.containsKey(w)) return null

        val letters = if (arabic) AR_LETTERS else EN_LETTERS
        var bestIdx = -1
        var bestRank = 12000

        fun offer(cand: String) {
            val i = l.byFolded[cand] ?: return
            if (l.rank[i] < bestRank) { bestRank = l.rank[i]; bestIdx = i }
        }

        for (i in w.indices) offer(w.substring(0, i) + w.substring(i + 1))
        for (i in 0 until w.length - 1) {
            offer(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
        }
        for (i in w.indices) for (c in letters) {
            if (c == w[i]) continue
            offer(w.substring(0, i) + c + w.substring(i + 1))
        }
        for (i in 0..w.length) for (c in letters) {
            offer(w.substring(0, i) + c + w.substring(i))
        }

        if (bestIdx < 0) return null
        val b = l.shown[bestIdx]
        return if (b == word) null else b
    }
}
