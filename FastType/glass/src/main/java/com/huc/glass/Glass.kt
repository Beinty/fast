package com.huc.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * One rounded pane of glass.
 *
 * Four things, in this order, are what separate glass from a translucent rectangle:
 * the softened background showing through, a white veil to lift it off that
 * background, a specular sweep running corner to corner, and a half-pixel bright
 * edge. Drop any one of them and it stops reading as glass.
 */
object Glass {

    private val back = Paint(Paint.ANTI_ALIAS_FLAG)
    private val veil = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)

    private val sweep = LinearGradient(
        0f, 0f, 1f, 1f,
        intArrayOf(0x99FFFFFF.toInt(), 0x2EFFFFFF, 0x00FFFFFF, 0x14FFFFFF, 0x45FFFFFF),
        floatArrayOf(0f, 0.26f, 0.48f, 0.82f, 1f),
        Shader.TileMode.CLAMP
    )
    private val mtx = Matrix()

    private var shaderBm: Bitmap? = null
    private var shader: BitmapShader? = null

    init {
        edge.style = Paint.Style.STROKE
        shade.style = Paint.Style.FILL
        back.isFilterBitmap = true
    }

    /** The veil weight, by the three frost levels and by how dark the wall is. */
    private fun veilAlpha(dark: Boolean): Int {
        val base = if (dark) 48 else 40
        val step = when (GStore.glass) {
            0 -> -14
            2 -> 20
            else -> 0
        }
        val a = base + step
        return if (a < 14) 14 else if (a > 110) 110 else a
    }

    private fun shaderFor(bm: Bitmap): BitmapShader {
        val cur = shader
        if (cur != null && shaderBm === bm) return cur
        val made = BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        // the frosted copy is kept smaller than the screen; stretch it back into place
        val s = Wall.blurScale
        if (s != 1f) {
            val m = Matrix()
            m.setScale(s, s)
            made.setLocalMatrix(m)
        }
        shader = made
        shaderBm = bm
        return made
    }

    /** Drops the cached shader — the background bitmap changed under us. */
    fun reset() {
        shader = null
        shaderBm = null
    }

    fun draw(c: Canvas, r: RectF, rad: Float, blur: Bitmap?, dark: Boolean, shadow: Boolean) {
        if (r.width() <= 0f || r.height() <= 0f) return

        if (shadow) {
            // three expanding passes stand in for a soft shadow; setShadowLayer is not
            // dependable on a hardware canvas for shapes
            var i = 3
            while (i >= 1) {
                val g = i * 3f
                val dy = g * 0.55f
                shade.color = Color.argb(13, 0, 0, 0)
                c.drawRoundRect(
                    r.left - g, r.top - g + dy, r.right + g, r.bottom + g + dy,
                    rad + g, rad + g, shade
                )
                i--
            }
        }

        // the background, softened, sampled exactly where this pane sits
        if (blur != null) {
            back.shader = shaderFor(blur)
            back.alpha = 255
            c.drawRoundRect(r, rad, rad, back)
            back.shader = null
        } else {
            back.shader = null
            back.color = if (dark) Color.argb(60, 255, 255, 255) else Color.argb(50, 0, 0, 0)
            c.drawRoundRect(r, rad, rad, back)
        }

        veil.color = Color.argb(veilAlpha(dark), 255, 255, 255)
        c.drawRoundRect(r, rad, rad, veil)

        mtx.setScale(r.width(), r.height())
        mtx.postTranslate(r.left, r.top)
        sweep.setLocalMatrix(mtx)
        spec.shader = sweep
        c.drawRoundRect(r, rad, rad, spec)
        spec.shader = null

        val sw = 1.2f
        edge.strokeWidth = sw
        edge.color = Color.argb(74, 255, 255, 255)
        c.drawRoundRect(
            r.left + sw / 2f, r.top + sw / 2f, r.right - sw / 2f, r.bottom - sw / 2f,
            rad, rad, edge
        )
    }
}
