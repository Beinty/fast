package com.huc.glass

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max

/**
 * The background, and the frosted copy of it that every glass surface samples.
 *
 * Real glass needs to know what is behind it. A window cannot read the pixels the
 * system paints under it, so the home screen paints its own background instead:
 * a photo of his if he picked one, otherwise the phone's own wallpaper if the
 * platform still hands it over, otherwise a built-in gradient. Whichever it is,
 * we hold the bitmap, so blurring it is ours to do.
 */
object Wall {

    private const val FILE = "wall.jpg"

    @Volatile
    var src: Bitmap? = null
        private set

    /** The same picture, heavily softened — this is what the glass shows through. */
    @Volatile
    var blur: Bitmap? = null
        private set

    /** The frosted copy is kept at half size; the glass shader scales it back up. */
    @Volatile
    var blurScale = 2f
        private set

    /** True when the lower half of the background is dark, so the veil can lift. */
    @Volatile
    var dark = false
        private set

    private var builtFor = ""

    fun file(ctx: Context): File = File(ctx.filesDir, FILE)

    fun hasOwn(ctx: Context): Boolean = file(ctx).exists()

    /** Forces the next build to redo the work — call after the picture changes. */
    fun invalidate() {
        builtFor = ""
    }

    fun forget(ctx: Context) {
        try { file(ctx).delete() } catch (_: Throwable) {}
        GStore.ownWall = false
        invalidate()
    }

