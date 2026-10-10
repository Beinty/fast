package com.huc.fasttype

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * Where everything the keyboard learns actually lives.
 *
 * It used to live in SharedPreferences, as six JSON strings. That shape is what
 * set every limit in the old learning system, and the limits were not chosen for
 * any good reason — they were the largest numbers that could be rewritten on
 * every space without the keyboard stuttering. Four thousand words. Six thousand
 * pairs. Four thousand triples. Past those he stopped learning, and the rarest
 * entries were thrown away for good.
 *
 * Here nothing is thrown away because a counter filled up. Rows are written in
 * batches on a background thread, one row at a time rather than the whole file,
 * so the cost of learning a word does not grow with how much he has already
 * learnt. What he can learn is bounded by the disk, which is to say not bounded.
 *
 * [UserDict] still answers every question from memory — a database query per
 * keystroke is exactly what makes a keyboard feel slow. This is the durable copy
 * underneath it: written as he types, read back whole at startup, and pruned
 * only of entries that have decayed to nothing.
 */
object Learn {

    private const val DB = "huc_learn.db"
    private const val VERSION = 2

    /** Table names kept to two letters: they appear in every statement below. */
    private const val T_WORD = "w"      // k, n (count), t (tick last seen)
    private const val T_PAIR = "p"      // k = "a\u0000b", n
    private const val T_TRI = "t3"      // k = "a\u0000b\u0000c", n
    private const val T_FIX = "fx"      // k = the slip, g = what he meant, u
    private const val T_KEEP = "kp"     // k = a spelling he insisted on
    private const val T_REJ = "rj"      // k, n = how often he put it back
    private const val T_META = "m"      // k, v
    private const val T_TOK = "tk"      // k = verbatim, n = times typed

    private class Helper(c: Context) : SQLiteOpenHelper(c, DB, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE $T_WORD (k TEXT PRIMARY KEY, n INTEGER, t INTEGER)")
            db.execSQL("CREATE TABLE $T_PAIR (k TEXT PRIMARY KEY, n INTEGER)")
            db.execSQL("CREATE TABLE $T_TRI  (k TEXT PRIMARY KEY, n INTEGER)")
            db.execSQL("CREATE TABLE $T_FIX  (k TEXT PRIMARY KEY, g TEXT, u INTEGER)")
            db.execSQL("CREATE TABLE $T_KEEP (k TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE $T_REJ  (k TEXT PRIMARY KEY, n INTEGER)")
            db.execSQL("CREATE TABLE $T_META (k TEXT PRIMARY KEY, v INTEGER)")
            db.execSQL("CREATE TABLE $T_TOK  (k TEXT PRIMARY KEY, n INTEGER)")
            // the hot set is read back by score, so that read must not be a sort
            db.execSQL("CREATE INDEX w_n ON $T_WORD (n DESC)")
            db.execSQL("CREATE INDEX p_n ON $T_PAIR (n DESC)")
            db.execSQL("CREATE INDEX t3_n ON $T_TRI (n DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) {
            // Only ever add. This file is the only copy of what he has taught the
            // keyboard, and dropping it to rebuild a schema would throw that away.
            if (from < 2) {
                try {
                    db.execSQL("CREATE TABLE IF NOT EXISTS $T_TOK (k TEXT PRIMARY KEY, n INTEGER)")
                } catch (_: Throwable) {}
            }
        }
    }

    @Volatile private var helper: Helper? = null

    private fun db(): SQLiteDatabase? = try {
        helper?.writableDatabase
    } catch (_: Throwable) {
        null
    }

    fun open(c: Context) {
        if (helper != null) return
        helper = Helper(c.applicationContext)
    }

    // ---- what is waiting to be written ------------------------------------
    //
    // A set of keys, not a copy of the values: by the time the flush runs the
    // count in memory is the right one, and he may have typed the word three
    // more times while it waited.

    private val dirtyWord = ConcurrentHashMap.newKeySet<String>(256)
    private val dirtyPair = ConcurrentHashMap.newKeySet<String>(256)
    private val dirtyTri = ConcurrentHashMap.newKeySet<String>(256)
    private val dirtyFix = ConcurrentHashMap.newKeySet<String>(32)
    private val dirtyKeep = ConcurrentHashMap.newKeySet<String>(32)
    private val dirtyRej = ConcurrentHashMap.newKeySet<String>(32)
    private val dirtyTok = ConcurrentHashMap.newKeySet<String>(64)

