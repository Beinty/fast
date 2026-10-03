package com.huc.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Rendered icons, kept on disk.
 *
 * Rendering sixty icons means sixty drawable loads, layer composites and squircle
 * masks — a second or more of work. The system is free to close the home screen
 * whenever it wants memory, so that work would otherwise be repeated every single
 * time he leaves an app, which is exactly the wait he sees. Decoding a 180px PNG
 * is about a millisecond, so the cache turns that second into a blink.
 *
 * The file name carries the package's last-update time, so an app that updates
 * simply misses the cache and gets redrawn.
 */
object IconCache {

    private fun dir(ctx: Context): File {
        val d = File(ctx.cacheDir, "ic")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun name(key: String, size: Int, stamp: Long): String =
        Integer.toHexString(key.hashCode()) + "_" + size + "_" +
            java.lang.Long.toHexString(stamp) + ".png"

    fun load(ctx: Context, key: String, size: Int, stamp: Long): Bitmap? {
        return try {
            val f = File(dir(ctx), name(key, size, stamp))
            if (!f.exists()) null else BitmapFactory.decodeFile(f.absolutePath)
        } catch (_: Throwable) {
            null
        }
    }

    fun save(ctx: Context, key: String, size: Int, stamp: Long, bm: Bitmap) {
        try {
            val f = File(dir(ctx), name(key, size, stamp))
            FileOutputStream(f).use { out -> bm.compress(Bitmap.CompressFormat.PNG, 100, out) }
        } catch (_: Throwable) {
        }
    }

    /** Drops files that no longer match any installed app at the current size. */
    fun sweep(ctx: Context, keep: Set<String>) {
        try {
            val files = dir(ctx).listFiles() ?: return
            for (f in files) {
                if (!keep.contains(f.name)) f.delete()
            }
        } catch (_: Throwable) {
        }
    }

    fun fileName(key: String, size: Int, stamp: Long): String = name(key, size, stamp)

    fun clear(ctx: Context) {
        try {
            val files = dir(ctx).listFiles() ?: return
            for (f in files) f.delete()
        } catch (_: Throwable) {
        }
    }
}