    /** Copies a picked image into our own storage, downscaled to something sane. */
    fun adopt(ctx: Context, input: InputStream, maxSide: Int): Boolean {
        return try {
            val raw = BitmapFactory.decodeStream(input) ?: return false
            val s = maxSide.toFloat() / max(raw.width, raw.height).toFloat()
            val bm = if (s < 1f) {
                Bitmap.createScaledBitmap(
                    raw, max(1, (raw.width * s).toInt()), max(1, (raw.height * s).toInt()), true
                )
            } else raw
            FileOutputStream(file(ctx)).use { out ->
                bm.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            if (bm !== raw) bm.recycle()
            raw.recycle()
            GStore.ownWall = true
            invalidate()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Builds the background and its frosted copy at the view's exact size. */
    @Synchronized
    fun build(ctx: Context, w: Int, h: Int, deviceDark: Boolean) {
        if (w <= 0 || h <= 0) return
        val key = w.toString() + "x" + h + if (deviceDark) "d" else "l"
        if (key == builtFor && src != null && blur != null) return

        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(base)
        var drawn = false

        val own = file(ctx)
        if (own.exists()) {
            try {
                val bm = BitmapFactory.decodeFile(own.absolutePath)
                if (bm != null) {
                    cover(c, bm, w, h)
                    bm.recycle()
                    drawn = true
                }
            } catch (_: Throwable) {
            }
        }

        if (!drawn) {
            // Android 13 and up usually refuse this to anyone but the system; try anyway,
            // because when it works his real wallpaper is the best possible background
            try {
                val d: Drawable? = WallpaperManager.getInstance(ctx).drawable
                if (d != null && d.intrinsicWidth > 0) {
                    coverDrawable(c, d, w, h)
                    drawn = true
                }
            } catch (_: Throwable) {
            }
        }

        if (!drawn) builtIn(c, w, h, deviceDark)

        src = base
        blur = soften(base, w, h)
        dark = sampleDark(base)
        builtFor = key
    }

    // ---- painting the sources ----------------------------------------------

    private fun cover(c: Canvas, bm: Bitmap, w: Int, h: Int) {
        val s = max(w.toFloat() / bm.width.toFloat(), h.toFloat() / bm.height.toFloat())
        val dw = bm.width * s
        val dh = bm.height * s
        val l = (w - dw) / 2f
        val t = (h - dh) / 2f
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(bm, null, RectF(l, t, l + dw, t + dh), p)
    }

    private fun coverDrawable(c: Canvas, d: Drawable, w: Int, h: Int) {
        val iw = if (d.intrinsicWidth > 0) d.intrinsicWidth else w
        val ih = if (d.intrinsicHeight > 0) d.intrinsicHeight else h
        val s = max(w.toFloat() / iw.toFloat(), h.toFloat() / ih.toFloat())
        val dw = (iw * s).toInt()
        val dh = (ih * s).toInt()
        val l = (w - dw) / 2
        val t = (h - dh) / 2
        d.setBounds(l, t, l + dw, t + dh)
        d.draw(c)
    }

    /** The fallback wallpaper — one for the light theme, one for the dark. */
    private fun builtIn(c: Canvas, w: Int, h: Int, deviceDark: Boolean) {
        val fw = w.toFloat()
        val fh = h.toFloat()
        val p = Paint()
        if (deviceDark) {
            p.shader = LinearGradient(
                0f, 0f, fw * 0.3f, fh,
                intArrayOf(0xFF070D1C.toInt(), 0xFF0F1D3D.toInt(), 0xFF05070F.toInt()),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, fw, fh, p)
            glow(c, fw * 0.2f, fh * 0.12f, fh * 0.42f, 0x5C2A6CFF, fw, fh)
            glow(c, fw * 0.88f, fh * 0.78f, fh * 0.40f, 0x4400D4C8, fw, fh)
        } else {
            p.shader = LinearGradient(
                0f, 0f, fw * 0.35f, fh,
                intArrayOf(0xFFFF7E5F.toInt(), 0xFFC2468A.toInt(), 0xFF3B2A7A.toInt()),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, fw, fh, p)
            glow(c, fw * 0.18f, fh * 0.08f, fh * 0.45f, 0x66FFD9A3, fw, fh)
            glow(c, fw * 0.85f, fh * 0.22f, fh * 0.40f, 0x55FF9F7A, fw, fh)
            glow(c, fw * 0.5f, fh * 1.0f, fh * 0.5f, 0x555B3BB0, fw, fh)
        }
    }

    private fun glow(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, w: Float, h: Float) {
        val p = Paint()
        p.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(color, color and 0x00FFFFFF),
            null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, p)
    }

    // ---- the frost ---------------------------------------------------------

    /**
     * Two shrink passes and one filtered stretch back. It is not a gaussian, but at
     * this scale the eye cannot tell, and it costs a couple of milliseconds instead
     * of a render-script dependency.
     */
    private fun soften(base: Bitmap, w: Int, h: Int): Bitmap {
        val sw = max(4, w / 20)
        val sh = max(4, h / 20)
        val a = Bitmap.createScaledBitmap(base, sw, sh, true)
        val b = Bitmap.createScaledBitmap(a, max(3, sw / 2), max(3, sh / 2), true)
        val mid = Bitmap.createScaledBitmap(b, max(6, w / 6), max(6, h / 6), true)
        // half size is plenty: the shader stretches it back with filtering, which only
        // softens it further, and it saves several megabytes of bitmap
        val ow = max(2, w / 2)
        val oh = max(2, h / 2)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val m = ColorMatrix()
        m.setSaturation(1.85f)
        p.colorFilter = ColorMatrixColorFilter(m)
        c.drawBitmap(mid, null, RectF(0f, 0f, ow.toFloat(), oh.toFloat()), p)
        a.recycle()
        b.recycle()
        mid.recycle()
        blurScale = w.toFloat() / ow.toFloat()
        return out
    }

    /** Looks at the bottom third — where the dock sits — and reports its brightness. */
    private fun sampleDark(bm: Bitmap): Boolean {
        return try {
            val n = 12
            var sum = 0.0
            val y0 = (bm.height * 0.62f).toInt()
            val y1 = bm.height - 1
            for (i in 0 until n) {
                for (j in 0 until n) {
                    val x = (bm.width - 1) * i / (n - 1)
                    val y = y0 + (y1 - y0) * j / (n - 1)
                    val px = bm.getPixel(x, y)
                    sum += 0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px)
                }
            }
            (sum / (n * n)) < 118.0
        } catch (_: Throwable) {
            false
        }
    }
}