    fun touchWord(k: String) { dirtyWord.add(k) }
    fun touchPair(k: String) { dirtyPair.add(k) }
    fun touchTri(k: String) { dirtyTri.add(k) }
    fun touchFix(k: String) { dirtyFix.add(k) }
    fun touchKeep(k: String) { dirtyKeep.add(k) }
    fun touchRej(k: String) { dirtyRej.add(k) }
    fun touchTok(k: String) { dirtyTok.add(k) }

    val waiting: Int
        get() = dirtyWord.size + dirtyPair.size + dirtyTri.size +
            dirtyFix.size + dirtyKeep.size + dirtyRej.size + dirtyTok.size

    /**
     * Writes everything waiting, in one transaction.
     *
     * Call it from a background thread. [values] is asked for each key's current
     * state, so a key whose entry has since gone is deleted rather than written.
     */
    fun flush(
        wordAt: (String) -> Pair<Int, Int>?,   // count, tick
        pairAt: (String) -> Int?,
        triAt: (String) -> Int?,
        fixAt: (String) -> Pair<String, Int>?, // what he meant, when last used
        keepHas: (String) -> Boolean,
        rejAt: (String) -> Int?,
        tokAt: (String) -> Int?,
        tick: Int
    ) {
        val d = db() ?: return
        val w = drain(dirtyWord)
        val p = drain(dirtyPair)
        val t = drain(dirtyTri)
        val f = drain(dirtyFix)
        val kp = drain(dirtyKeep)
        val rj = drain(dirtyRej)
        val tk = drain(dirtyTok)
        if (w.isEmpty() && p.isEmpty() && t.isEmpty() && f.isEmpty() &&
            kp.isEmpty() && rj.isEmpty() && tk.isEmpty()
        ) return
        try {
            d.beginTransaction()
            val cv = ContentValues(3)
            for (k in w) {
                val v = wordAt(k)
                if (v == null) { d.delete(T_WORD, "k=?", arrayOf(k)); continue }
                cv.clear(); cv.put("k", k); cv.put("n", v.first); cv.put("t", v.second)
                d.insertWithOnConflict(T_WORD, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (k in p) {
                val v = pairAt(k)
                if (v == null) { d.delete(T_PAIR, "k=?", arrayOf(k)); continue }
                cv.clear(); cv.put("k", k); cv.put("n", v)
                d.insertWithOnConflict(T_PAIR, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (k in t) {
                val v = triAt(k)
                if (v == null) { d.delete(T_TRI, "k=?", arrayOf(k)); continue }
                cv.clear(); cv.put("k", k); cv.put("n", v)
                d.insertWithOnConflict(T_TRI, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (k in f) {
                val v = fixAt(k)
                if (v == null) { d.delete(T_FIX, "k=?", arrayOf(k)); continue }
                cv.clear(); cv.put("k", k); cv.put("g", v.first); cv.put("u", v.second)
                d.insertWithOnConflict(T_FIX, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (k in kp) {
                if (!keepHas(k)) { d.delete(T_KEEP, "k=?", arrayOf(k)); continue }
                cv.clear(); cv.put("k", k)
                d.insertWithOnConflict(T_KEEP, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            for (k in rj) {
                val v = rejAt(k)
                if (v == null) { d.delete(T_REJ, "k=?", arrayOf(k)); continue }
                cv.clear(); cv.put("k", k); cv.put("n", v)
                d.insertWithOnConflict(T_REJ, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (k in tk) {
                val v = tokAt(k)
                if (v == null) { d.delete(T_TOK, "k=?", arrayOf(k)); continue }
                cv.clear(); cv.put("k", k); cv.put("n", v)
                d.insertWithOnConflict(T_TOK, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            cv.clear(); cv.put("k", "tick"); cv.put("v", tick)
            d.insertWithOnConflict(T_META, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            d.setTransactionSuccessful()
        } catch (_: Throwable) {
        } finally {
            try { d.endTransaction() } catch (_: Throwable) {}
        }
        if (snap != null) measure()   // whoever writes keeps the figure honest
    }

    private fun drain(s: MutableSet<String>): List<String> {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<String>(s.size)
        val it = s.iterator()
        while (it.hasNext()) { out.add(it.next()); it.remove() }
        return out
    }

    // ---- reading back ------------------------------------------------------

    /** The [limit] strongest word rows, straight into [into]. */
    fun loadWords(limit: Int, into: (String, Int, Int) -> Unit): Int =
        rows("SELECT k,n,t FROM $T_WORD ORDER BY n DESC LIMIT $limit") { c ->
            into(c.getString(0), c.getInt(1), c.getInt(2))
        }

    fun loadPairs(limit: Int, into: (String, Int) -> Unit): Int =
        rows("SELECT k,n FROM $T_PAIR ORDER BY n DESC LIMIT $limit") { c ->
            into(c.getString(0), c.getInt(1))
        }

    fun loadTri(limit: Int, into: (String, Int) -> Unit): Int =
        rows("SELECT k,n FROM $T_TRI ORDER BY n DESC LIMIT $limit") { c ->
            into(c.getString(0), c.getInt(1))
        }

    /** Repairs are never trimmed and never sampled: all of them come back. */
    fun loadFixes(into: (String, String, Int) -> Unit): Int =
        rows("SELECT k,g,u FROM $T_FIX") { c ->
            into(c.getString(0), c.getString(1), c.getInt(2))
        }

    fun loadKeep(into: (String) -> Unit): Int =
        rows("SELECT k FROM $T_KEEP") { c -> into(c.getString(0)) }

    fun loadTokens(into: (String, Int) -> Unit): Int =
        rows("SELECT k, n FROM $T_TOK") { c -> into(c.getString(0), c.getInt(1)) }

    fun loadRejects(into: (String, Int) -> Unit): Int =
        rows("SELECT k,n FROM $T_REJ") { c -> into(c.getString(0), c.getInt(1)) }

    fun tick(): Int {
        var out = 0
        rows("SELECT v FROM $T_META WHERE k='tick'") { c -> out = c.getInt(0) }
        return out
    }

    private inline fun rows(sql: String, body: (android.database.Cursor) -> Unit): Int {
        val d = db() ?: return 0
        var n = 0
        try {
            d.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) { body(c); n++ }
            }
        } catch (_: Throwable) {
        }
        return n
    }

    /**
     * How much he has learnt, in rows: words, pairs, triples, repairs.
     *
     * COUNT(*) on a growing table is a scan, and this is read while a settings
     * screen is being built, so the answer is worked out once and then kept up to
     * date by whoever next writes. Being a few words out of date on a screen is
     * nothing; a settings page that takes a moment to appear is not.
     */
    @Volatile private var snap: IntArray? = null

    fun counts(): IntArray = snap ?: measure()

    fun measure(): IntArray {
        val out = IntArray(4)
        val names = arrayOf(T_WORD, T_PAIR, T_TRI, T_FIX)
        for (i in names.indices) {
            rows("SELECT COUNT(*) FROM ${names[i]}") { c -> out[i] = c.getInt(0) }
        }
        snap = out
        return out
    }

    /**
     * Ages every count, then removes what has aged to nothing.
     *
     * This is how the file stays fast while nothing is ever capped. A word he
     * wrote twice last spring and never again loses a tenth of its weight each
     * pass and eventually leaves; a word he writes every day gains far faster
     * than the pass takes away, so it never does. Nothing is dropped for being
     * the four-thousand-and-first word he learnt.
     */
    fun decay() {
        val d = db() ?: return
        try {
            d.beginTransaction()
            d.execSQL("UPDATE $T_WORD SET n = n - (n + 9) / 10 WHERE n > 0")
            d.execSQL("UPDATE $T_PAIR SET n = n - (n + 9) / 10 WHERE n > 0")
            d.execSQL("UPDATE $T_TRI  SET n = n - (n + 9) / 10 WHERE n > 0")
            d.execSQL("DELETE FROM $T_WORD WHERE n <= 0")
            d.execSQL("DELETE FROM $T_PAIR WHERE n <= 0")
            d.execSQL("DELETE FROM $T_TRI  WHERE n <= 0")
            d.setTransactionSuccessful()
        } catch (_: Throwable) {
        } finally {
            try { d.endTransaction() } catch (_: Throwable) {}
        }
    }

    fun wipe() {
        val d = db() ?: return
        dirtyWord.clear(); dirtyPair.clear(); dirtyTri.clear()
        dirtyFix.clear(); dirtyKeep.clear(); dirtyRej.clear()
        snap = IntArray(4)
        try {
            d.beginTransaction()
            for (t in arrayOf(T_WORD, T_PAIR, T_TRI, T_FIX, T_KEEP, T_REJ, T_TOK, T_META)) {
                d.execSQL("DELETE FROM $t")
            }
            d.setTransactionSuccessful()
        } catch (_: Throwable) {
        } finally {
            try { d.endTransaction() } catch (_: Throwable) {}
        }
    }

    /** True when there is nothing here yet, so the old file is worth reading once. */
    fun empty(): Boolean {
        var n = -1
        rows("SELECT COUNT(*) FROM $T_WORD") { c -> n = c.getInt(0) }
        return n == 0
    }
}
