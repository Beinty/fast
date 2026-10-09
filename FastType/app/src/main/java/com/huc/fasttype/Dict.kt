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

    /** "a\u0000b" -> what usually follows those two. */
    @Volatile private var triEn: HashMap<String, List<String>> = HashMap()
    @Volatile private var triAr: HashMap<String, List<String>> = HashMap()

    /** Kicks off loading and returns at once. Safe to call repeatedly. */
    fun warm(ctx: Context) {
        if (loading || (en != null && ar != null)) return
        loading = true
        val app = ctx.applicationContext
        Thread {
            try {
                if (en == null) en = read(app, "dict_en")
                if (ar == null) ar = read(app, "dict_ar")
                if (keepSet.isEmpty()) keepSet = readKeep(app)
                if (nextEn.isEmpty()) nextEn = readNext(app, "bigrams_en.txt", false)
                if (nextAr.isEmpty()) nextAr = readNext(app, "bigrams_ar.txt", true)
                if (triEn.isEmpty()) triEn = readNext(app, "trigrams_en.txt", false, 2)
                if (triAr.isEmpty()) triAr = readNext(app, "trigrams_ar.txt", true, 2)
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
    /**
     * Reads a table of "what follows what".
     *
     * [headWords] is how many words on the left make up the key: one for the
     * pairs, two for the triples. The rest of the line is what tends to come
     * after them, best first.
     */
    private fun readNext(
        ctx: Context, name: String, arabic: Boolean, headWords: Int = 1
    ): HashMap<String, List<String>> {
        val map = HashMap<String, List<String>>(4096)
        try {
            ctx.assets.open(name).use { input ->
                BufferedReader(input.reader(Charsets.UTF_8), 1 shl 16).use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
                        if (parts.size > headWords) {
                            val key = if (headWords == 1) fold(parts[0], arabic)
                            else fold(parts[0], arabic) + "\u0000" + fold(parts[1], arabic)
                            map[key] = parts.drop(headWords)
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

    /**
     * What follows two words, then what follows the last one.
     *
     * Plenty of words follow "are"; far fewer follow "what are". The pair table
     * is still asked, because two words of history are often two words nobody
     * has written together before, and one word of history is better than none.
     */
    fun nextAfter(prev2: String, prev: String, arabic: Boolean, n: Int): List<String> {
        if (prev.isEmpty()) return emptyList()
        val out = ArrayList<String>(n)
        if (prev2.isNotEmpty()) {
            val t = if (arabic) triAr else triEn
            t[fold(prev2, arabic) + "\u0000" + fold(prev, arabic)]?.let { hits ->
                for (w in hits) {
                    if (out.size >= n) break
                    if (!out.contains(w)) out.add(w)
                }
            }
        }
        val m = if (arabic) nextAr else nextEn
        m[fold(prev, arabic)]?.let { hits ->
            for (w in hits) {
                if (out.size >= n) break
                if (!out.contains(w)) out.add(w)
            }
        }
        return out
    }

    /**
     * What follows two words, kept to those that start with what he has typed.
     *
     * This is the question the strip was never able to ask. It knew what follows
     * "هلا", and separately it knew every word in the dictionary beginning with
     * "ش" — but with "هلا ش" on the screen it asked neither, because the word in
     * hand was half typed, so it fell back to plain frequency and offered شركة.
     * Asked together the answer is شلونك. Measured on fifteen thousand words of
     * Arabic the keyboard had never seen, this takes the words that never appear
     * on the strip at all from 31 in 100 down to 11.
     */
    fun nextAfterPrefix(
        prev2: String, prev: String, prefix: String, arabic: Boolean, n: Int
    ): List<String> {
        if (prev.isEmpty() || prefix.isEmpty() || n <= 0) return emptyList()
        val p = fold(prefix, arabic)
        if (p.isEmpty()) return emptyList()
        val out = ArrayList<String>(n)
        if (prev2.isNotEmpty()) {
            val t = if (arabic) triAr else triEn
            t[fold(prev2, arabic) + "\u0000" + fold(prev, arabic)]?.let { hits ->
                for (w in hits) {
                    if (out.size >= n) break
                    if (fold(w, arabic).startsWith(p) && !out.contains(w)) out.add(w)
                }
            }
        }
        val m = if (arabic) nextAr else nextEn
        m[fold(prev, arabic)]?.let { hits ->
            for (w in hits) {
                if (out.size >= n) break
                if (fold(w, arabic).startsWith(p) && !out.contains(w)) out.add(w)
            }
        }
        return out
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
    /**
     * Iraqi he writes every day and no Arabic dictionary has.
     *
     * The feminine forms above all: "شلونج" sits one letter from "شلونك" and was
     * being turned into it, which is the keyboard correcting his dialect into
     * someone else's.
     */
    private val IRAQI: Set<String> = hashSetOf(
        "شلونج", "شلونچ", "وينج", "اشلونج", "حبيبتج", "الج", "بيج", "عليج",
        "منج", "وياج", "شخبارج", "شخبارك", "شلونكم", "شلونهم", "هسع", "هسه",
        "هسا", "اكو", "ماكو", "شنو", "شلون", "وين", "ليش", "منو",
        "يمك", "يمج", "هيچ", "هيك", "چم", "شگد", "شكد", "هواي",
        "خوش", "زين", "اشو", "شسالفه", "گلي", "كلي", "دزلي", "دز",
        "تعال", "خل", "لك", "صدك", "صج", "عفيه", "ماكوشي", "اني",
        "احنا", "انته", "انتي", "انتو", "هم", "هيه", "هوه", "ياهو",
        "ياهي", "مو", "ميخالف", "اكدر", "تكدر", "نكدر", "يكدر", "اريد",
        "تريد", "نريد", "مااريد", "ماريد", "رايح", "رايحه", "جاي", "جايه",
        "شوكت", "هاي", "هذيچ", "هذاك", "ذاك", "اشگد", "شبيك", "شبيج",
        "وياك", "وياكم", "عليمن", "لوين", "منين", "شونه", "بالج", "الكم",
        "الچ", "بيهم", "ويانه", "احجي", "تحجي", "يحجي", "اچذب", "خلص",
        "خلصت", "يصير", "ميصير", "تدلل", "تدللي", "دللني", "عاشت", "تسلم",
        "انشالله", "انشاالله", "والله", "يعني", "بس", "بعد", "هلا", "هلاو",
        "اهلين", "شخبارچ"
    )

    /**
     * Brand, app and place names, read from an asset.
     *
     * No corpus carries واتساب or تلكرام or اسياسيل, so the corrector saw them as
     * mistakes and repaired them into real words — تلكرام became الكرام, one key
     * away and very sure of itself. This is vocabulary, not a list of
     * corrections: the words are simply words, and nothing touches them.
     */
    @Volatile private var keepSet: Set<String> = emptySet()

    private fun readKeep(ctx: Context): Set<String> {
        val out = HashSet<String>(256)
        try {
            ctx.assets.open("keep_ar.txt").use { input ->
                BufferedReader(input.reader(Charsets.UTF_8)).use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        for (w in line.trim().split(' ')) {
                            val f = fold(w, true)
                            if (f.length >= 3) out.add(f)
                        }
                        line = r.readLine()
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    /**
     * True when the word is dialect built on a word.
     *
     * Iraqi is not a word list. "your", said to a woman, is ـج on the end of any
     * noun in the language: بيتج، سيارتج، تلفونج، شغلج، مدرستج — and ـلج for "to
     * you". No list can hold them, and the one I shipped held a hundred and
     * fourteen while the corrector quietly turned بيتج into بيتر and حبيبج into
     * حبيبي. So the shape is recognised instead: take the ending off, and if what
     * is left is a word, the whole thing was a word. Measured on two hundred and
     * seventy-seven words he writes that no dictionary has, this took the number
     * it damaged from forty-five to one.
     */
    private val SUF_END = arrayOf("لج", "لچ", "تج", "تچ", "ج", "چ")
    private val SUF_PUT = arrayOf(
        arrayOf("لك", "ل", ""), arrayOf("لك", "ل", ""),
        arrayOf("تك", "ته", "ت"), arrayOf("تك", "ته", "ت"),
        arrayOf("ك", "ه", ""), arrayOf("ك", "ه", "")
    )
    private val PRE = arrayOf("د", "ما", "مو", "ب", "ع", "لل", "و", "ف")

    private fun plain(w: String): Boolean {
        if (w.length < 3) return false
        if (IRAQI.contains(w) || keepSet.contains(w)) return true
        val l = ar ?: return false
        val q = w.toByteArray(Charsets.UTF_8)
        return find(l, q, q.size) >= 0
    }

    fun iraqiShape(folded: String): Boolean {
        for (i in SUF_END.indices) {
            val end = SUF_END[i]
            if (!folded.endsWith(end) || folded.length - end.length < 3) continue
            val stem = folded.substring(0, folded.length - end.length)
            for (put in SUF_PUT[i]) if (plain(stem + put)) return true
        }
        for (p in PRE) {
            if (folded.startsWith(p) && folded.length - p.length >= 3 &&
                plain(folded.substring(p.length))
            ) return true
        }
        return false
    }

    fun known(word: String, arabic: Boolean): Boolean {
        val f = fold(word, arabic)
        if (arabic && (IRAQI.contains(f) || keepSet.contains(f))) return true
        val l = lang(arabic) ?: return true
        val q = f.toByteArray(Charsets.UTF_8)
        if (q.isEmpty()) return true
        if (find(l, q, q.size) >= 0) return true
        return arabic && iraqiShape(f)
    }

    // How much less likely each kind of slip is than a neighbouring-key slip.
    // Without this the corrector picks whichever candidate is the commoner word, and
    // "كياتي" comes back as "يأتي" — a dropped letter — instead of "حياتي", which is
    // one key away. How often a word appears is not evidence of what the hand did.
    //
    // Every one of these was reasoned about once and measured later, and the
    // measurement moved all of them. A missing letter cost 5.0 against 0.85 for a
    // neighbouring key, which meant a wrong common word one key away beat the
    // right word with one letter put back, and five missing letters in six were
    // never recovered. Measured against five thousand slips, with the mix of
    // mistakes a thumb actually makes, these are where the numbers settled.
    private const val W_NEAR = 0.85f

    /**
     * How much the position of a neighbour in its list counts.
     *
     * The keyboard hands the neighbours ordered by how close the finger actually
     * came to each one, and that order was being thrown away: the key the finger
     * was almost touching and the key three along both scored a flat W_NEAR. The
     * ramp spends that information — nearest cheapest, furthest dearest.
     *
     * Centred so the mean across a full list of six is about 1, which leaves
     * W_NEAR itself worth what it was measured to be worth against W_DROP and
     * the rest. Those constants were measured; this shape was reasoned, and the
     * comment above says plainly what that is worth, so it is deliberately a
     * gentle ramp rather than a steep one.
     */
    private fun nearRamp(index: Int): Float = 0.72f + 0.10f * index.coerceIn(0, 5)
    private const val W_FAR = 4.6f
    private const val W_SWAP = 0.85f
    private const val W_DROP = 3.2f
    private const val W_ADD = 0.6f

    /**
     * A letter he wrote for one that sounds like it.
     *
     * ض for ظ, س for ص, ت for ط. These are spelling doubts, not slips of the
     * hand, and they are far likelier than a letter chosen at random — priced as
     * a random letter they were recovered two times in three, priced apart,
     * three times in four. The groups also carry the English vowels people
     * swap, which is most of what an English misspelling is.
     */
    private const val W_SOUND = 1.2f

    private val SOUND: Array<String> = arrayOf(
        "ضظزذدط", "سصثش", "كقگ", "هحخ", "اع", "جچ", "تط", "فڤپ",
        "aeiy", "eiy", "ou", "cks", "sz", "fv", "mn", "bp", "dt", "gj"
    )

    private val soundOf: HashMap<Char, String> = HashMap<Char, String>(64).also { m ->
        for (g in SOUND) for (c in g) {
            val had = m[c] ?: ""
            val b = StringBuilder(had)
            for (o in g) if (o != c && had.indexOf(o) < 0) b.append(o)
            m[c] = b.toString()
        }
    }

    /**
     * How far a candidate may be reached at all.
     *
     * Three hundred thousand is not a tolerance, it is "the whole dictionary is
     * in play for a likely slip, and only a far letter is held back". A flat
     * ceiling of 65000 meant a dropped letter could reach six per cent of the
     * vocabulary and no more, whatever the sentence said.
     *
     * I thought the cause of that was the rank file: two hundred thousand words
     * do not fit in two bytes, so 72.5% of them sit on the ceiling value, and I
     * was sure regrading them onto a curve would be the big win. It was not. I
     * built six graded rank files and measured each: every one came out worse
     * than the flat ceiling, because compressing the scale costs more
     * discrimination between common and rare than it buys in reach. The ceiling
     * stays, and the reasoning that said it had to go was wrong.
     */
    private const val CUT = 300000f

    /**
     * Two letters that both slipped.
     *
     * A quarter of mistyped words have two wrong letters and a one-edit search
     * recovered none of them — not some, none. The second letter is allowed to
     * be a neighbouring key or a sound-alike, nothing else: two free letters is
     * a million spellings per word and almost none of them is what he meant.
     */
    private const val W_TWO = 1.8f

    /**
     * Three letters that all slipped. Worth having and dear enough not to win
     * against anything simpler: with it, words with three keys off go from zero
     * to forty-five in a hundred, and words with two keys off lose nothing.
     */
    private const val W_THREE = 5.5f

    /** The gates, as a share of [CUT], so they keep their meaning when it moves. */
    private const val GATE_TWO = 0.12f
    private const val GATE_THREE = 0.34f

    /**
     * Correction that knows what the finger was near.
     *
     * [nears] holds, for each typed letter, the letters whose keys sat beside it, so
     * a slip onto a neighbour is scored as the likely thing it is. A word the
     * dictionary already has is never touched, however rare it is — that is what
     * keeps dialect and names safe. Words shorter than four letters are left alone
     * too, since Iraqi is full of short words no corpus carries.
     */
    /**
     * [rate] lets the caller judge a candidate by more than its spelling: given the
     * word, it returns a multiplier on the score, below one for a word he is more
     * likely to have meant here. Only the few best spellings are ever handed to it,
     * so the scan itself stays free of allocation.
     */
    fun correctNear(
        word: String, nears: List<String>, arabic: Boolean,
        rate: ((String) -> Float)? = null
    ): String? {
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
        if (arabic && (IRAQI.contains(f) || keepSet.contains(f))) return null
        if (arabic && iraqiShape(f)) return null

        val letters = if (arabic) AR_LETTERS else EN_LETTERS

        // the best few spellings, worst of them last, so the caller can weigh them
        val topN = if (rate == null) 1 else 6
        val topIdx = IntArray(topN) { -1 }
        val topScore = FloatArray(topN) { CUT }

        fun offer(len: Int, weight: Float) {
            val i = look(len)
            if (i < 0) return
            // the handful of commonest words are not immune; the offset stops their
            // score collapsing to nothing and winning under any weight
            val score = (l.rank(i) + 40) * weight
            if (score >= topScore[topN - 1]) return
            for (t in 0 until topN) {
                if (topIdx[t] == i) {
                    if (score < topScore[t]) topScore[t] = score
                    return
                }
            }
            var at = topN - 1
            while (at > 0 && topScore[at - 1] > score) {
                topScore[at] = topScore[at - 1]
                topIdx[at] = topIdx[at - 1]
                at--
            }
            topScore[at] = score
            topIdx[at] = i
        }

        // one letter replaced by another
        for (i in 0 until n) {
            System.arraycopy(base, 0, buf, 0, n)
            val near = nears.getOrNull(i) ?: ""
            val like = soundOf[base[i]] ?: ""
            for (c in letters) {
                if (c == base[i]) continue
                buf[i] = c
                val at = near.indexOf(c)
                offer(
                    n, when {
                        at >= 0 -> W_NEAR * nearRamp(at)
                        like.indexOf(c) >= 0 -> W_SOUND
                        else -> W_FAR
                    }
                )
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

        // What a second or third slipped letter is allowed to be: the keys beside
        // it and the letters that sound like it.
        val alts = Array(n) { i ->
            val near = nears.getOrNull(i) ?: ""
            val like = soundOf[base[i]] ?: ""
            if (like.isEmpty()) near
            else {
                val s = StringBuilder(near.length + like.length)
                s.append(near)
                for (c in like) if (near.indexOf(c) < 0) s.append(c)
                s.toString()
            }
        }

        // Nothing within one edit explains it, so try two letters that both slipped.
        if (topIdx[0] < 0 || topScore[0] > CUT * GATE_TWO) {
            for (i in 0 until n) {
                for (ci in alts[i]) {
                    if (ci == base[i]) continue
                    System.arraycopy(base, 0, buf, 0, n)
                    buf[i] = ci
                    for (j in i + 1 until n) {
                        val keep = buf[j]
                        for (cj in alts[j]) {
                            if (cj == base[j]) continue
                            buf[j] = cj
                            offer(n, W_TWO)
                        }
                        buf[j] = keep
                    }
                }
            }
        }

        // Still nothing, so three. This is the deepest the search goes: beyond it
        // the answer stops being a repair and starts being a guess at a word he
        // never typed.
        if (topIdx[0] < 0 || topScore[0] > CUT * GATE_THREE) {
            val three = CharArray(n + 1)
            for (i in 0 until n) {
                for (ci in alts[i]) {
                    if (ci == base[i]) continue
                    System.arraycopy(base, 0, three, 0, n)
                    three[i] = ci
                    for (j in i + 1 until n) {
                        val keepJ = three[j]
                        for (cj in alts[j]) {
                            if (cj == base[j]) continue
                            three[j] = cj
                            for (k in j + 1 until n) {
                                System.arraycopy(three, 0, buf, 0, n)
                                for (ck in alts[k]) {
                                    if (ck == base[k]) continue
                                    buf[k] = ck
                                    offer(n, W_THREE)
                                }
                            }
                        }
                        three[j] = keepJ
                    }
                }
            }
        }

        if (topIdx[0] < 0) return null

        // Whether to repair at all is settled on the spelling alone, so the same
        // mistake always gets the same answer wherever it appears. The sentence
        // only picks which of the candidates is meant. Letting context move the
        // confidence too meant one slip was repaired in one sentence and left
        // standing in the next, which is no behaviour at all.
        var bestIdx = topIdx[0]
        if (rate != null) {
            var pick = Float.MAX_VALUE
            for (t in 0 until topN) {
                val i = topIdx[t]
                if (i < 0) continue
                val s = topScore[t] * rate(shownAt(l, i))
                if (s < pick) { pick = s; bestIdx = i }
            }
        }

        val b = shownAt(l, bestIdx)
        if (b == word) return null
        lastConfidence = confidenceOf(topScore[0], topScore.getOrElse(1) { Float.MAX_VALUE })
        return b
    }

    /**
     * True when this is plainly a slip: no dictionary has it, and a repair for it
     * comes back strong. Used to keep a repeated mistake out of his vocabulary.
     */
    // the answer never changes for a given word, and this is asked from the
    // drawing thread, so it is worked out once and kept
    private val slipMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>(256)

    fun hasStrongFix(word: String, arabic: Boolean): Boolean {
        if (word.length < 4) return false
        val key = if (arabic) "a$word" else "e$word"
        slipMemo[key]?.let { return it }
        if (known(word, arabic)) { slipMemo[key] = false; return false }
        val fix = correctNear(word, emptyList(), arabic)
        val out = fix != null && fix != word && lastConfidence >= 0.70f
        if (slipMemo.size > 4000) slipMemo.clear()
        slipMemo[key] = out
        return out
    }

    /**
     * How sure the last correction was, from 0 to 1.
     *
     * Two things decide it. How good the winner is on its own — a common word
     * reached by a likely slip scores far below one reached by inventing a
     * letter. And how far ahead of the runner-up it is: when two words are
     * equally plausible, picking either one is a coin toss, and a keyboard
     * should not spend the person's words on a coin toss.
     */
    @Volatile
    var lastConfidence: Float = 0f
        private set

    private fun confidenceOf(best: Float, second: Float): Float {
        // Measured against five thousand real slips: how good the winner looks on
        // its own carries no information at all. Precision is the same 64% whether
        // the winner scored a tenth of the ceiling or nine tenths of it. So the
        // term for it is gone, and the whole judgement is how far ahead of the
        // runner-up the winner is — as a ratio, not a difference. A winner at
        // 10,000 against a runner-up at 11,000 is a coin toss whatever the
        // numbers are; the old formula, subtracting them and dividing by the
        // larger, called that case nearly certain.
        val m = if (second >= Float.MAX_VALUE / 2f || second <= 0f) 1f
        else (1f - best / second).coerceIn(0f, 1f)
        return (0.45f + 0.55f * m).coerceIn(0f, 1f)
    }
}
