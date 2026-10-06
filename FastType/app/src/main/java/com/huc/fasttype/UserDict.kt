package com.huc.fasttype

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * The words this person actually writes.
 *
 * Every finished word is counted, together with how recently it was written and
 * what word came before it. That is enough for the keyboard to stop guessing
 * from a general dictionary and start answering from his own writing: his words
 * come up first, a slip he makes twice is repaired on sight, and a correction
 * picks the word he would actually have used in that place rather than whichever
 * one happens to be closest letter by letter.
 */
object UserDict {

    private const val PREF = "huc_userdict"
    private const val K_COUNTS = "counts"
    private const val K_KEEP = "keep"
    private const val K_PAIRS = "pairs"
    private const val K_FIXES = "fixes"
    private const val K_RECENT = "recent"
    private const val K_TICK = "tick"
    private const val K_TRI = "tri"
    private const val K_FIXUSE = "fixuse"
    private const val K_REJECT = "reject"

    /**
     * How much is kept in memory — not how much he can learn.
     *
     * These used to be the same number, and that was the whole problem. Everything
     * lived in one JSON string that was rewritten on every space, so the cap had
     * to be small enough to rewrite cheaply, and past it he simply stopped
     * learning. Now [Learn] holds every row on disk and these only say how much of
     * it is held in front of the hand. The hot set is what answers a keystroke, so
     * it must never be asked to search a database; the rest waits on disk and comes
     * back up the next time the keyboard starts.
     *
     * Thirty thousand words is about ten times a large personal vocabulary, so in
     * practice the hot set is everything he has, and the trim below never runs.
     */
    private const val HOT_WORDS = 30000
    private const val HOT_TRIM = 24000

    /** Seen this many times, a word is treated as the person's own spelling. */
    // Two was too low: one word typed wrong twice became protected for good, and
    // the keyboard then refused to fix it ever again.
    private const val OWN = 4

    /**
     * Seen this many times, a word starts being offered.
     *
     * Deliberately lower than [OWN]. Showing him a word he has written twice costs
     * nothing if the guess is wrong — he ignores it. Refusing to correct a word
     * costs him a misspelling every time after, so that one stays cautious.
     */
    private const val SHOW = 2

    // Read from the worker thread while the keyboard writes from the main one, so
    // every store here has to be safe to walk while it is being changed.
    // Sorted, so "every word of his starting with ش" is a range on an ordered map
    // rather than a walk over all of them. At four thousand words a walk per
    // keystroke was affordable; at thirty thousand it is not, and the structure
    // has to carry that rather than the cap.
    private val counts = ConcurrentSkipListMap<String, Int>()

    /** When each word was last written, on the counter below. */
    private val recent = ConcurrentHashMap<String, Int>(512)

    /** Goes up by one on every word learnt; the clock the ranking runs on. */
    private var tick = 0

    /** "prev\u0000next" -> how often this person put those two words together. */
    private val pairs = ConcurrentHashMap<String, Int>(512)

    /**
     * "a\u0000b\u0000c" -> how often he wrote those three in a row.
     *
     * Two words of history say far more than one. "شاء" is followed by plenty of
     * things; "ان شاء" is followed by one. The pairs carry the weight when there
     * is no third word to go on, and this sharpens it when there is.
     */
    private val tri = ConcurrentHashMap<String, Int>(256)

    /**
     * The same pairs and triples, indexed the way they are asked for.
     *
     * "what follows هلا" used to be answered by walking every pair he had ever
     * written and testing the prefix of each key — six thousand string tests for
     * one keystroke. Here each history is a key of its own, holding only the words
     * that followed it, so the answer is one lookup however much he has written.
     */
    private val after = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>(512)

    private fun link(head: String, next: String, n: Int) {
        after.getOrPut(head) { ConcurrentHashMap(4) }[next] = n
    }

    private fun unlink(head: String, next: String) {
        after[head]?.let { it.remove(next); if (it.isEmpty()) after.remove(head) }
    }

    /** Splits "a\u0000b" or "a\u0000b\u0000c" into its history and its last word. */
    private fun headOf(k: String): Pair<String, String>? {
        val at = k.lastIndexOf('\u0000')
        if (at <= 0 || at == k.length - 1) return null
        return k.substring(0, at) to k.substring(at + 1)
    }

