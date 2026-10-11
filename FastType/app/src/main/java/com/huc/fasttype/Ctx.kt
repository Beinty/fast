package com.huc.fasttype

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * What follows what, in Arabic, counted off real writing.
 *
 * The keyboard knew 215,000 words and five thousand pairs of them, and that
 * gap is the whole difference between a strip that offers the next word and
 * one that sits empty the moment a word is finished. These tables were counted
 * from twenty-three million words of Arabic that people actually wrote —
 * tweets, questions, reviews, comments, talks — and carry 120,000 first-words
 * and 315,000 pairs, which is twenty-four and fifty-six times what was there.
 *
 * They are fifteen megabytes, and that is why they are not a HashMap. Loading
 * them into one would cost the better part of a hundred megabytes of heap in
 * an input method, which the system kills keyboards for. Instead each file is
 * copied out of the assets once and then memory-mapped: the lookup is a binary
 * search over the mapped bytes, the pages it touches are the only ones that
 * ever reach RAM, and the kernel drops them again under pressure.
 *
 * Format, one line each, sorted by the key as bytes:
 *
 *     كلمة<TAB>الأولى الثانية الثالثة
 *     كلمة أخرى<TAB>التالية ...
 *
 * Keys are folded (no hamza shapes, no taa marbuta) because that is how the
 * keyboard spells a word it is looking up; the followers are written properly,
 * because they are what goes on the screen.
 */
object Ctx {

    private const val F2 = "ctx2_ar.txt"
    private const val F3 = "ctx3_ar.txt"
    private const val MARK = "ctx.ver"

    private const val NL = '\n'.code.toByte()
    private const val TAB = '\t'.code.toByte()
    private const val SP = ' '.code.toByte()

    @Volatile private var m2: MappedByteBuffer? = null
    @Volatile private var m3: MappedByteBuffer? = null
    @Volatile private var started = false

    val ready: Boolean get() = m2 != null

    /**
     * Copies the tables out of the assets the first time, then maps them.
     *
     * On a background thread at the lowest priority: the keyboard has to be on
     * screen and typing long before fifteen megabytes finish copying, and
     * everything here answers "nothing yet" until they do.
     */
    fun load(app: Context) {
        if (started) return
        started = true
        Thread {
            try {
                val dir = app.filesDir
                val mark = File(dir, MARK)
                val want = BuildConfig.VERSION_CODE.toString()
                val have = try {
                    if (mark.exists()) mark.readText().trim() else ""
                } catch (_: Throwable) { "" }
                if (have != want) {
                    copy(app, F2, File(dir, F2))
                    copy(app, F3, File(dir, F3))
                    try { mark.writeText(want) } catch (_: Throwable) {}
                }
                m2 = map(File(dir, F2))
                m3 = map(File(dir, F3))
            } catch (_: Throwable) {
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun copy(app: Context, name: String, to: File) {
        app.assets.open(name).use { input ->
            to.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
        }
    }

    private fun map(f: File): MappedByteBuffer? {
        if (!f.exists() || f.length() < 16) return null
        return RandomAccessFile(f, "r").use { raf ->
            raf.channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }
        }
    }

    /** Words that usually follow [prev]. [prev] is already folded. */
    fun after(prev: String): List<String> {
        val b = m2 ?: return emptyList()
        return look(b, prev)
    }

    /** Words that usually follow the pair [prev2] [prev], both folded. */
    fun after2(prev2: String, prev: String): List<String> {
        val b = m3 ?: return emptyList()
        return look(b, "$prev2 $prev")
    }

    // ---- the search -------------------------------------------------------

    private fun look(buf: MappedByteBuffer, key: String): List<String> {
        if (key.isEmpty()) return emptyList()
        val q = key.toByteArray(Charsets.UTF_8)
        val size = buf.limit()
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            var s = mid
            while (s > 0 && buf.get(s - 1) != NL) s--
            val c = cmp(buf, s, q)
            if (c < 0) {
                var e = s
                while (e < size && buf.get(e) != NL) e++
                lo = e + 1
            } else {
                hi = s
            }
        }
        if (lo >= size) return emptyList()
        if (cmp(buf, lo, q) != 0) return emptyList()
        return followers(buf, lo + q.size + 1)
    }

    /**
     * The line at [start] against the key: negative when the line sorts first.
     *
     * The key is compared against the line up to its tab, so a line whose key
     * merely starts with the query is still "greater" — exactly what a lookup
     * of "صباح" should do when "صباحا" is the neighbouring line.
     */
    private fun cmp(buf: MappedByteBuffer, start: Int, q: ByteArray): Int {
        val size = buf.limit()
        var i = start
        var j = 0
        while (true) {
            val a = if (i < size) buf.get(i) else NL
            if (a == NL || a == TAB) return if (j == q.size) 0 else -1
            if (j == q.size) return 1
            val x = a.toInt() and 0xFF
            val y = q[j].toInt() and 0xFF
            if (x != y) return if (x < y) -1 else 1
            i++; j++
        }
    }

    private fun followers(buf: MappedByteBuffer, from: Int): List<String> {
        val size = buf.limit()
        if (from >= size) return emptyList()
        var i = from
        val out = ArrayList<String>(5)
        val bytes = ByteArray(64)
        var n = 0
        while (i < size) {
            val b = buf.get(i)
            if (b == NL || b == SP) {
                if (n > 0) {
                    out.add(String(bytes, 0, n, Charsets.UTF_8))
                    n = 0
                }
                if (b == NL) break
            } else if (n < bytes.size) {
                bytes[n++] = b
            }
            i++
        }
        if (n > 0) out.add(String(bytes, 0, n, Charsets.UTF_8))
        return out
    }
}
