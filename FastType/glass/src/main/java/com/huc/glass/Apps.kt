package com.huc.glass

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import java.text.Collator

class AppEntry(val pkg: String, val cls: String, val label: String) {
    @Volatile var icon: Bitmap? = null
    val key: String get() = pkg + "/" + cls
}

/**
 * The installed apps, and the job of making their icons look like iOS icons.
 *
 * The app's own artwork is kept — only the outline changes. An adaptive icon is two
 * layers on a 108-unit canvas whose middle 72 are guaranteed visible, so both layers
 * are drawn at 1.5x and the squircle is cut out of the result. Letting
 * AdaptiveIconDrawable draw itself would apply ColorOS's mask instead of ours, which
 * is exactly what we are replacing, so the layers are drawn by hand.
 */
object Apps {

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
        val out = ArrayList<AppEntry>(found.size)
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

        // carry over the icons we already rendered
        val prev = all
        val old = HashMap<String, AppEntry>(prev.size)
        for (e in prev) old[e.key] = e
        for (e in out) {
            val p = old[e.key]
            if (p != null) e.icon = p.icon
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

    /** Phone, browser, messages, camera — resolved by what actually handles each job. */
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

        val list = all
        var i = 0
        while (out.size < 4 && i < list.size) {
            val k = list[i].key
            if (!out.contains(k)) out.add(k)
            i++
        }
        return out
    }

    // ---- icons -------------------------------------------------------------

    fun buildIcon(ctx: Context, e: AppEntry, size: Int) {
        if (e.icon != null || size <= 8) return
        val d = rawIcon(ctx, e)
        val tmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tmp)

        if (d is AdaptiveIconDrawable) {
            // both layers by hand, unmasked, so ColorOS's own shape never gets applied
            val big = (size * 1.5f).toInt()
            val off = -((big - size) / 2)
            val bg = try { d.background } catch (_: Throwable) { null }
            val fg = try { d.foreground } catch (_: Throwable) { null }
            if (bg == null && fg == null) {
                d.setBounds(0, 0, size, size)
                safeDraw(d, tc)
            } else {
                if (bg != null) {
                    bg.setBounds(off, off, off + big, off + big)
                    safeDraw(bg, tc)
                } else {
                    tc.drawColor(Color.WHITE)
                }
                if (fg != null) {
                    fg.setBounds(off, off, off + big, off + big)
                    safeDraw(fg, tc)
                }
            }
        } else if (d != null) {
            if (fillsCorners(d, size)) {
                d.setBounds(0, 0, size, size)
                safeDraw(d, tc)
            } else {
                // a legacy icon with its own silhouette: give it a tile to sit on,
                // the way a themed iOS icon set does
                tc.drawColor(tileColour(d, size))
                val inset = size * 0.14f
                val inner = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, size, size)
                safeDraw(d, Canvas(inner))
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.isFilterBitmap = true
                tc.drawBitmap(
                    inner, null,
                    RectF(inset, inset, size - inset, size - inset), p
                )
                inner.recycle()
            }
        } else {
            tc.drawColor(Color.argb(255, 120, 124, 132))
        }

        // cut the squircle out of whatever we just drew, with clean edges
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = BitmapShader(tmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        oc.drawPath(Squircle.path(size.toFloat()), p)
        tmp.recycle()
        e.icon = out
    }

    private fun safeDraw(d: Drawable, c: Canvas) {
        try { d.draw(c) } catch (_: Throwable) {}
    }

    /**
     * Loads the icon from the app's own resources rather than through the package
     * manager, which on ColorOS hands back an already-reshaped icon.
     */
    private fun rawIcon(ctx: Context, e: AppEntry): Drawable? {
        val pm = ctx.packageManager
        val dpi = ctx.resources.displayMetrics.densityDpi
        try {
            val ai = pm.getActivityInfo(ComponentName(e.pkg, e.cls), 0)
            val res = pm.getResourcesForApplication(ai.applicationInfo)
            val id = if (ai.icon != 0) ai.icon else ai.applicationInfo.icon
            if (id != 0) {
                val d = res.getDrawableForDensity(id, dpi, null)
                if (d != null) return d
            }
        } catch (_: Throwable) {
        }
        try { return pm.getActivityIcon(ComponentName(e.pkg, e.cls)) } catch (_: Throwable) {}
        try { return pm.getApplicationIcon(e.pkg) } catch (_: Throwable) {}
        return null
    }

    /** True when the artwork already reaches the corners, so masking is enough. */
    private fun fillsCorners(d: Drawable, size: Int): Boolean {
        return try {
            val n = 24
            val bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, n, n)
            safeDraw(d, Canvas(bm))
            var on = 0
            var total = 0
            for (y in 0 until n) {
                for (x in 0 until n) {
                    // only the band just inside the edge matters
                    val edge = x < 2 || y < 2 || x >= n - 2 || y >= n - 2
                    if (!edge) continue
                    total++
                    if (Color.alpha(bm.getPixel(x, y)) > 120) on++
                }
            }
            bm.recycle()
            total > 0 && on.toFloat() / total.toFloat() > 0.72f
        } catch (_: Throwable) {
            true
        }
    }

    /** A pale tile that suits the icon, for legacy artwork that needs a backing. */
    private fun tileColour(d: Drawable, size: Int): Int {
        return try {
            val n = 20
            val bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, n, n)
            safeDraw(d, Canvas(bm))
            var r = 0L; var g = 0L; var b = 0L; var c = 0L
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val px = bm.getPixel(x, y)
                    if (Color.alpha(px) < 150) continue
                    r += Color.red(px); g += Color.green(px); b += Color.blue(px); c++
                }
            }
            bm.recycle()
            if (c == 0L) return Color.argb(255, 244, 244, 247)
            // pull it most of the way to white so the artwork still reads on top
            val mr = (r / c).toInt(); val mg = (g / c).toInt(); val mb = (b / c).toInt()
            Color.argb(
                255,
                mr + ((255 - mr) * 0.80f).toInt(),
                mg + ((255 - mg) * 0.80f).toInt(),
                mb + ((255 - mb) * 0.80f).toInt()
            )
        } catch (_: Throwable) {
            Color.argb(255, 244, 244, 247)
        }
    }
}