    private const val HOT_PAIRS = 60000
    private const val HOT_TRI = 40000
    /**
     * Repairs he has taught the keyboard.
     *
     * Kept far looser than the rest, because a repair is not a statistic to be
     * aged out — it is an instruction. Only the coldest go, and only once there
     * are more than this many.
     */
    private const val MAX_FIX = 20000
    private val keep: MutableSet<String> = ConcurrentHashMap.newKeySet(128)

    @Volatile private var loaded = false
    @Volatile private var dirty = false
    private var ctx: Context? = null

    /**
     * Opens the store and reads it back, with the slow part off the main thread.
     *
     * The keyboard calls this from onCreate, which is the main thread, and a
     * hundred thousand rows read there is a keyboard that takes a visible moment
     * to appear. So the small things that must be right immediately — the repairs
     * he has taught it, the spellings he insisted on — are read at once, and his
     * vocabulary follows a moment later. Everything that reads these stores
     * already copes with them being empty, because for the first second of the
     * first ever launch they were always going to be.
     */
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        ctx = c.applicationContext
        Learn.open(c)
        val fresh = Learn.empty()
        if (!fresh) {
            // small, and the correction path is wrong without it
            Learn.loadFixes { k, g, u -> fixes[k] = g; fixUsed[k] = u }
            Learn.loadKeep { k -> keep.add(k) }
            Learn.loadRejects { k, n -> rejects[k] = n }
            tick = Learn.tick()
        }
        try {
            writer.execute {
                try {
                    if (fresh) {
                        // the old file is read exactly once, to carry over what he
                        // had already taught the keyboard, then never again
                        readOldFile(ctx ?: return@execute)
                        markAllDirty()
                        flushNow()
                    } else {
                        Learn.loadWords(HOT_WORDS) { k, n, t ->
                            counts[k] = n
                            if (t > 0) recent[k] = t
                        }
                        Learn.loadPairs(HOT_PAIRS) { k, n -> pairs[k] = n }
                        Learn.loadTri(HOT_TRI) { k, n -> tri[k] = n }
                    }
                    rebuildIndex()
                    ready = true
                } catch (_: Throwable) {
                    ready = true
                }
            }
        } catch (_: Throwable) {
            ready = true
        }
    }

    /** True once his vocabulary is in memory. Nothing waits on it; it is for tests. */
    @Volatile var ready = false
        private set

    private fun markAllDirty() {
        for (k in counts.keys) Learn.touchWord(k)
        for (k in pairs.keys) Learn.touchPair(k)
        for (k in tri.keys) Learn.touchTri(k)
        for (k in fixes.keys) Learn.touchFix(k)
        for (k in keep) Learn.touchKeep(k)
        for (k in rejects.keys) Learn.touchRej(k)
    }

    private fun readOldFile(c: Context) {
        try {
            val p = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            readInto(p.getString(K_COUNTS, "{}"), counts)
            readInto(p.getString(K_PAIRS, "{}"), pairs)
            readInto(p.getString(K_RECENT, "{}"), recent)
            readInto(p.getString(K_TRI, "{}"), tri)
            readInto(p.getString(K_FIXUSE, "{}"), fixUsed)
            readInto(p.getString(K_REJECT, "{}"), rejects)
            tick = p.getInt(K_TICK, 0)
            (p.getString(K_KEEP, "") ?: "").split('\n').forEach {
                if (it.isNotBlank()) keep.add(it)
            }
            JSONObject(p.getString(K_FIXES, "{}") ?: "{}").let { o ->
                val it3 = o.keys()
                while (it3.hasNext()) {
                    val k = it3.next()
                    val v = o.optString(k, "")
                    if (v.isNotEmpty()) fixes[k] = v
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun readInto(json: String?, into: MutableMap<String, Int>) {
        try {
            val o = JSONObject(json ?: "{}")
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                into[k] = o.optInt(k, 1)
            }
        } catch (_: Exception) {
        }
    }

    /** Counts one finished word. Cheap enough to call on every space. */
    fun seen(word: String, arabic: Boolean) {
        if (word.length < 2 || word.length > 24) return
        if (!isWordy(word, arabic)) return
        val k = Dict.fold(word, arabic)
        // A spelling we have a repair for is a mistake, however often he makes it.
        // Counting it was what let a known slip quietly become "his own word" and
        // put itself beyond correction.
        if (fixes.containsKey(k)) return
        counts[k] = (counts[k] ?: 0) + 1
        tick++
        recent[k] = tick
        Learn.touchWord(k)
        dirty = true
        maybeFlush()
        if (counts.size > HOT_WORDS) trim()
    }

    /**
     * How much a word is worth right now.
     *
     * A word written fifty times last year should not outrank one he is writing
     * every day this week, so how often and how lately are weighed together. The
     * count still carries it — this only decides between words of similar standing.
     */
    private fun score(k: String): Float {
        val c = counts[k] ?: return 0f
        val age = tick - (recent[k] ?: 0)
        val boost = when {
            age < 60 -> 2.0f
            age < 250 -> 1.5f
            age < 1200 -> 1.15f
            else -> 1.0f
        }
        return c * boost
    }

    /** Records that [next] followed [prev] in this person's own writing. */
    fun seenPair(prev: String, next: String, arabic: Boolean) {
        if (prev.length < 2 || next.length < 2) return
        if (!isWordy(prev, arabic) || !isWordy(next, arabic)) return
        val a = Dict.fold(prev, arabic)
        val b = Dict.fold(next, arabic)
        val k = a + "\u0000" + b
        val n = (pairs[k] ?: 0) + 1
        pairs[k] = n
        link(a, b, n)
        Learn.touchPair(k)
        dirty = true
        if (pairs.size > HOT_PAIRS) trimPairs()
    }

    /** Records that [c] followed [a] then [b]. */
    fun seenTri(a: String, b: String, c: String, arabic: Boolean) {
        if (a.length < 2 || b.length < 2 || c.length < 2) return
        if (!isWordy(a, arabic) || !isWordy(b, arabic) || !isWordy(c, arabic)) return
        val h = Dict.fold(a, arabic) + "\u0000" + Dict.fold(b, arabic)
        val last = Dict.fold(c, arabic)
        val k = h + "\u0000" + last
        val n = (tri[k] ?: 0) + 1
        tri[k] = n
        link(h, last, n)
        Learn.touchTri(k)
        dirty = true
        if (tri.size > HOT_TRI) trimTri()
    }

    fun triCount(a: String, b: String, c: String, arabic: Boolean): Int {
        if (a.isEmpty() || b.isEmpty() || c.isEmpty() || tri.isEmpty()) return 0
        val k = Dict.fold(a, arabic) + "\u0000" + Dict.fold(b, arabic) +
            "\u0000" + Dict.fold(c, arabic)
        return tri[k] ?: 0
    }

    /** The words he writes after these two, strongest first. */
    fun nextTri(a: String, b: String, arabic: Boolean, n: Int): List<String> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        return best(Dict.fold(a, arabic) + "\u0000" + Dict.fold(b, arabic), n, "")
    }

    /**
     * The words he writes after [head], strongest first, kept to [n].
     *
     * [prefix] is the part of the next word he has already typed. Filtering here
     * rather than in the caller is what makes "هلا ش" able to answer شلونك: the
     * history and the letters so far are one question, and asked separately
     * neither of them knows the answer.
     */
    private fun best(head: String, n: Int, prefix: String): List<String> {
        if (n <= 0) return emptyList()
        val m = after[head] ?: return emptyList()
        val topW: Array<String?> = arrayOfNulls(n)
        val topC = IntArray(n)
        for ((w, c) in m) {
            if (prefix.isNotEmpty() && !w.startsWith(prefix)) continue
            if (c <= topC[n - 1]) continue
            var at = n - 1
            while (at > 0 && topC[at - 1] < c) {
                topC[at] = topC[at - 1]; topW[at] = topW[at - 1]; at--
            }
            topC[at] = c; topW[at] = w
        }
        val out = ArrayList<String>(n)
        for (i in 0 until n) topW[i]?.let { out.add(it) }
        return out
    }

    /**
     * What he writes next, given up to two words of history and whatever letters
     * of the next word he has typed. Two words of history first, one behind it.
     */
    fun nextWith(prev2: String, prev: String, prefix: String,
                 arabic: Boolean, n: Int): List<String> {
        if (prev.isEmpty() || n <= 0) return emptyList()
        val p = if (prefix.isEmpty()) "" else Dict.fold(prefix, arabic)
        val out = ArrayList<String>(n)
        if (prev2.isNotEmpty()) {
            for (w in best(Dict.fold(prev2, arabic) + "\u0000" + Dict.fold(prev, arabic),
                           n, p)) {
                if (!out.contains(w)) out.add(w)
            }
        }
        if (out.size < n) {
            for (w in best(Dict.fold(prev, arabic), n, p)) {
                if (out.size >= n) break
                if (!out.contains(w)) out.add(w)
            }
        }
        return out
    }

    /** Drops the weakest from memory only. The rows stay in [Learn]. */
    private fun trimTri() {
        val keepers = tri.entries.sortedByDescending { it.value }.take(HOT_TRI * 3 / 4)
        tri.clear()
        for (e in keepers) tri[e.key] = e.value
        rebuildIndex()
    }

    /** Rebuilds [after] from the two flat maps. Runs only after a trim or a load. */
    private fun rebuildIndex() {
        after.clear()
        for ((k, c) in pairs) headOf(k)?.let { link(it.first, it.second, c) }
        for ((k, c) in tri) headOf(k)?.let { link(it.first, it.second, c) }
    }

    /** How often he has put these two words together, in this order. */
    fun pairCount(prev: String, next: String, arabic: Boolean): Int {
        if (prev.isEmpty() || next.isEmpty() || pairs.isEmpty()) return 0
        val k = Dict.fold(prev, arabic) + "\u0000" + Dict.fold(next, arabic)
        return pairs[k] ?: 0
    }

    /**
     * How much a candidate correction deserves, given the word before it.
     *
     * Returned as a multiplier on the corrector's own score, where below one means
     * better. A word he regularly writes in exactly this place beats one that is
     * closer letter by letter but that he has never written after this word — which
     * is the whole difference between a corrector that guesses and one that knows
     * him. "صباح الخيز" becomes "صباح الخير", not "صباح الخيط".
     */
    fun contextWeight(prev2: String, prev: String, cand: String, arabic: Boolean): Float {
        var w = 1f

        // two words of history beat one, so it is asked first and counts for more
        val tc = if (prev2.isEmpty()) 0 else triCount(prev2, prev, cand, arabic)
        if (tc >= 3) w *= 0.22f
        else if (tc >= 1) w *= 0.38f
        else {
            val pc = pairCount(prev, cand, arabic)
            if (pc >= 6) w *= 0.30f
            else if (pc >= 3) w *= 0.45f
            else if (pc >= 1) w *= 0.65f
        }

        val k = Dict.fold(cand, arabic)
        val c = counts[k] ?: 0
        if (c >= 12) w *= 0.55f
        else if (c >= SHOW) w *= 0.75f
        return w
    }

    /** The words this person usually writes after [prev], most used first. */
    fun next(prev: String, arabic: Boolean, n: Int): List<String> {
        if (prev.isEmpty()) return emptyList()
        return best(Dict.fold(prev, arabic), n, "")
    }

    private fun bestNext(prev: String, arabic: Boolean, least: Int): String? {
        if (prev.isEmpty() || pairs.isEmpty()) return null
        val head = Dict.fold(prev, arabic) + "\u0000"
        var best: String? = null
        var bestC = least - 1
        for ((k, c) in pairs) {
            if (c <= bestC) continue
            if (k.startsWith(head)) { best = k.substring(head.length); bestC = c }
        }
        return best
    }

    /**
     * A whole line he writes often, offered in one tap.
     *
     * Built by walking the pairs rather than stored separately: if he reliably
     * writes B after A and C after B, then "A B C" is a line of his, and there is
     * nothing to keep in a file for it.
     */
    fun phrase(start: String, arabic: Boolean): String? {
        if (start.length < 2) return null
        val a = bestNext(start, arabic, 3) ?: return null
        val b = bestNext(a, arabic, 3) ?: return null
        if (b == start || b == a) return null
        return "$start $a $b"
    }

    /** Drops the weakest from memory only. The rows stay in [Learn]. */
    private fun trimPairs() {
        val keepers = pairs.entries.sortedByDescending { it.value }.take(HOT_PAIRS * 3 / 4)
        pairs.clear()
        for (e in keepers) pairs[e.key] = e.value
        rebuildIndex()
    }

    /**
     * Mistakes this person makes, and what they actually meant.
     *
     * The general corrector guesses from a dictionary; this remembers. Once a repair
     * is in here it is applied straight away, every time, with no guessing at all —
     * which is what makes a keyboard feel like it has learnt someone's hands.
     */
    private val fixes = ConcurrentHashMap<String, String>()

    /** When each repair was last wanted, so a busy one is never the one dropped. */
    private val fixUsed = ConcurrentHashMap<String, Int>()

    /** How many times he has put a word back after it was corrected. */
    private val rejects = ConcurrentHashMap<String, Int>()

    /** Rejections before a spelling is taken as deliberate and left alone for good. */
    private const val REJECTS_TO_KEEP = 2

    /** Remembers that [bad] should have been [good]. */
    fun learnFix(bad: String, good: String, arabic: Boolean) {
        if (bad.length < 2 || good.length < 2) return
        if (bad.length > 24 || good.length > 24) return
        if (!isWordy(bad, arabic) || !isWordy(good, arabic)) return
        val k = Dict.fold(bad, arabic)
        if (k == Dict.fold(good, arabic)) return
        if (keep.contains(k)) return          // he insisted on this spelling before
        tick++
        fixUsed[k] = tick
        // the slip itself is no longer vocabulary; it is a mistake with an answer
        counts.remove(k)
        if (fixes[k] == good) { dirty = true; return }
        fixes[k] = good
        if (fixes.size > MAX_FIX) {
            // the ones he has not needed in longest go, never whichever the
            // iterator happened to reach first
            val cold = fixes.keys.sortedBy { fixUsed[it] ?: 0 }
                .take(fixes.size - MAX_FIX * 3 / 4)
            for (c in cold) { fixes.remove(c); fixUsed.remove(c); Learn.touchFix(c) }
        }
        dirty = true
        save()
    }

    /** What he meant, when this exact slip has been seen before. */
    fun fixFor(word: String, arabic: Boolean): String? {
        if (fixes.isEmpty()) return null
        val k = Dict.fold(word, arabic)
        if (keep.contains(k)) return null
        val out = fixes[k] ?: return null
        if (out == word) return null
        fixUsed[k] = tick
        return out
    }

    /** He put the typed word back, so it was never a mistake. */
    fun forgetFix(word: String, arabic: Boolean) {
        val k = Dict.fold(word, arabic)
        if (fixes.remove(k) != null) { Learn.touchFix(k); dirty = true; save() }
    }

    /**
     * He put a corrected word back.
     *
     * Once is not a verdict. A backspace lands for all sorts of reasons, and
     * treating the first one as "never correct this again" is how a repair that
     * worked on Monday stops working for good on Tuesday. So the first time only
     * drops the stored repair; the second time the spelling is taken as
     * deliberate and left alone from then on.
     *
     * Returns true when the word has now been accepted as his own.
     */
    fun keepAsIs(word: String, arabic: Boolean): Boolean {
        val k = Dict.fold(word, arabic)
        forgetFix(word, arabic)
        val n = (rejects[k] ?: 0) + 1
        rejects[k] = n
        Learn.touchRej(k)
        dirty = true
        if (n < REJECTS_TO_KEEP) { save(); return false }
        keep.add(k)
        counts[k] = (counts[k] ?: 0) + OWN
        tick++
        recent[k] = tick
        Learn.touchKeep(k)
        Learn.touchWord(k)
        save()
        return true
    }

    /** True when this spelling must be left alone by auto-correction. */
    fun isOwn(word: String, arabic: Boolean): Boolean {
        val k = Dict.fold(word, arabic)
        if (keep.contains(k)) return true
        val c = counts[k] ?: 0
        if (c < OWN) return false
        // A word that is in no dictionary and sits one slip away from a common one
        // is a mistake he repeats, not a word he owns. Counting alone let a typo
        // typed a few times put itself permanently beyond correction, which is
        // what happened to اكلظ.
        if (c < STUBBORN && !Dict.known(word, arabic) && Dict.hasStrongFix(word, arabic)) {
            return false
        }
        return true
    }

    /** Repeated this often, a spelling is his however much it looks like a slip. */
    private const val STUBBORN = 25

    /**
     * The person's own words starting with [prefix], the ones they write most first.
     * These go in front of the general dictionary's guesses.
     */
    fun predict(prefix: String, arabic: Boolean, n: Int): List<String> {
        if (prefix.isEmpty() || counts.isEmpty()) return emptyList()
        val p = Dict.fold(prefix, arabic)
        if (p.isEmpty()) return emptyList()
        // the words between "شل" and "شل\uffff" are exactly the words starting with
        // "شل", so the map hands them over without a single wasted comparison
        val range = try {
            counts.subMap(p, true, p + '\uffff', true)
        } catch (_: Throwable) {
            return emptyList()
        }
        val hits = ArrayList<Pair<String, Float>>(16)
        for ((w, c) in range) {
            if (c < SHOW || w.length <= p.length) continue
            if (fixes.containsKey(w)) continue
            hits.add(w to score(w))
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
        var bestScore = 0f
        for ((cand, c) in counts) {
            if (c < OWN) continue
            val s = score(cand)
            if (s <= bestScore) continue
            if (kotlin.math.abs(cand.length - w.length) > 1) continue
            if (!oneEdit(w, cand)) continue
            best = cand
            bestScore = s
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
        val keepers = counts.entries.sortedByDescending { score(it.key) }.take(HOT_TRIM)
        counts.clear()
        val keptRecent = HashMap<String, Int>(HOT_TRIM)
        for (e in keepers) {
            counts[e.key] = e.value
            recent[e.key]?.let { keptRecent[e.key] = it }
        }
        recent.clear()
        recent.putAll(keptRecent)
    }

    /**
     * Writes what has changed, on a thread of its own.
     *
     * The old version rewrote every store, whole, as JSON — so the cost of
     * learning one word grew with everything already learnt, which is the real
     * reason the caps existed. Here a space costs one row. Batched, because
     * fifty rows in one transaction cost barely more than one.
     */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "huc-learn").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /** True while a write is queued but has not started, so one is queued at a time
     * without a change made meanwhile being left waiting for the next one. */
    private val queued = java.util.concurrent.atomic.AtomicBoolean(false)

    /** How many rows may wait before they are written without being asked. */
    private const val BATCH = 40

    /** Words typed between one ageing pass and the next. */
    private const val DECAY_EVERY = 4000

    private var decayAt = 0

    fun save() {
        if (!dirty) return
        dirty = false
        flushLater()
    }

    private fun flushLater() {
        if (!queued.compareAndSet(false, true)) return
        try {
            writer.execute {
                queued.set(false)      // cleared first: a change made during the
                flushNow()             // write queues another, and is not lost
            }
        } catch (_: Throwable) {
            queued.set(false)
        }
    }

    /** Called from [seen] on every word, so it must do nothing most of the time. */
    private fun maybeFlush() {
        if (Learn.waiting >= BATCH) flushLater()
        if (tick - decayAt >= DECAY_EVERY) {
            decayAt = tick
            try {
                writer.execute { Learn.decay() }
            } catch (_: Throwable) {
            }
        }
    }

    private fun flushNow() {
        Learn.flush(
            wordAt = { k -> counts[k]?.let { it to (recent[k] ?: 0) } },
            pairAt = { k -> pairs[k] },
            triAt = { k -> tri[k] },
            fixAt = { k -> fixes[k]?.let { it to (fixUsed[k] ?: 0) } },
            keepHas = { k -> keep.contains(k) },
            rejAt = { k -> rejects[k] },
            tick = tick
        )
    }

    private fun mapJson(m: Map<String, Int>): String {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        return o.toString()
    }

    // ---------- carrying it to another phone ----------

    /**
     * Everything the keyboard has learnt, as one piece of text.
     *
     * Written so he is never made to start again: a new phone, a reinstall, a
     * phone that breaks. It holds his words and nothing he typed — no sentences,
     * no messages, no order he wrote them in.
     */
    fun exportText(): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("tick", tick)
        o.put(K_COUNTS, JSONObject(mapJson(counts)))
        o.put(K_PAIRS, JSONObject(mapJson(pairs)))
        o.put(K_TRI, JSONObject(mapJson(tri)))
        o.put(K_RECENT, JSONObject(mapJson(recent)))
        o.put(K_KEEP, keep.joinToString("\n"))
        o.put(K_FIXES, JSONObject().also { fj -> for ((k, v) in fixes) fj.put(k, v) })
        return o.toString()
    }

    /**
     * Puts a saved file back, added to whatever is already here rather than over
     * it — restoring on a phone he has been using should not cost him the words
     * it has picked up since.
     */
    fun importText(text: String): Boolean {
        return try {
            val o = JSONObject(text)
            mergeCounts(o.optJSONObject(K_COUNTS), counts)
            mergeCounts(o.optJSONObject(K_PAIRS), pairs)
            mergeCounts(o.optJSONObject(K_TRI), tri)
            val r = o.optJSONObject(K_RECENT)
            if (r != null) {
                val it = r.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = r.optInt(k, 0)
                    if (v > (recent[k] ?: 0)) recent[k] = v
                }
            }
            tick = maxOf(tick, o.optInt("tick", 0))
            (o.optString(K_KEEP, "")).split('\n').forEach {
                if (it.isNotBlank()) keep.add(it)
            }
            val f = o.optJSONObject(K_FIXES)
            if (f != null) {
                val it2 = f.keys()
                while (it2.hasNext()) {
                    val k = it2.next()
                    val v = f.optString(k, "")
                    if (v.isNotEmpty()) fixes[k] = v
                }
            }
            if (counts.size > HOT_WORDS) trim()
            if (pairs.size > HOT_PAIRS) trimPairs()
            if (tri.size > HOT_TRI) trimTri()
            rebuildIndex()
            markAllDirty()
            dirty = true
            save()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun mergeCounts(from: JSONObject?, into: MutableMap<String, Int>) {
        if (from == null) return
        val it = from.keys()
        while (it.hasNext()) {
            val k = it.next()
            into[k] = (into[k] ?: 0) + from.optInt(k, 0)
        }
    }

    /** How many words the keyboard has picked up — shown in the settings screen. */
    fun learned(): Int {
        val onDisk = Learn.counts()[0]
        val inRam = counts.count { it.value >= SHOW }
        return if (onDisk > inRam) onDisk else inRam
    }

    /** Words, pairs, triples and repairs on disk — what settings shows him. */
    fun sizes(): IntArray = Learn.counts()

    /** How many of his own slips the keyboard now repairs on sight. */
    fun fixCount(): Int = maxOf(fixes.size, Learn.counts()[3])

    /**
     * Drops the slips that earlier versions counted as words.
     *
     * Until the stores were kept apart, a mistake typed a few times was filed as
     * his own spelling and became uncorrectable. Those entries are still on the
     * phone after the upgrade, so they are cleared out once: anything in no
     * dictionary that sits one slip from a common word goes, and everything he
     * actually writes stays.
     */
    fun purgeSlips(arabic: Boolean): Int {
        var gone = 0
        for (w in counts.keys.toList()) {
            if (Dict.known(w, arabic)) continue
            if (!Dict.hasStrongFix(w, arabic)) continue
            counts.remove(w); recent.remove(w); keep.remove(w); gone++
            Learn.touchWord(w); Learn.touchKeep(w)
        }
        if (gone > 0) { dirty = true; save() }
        return gone
    }

    fun forgetAll() {
        Learn.wipe()
        after.clear()
        fixUsed.clear()
        rejects.clear()
        counts.clear()
        pairs.clear()
        tri.clear()
        recent.clear()
        keep.clear()
        fixes.clear()
        tick = 0
        dirty = true
        save()
    }
}
