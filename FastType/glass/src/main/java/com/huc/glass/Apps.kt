package com.huc.glass

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.text.Collator

class AppEntry(val pkg: String, val cls: String, val label: String) {
    /** The white glyph, built once and kept. */
    @Volatile var glyph: Bitmap? = null
    /** False when the icon had no shape to carry and kept its own colours. */
    var mono = true
    val key: String get() = pkg + "/" + cls
}

/**
 * The installed apps, and the job of turning their icons into glass.
 *
 * A coloured square inside a pane of glass looks like a sticker. iOS gets its clear
 * look by throwing the icon's colour away and keeping only its silhouette, and
 * Android hands us exactly that when an icon ships a monochrome layer; failing that
 * the adaptive foreground usually works. Icons that are one full-bleed picture have
 * no silhouette to take, so those keep their colours rather than become white blobs.
 */
object Apps {

    /** Swapped whole, never mutated in place: the list is read while it is rebuilt. */
    @Volatile var all: List<AppEntry> = ArrayList()
        private set

    @Volatile var ready = false

    fun load(ctx: Context) {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN)
        q.addCategory(Intent.CATEGORY_LAUNCHER)
        val found: List<ResolveInfo> = try {
            pm.queryIntentActivities(q, 0)
        } catch (_: Throwable) {
            ArrayList()
        }
        val out = ArrayList<AppEntry>(found.size + 1)
        val seen = HashSet<String>()
        for (ri in found) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == null || ai.name == null) continue
            val k = ai.packageName + "/" + ai.name
            if (!seen.add(k)) continue
            var label = try {
                ri.loadLabel(pm).toString().trim()
            } catch (_: Throwable) {
                ""
            }
            if (label.isEmpty()) label = ai.packageName
            out.add(AppEntry(ai.packageName, ai.name, label))
        }
        val col = Collator.getInstance()
        out.sortWith(Comparator { a, b -> col.compare(a.label, b.label) })

        // keep the glyphs we already built, so a package change does not redo them all
        val prevList = all
        val old = HashMap<String, AppEntry>(prevList.size)
        for (e in prevList) old[e.key] = e
        for (e in out) {
            val p = old[e.key]
            if (p != null) {
                e.glyph = p.glyph
                e.mono = p.mono
            }
        }

        all = out
        ready = true
    }

    fun byKey(k: String): AppEntry? {
        for (e in all) if (e.key == k) return e
        return null
    }

    fun launch(ctx: Context, e: AppEntry): Boolean {
        return try {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_LAUNCHER)
            i.component = ComponentName(e.pkg, e.cls)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            ctx.startActivity(i)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun info(ctx: Context, e: AppEntry) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:" + e.pkg)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            ctx.startActivity(i)
        } catch (_: Throwable) {
        }
    }

    /**
     * Picks four sensible dock apps the first time: phone, browser, messages, camera,
     * each resolved by what actually handles that job on this phone.
     */
    fun defaultDock(ctx: Context): List<String> {
        val out = ArrayList<String>(4)
        val wanted = ArrayList<Intent>(4)

        wanted.add(Intent(Intent.ACTION_DIAL))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        web.addCategory(Intent.CATEGORY_BROWSABLE)
        wanted.add(web)
        wanted.add(Intent(Intent.ACTION_VIEW, Uri.parse("sms:")))
        wanted.add(Intent("android.media.action.STILL_IMAGE_CAMERA"))

        val pm = ctx.packageManager
        for (i in wanted) {
            val pkg = try {
                pm.resolveActivity(i, 0)?.activityInfo?.packageName
            } catch (_: Throwable) {
                null
            } ?: continue
            if (pkg == "android") continue
            val e = all.firstOrNull { it.pkg == pkg } ?: continue
            if (!out.contains(e.key)) out.add(e.key)
        }

        // top up from the list if anything did not resolve
        val list = all
        var i = 0
        while (out.size < 4 && i < list.size) {
            val k = list[i].key
            if (!out.contains(k)) out.add(k)
            i++
        }
        return out
    }

    // ---- the glyphs --------------------------------------------------------

    fun buildGlyph(ctx: Context, e: AppEntry, size: Int) {
        if (e.glyph != null || size <= 0) return
        val pm = ctx.packageManager
        var d: Drawable? = null
        try { d = pm.getActivityIcon(ComponentName(e.pkg, e.cls)) } catch (_: Throwable) {}
        if (d == null) {
            try { d = pm.getApplicationIcon(e.pkg) } catch (_: Throwable) {}
        }
        val src = d
        if (src == null) {
            e.glyph = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            e.mono = false
            return
        }

        var shape: Bitmap? = null

        if (src is AdaptiveIconDrawable) {
            // an icon that ships a monochrome layer has already drawn its own silhouette
            if (Build.VERSION.SDK_INT >= 33) {
                val m = try { src.monochrome } catch (_: Throwable) { null }
                if (m != null) {
                    val b = inSafeZone(m, size)
                    if (coverage(b) in 0.02f..0.92f) shape = b else b.recycle()
                }
            }
            if (shape == null) {
                val fg = try { src.foreground } catch (_: Throwable) { null }
                if (fg != null) {
                    val b = inSafeZone(fg, size)
                    if (coverage(b) in 0.02f..0.78f) shape = b else b.recycle()
                }
            }
        }

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.isFilterBitmap = true

        val s = shape
        if (s != null) {
            p.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            c.drawBitmap(s, 0f, 0f, p)
            s.recycle()
            e.mono = true
        } else {
            // no silhouette in there — let it keep its colours, pulled in a little
            val inset = size * 0.08f
            src.setBounds(0, 0, size, size)
            val tmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            src.draw(Canvas(tmp))
            c.drawBitmap(tmp, null, RectF(inset, inset, size - inset, size - inset), p)
            tmp.recycle()
            e.mono = false
        }
        e.glyph = out
    }

    /**
     * An adaptive layer is 108 units across and only the middle 72 are guaranteed to
     * be inside the mask, so render the whole thing at 1.5x and keep the centre.
     */
    private fun inSafeZone(d: Drawable, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val big = (size * 1.5f).toInt()
        val off = -((big - size) / 2)
        d.setBounds(off, off, off + big, off + big)
        try { d.draw(c) } catch (_: Throwable) {}
        return out
    }

    /** How much of the tile the glyph actually fills, 0..1. */
    private fun coverage(bm: Bitmap): Float {
        return try {
            val n = 26
            val s = Bitmap.createScaledBitmap(bm, n, n, true)
            var on = 0
            for (y in 0 until n) {
                for (x in 0 until n) {
                    if (Color.alpha(s.getPixel(x, y)) > 110) on++
                }
            }
            s.recycle()
            on.toFloat() / (n * n).toFloat()
        } catch (_: Throwable) {
            1f
        }
    }
}
