package com.huc.glass

import android.graphics.Matrix
import android.graphics.Path

/**
 * The iOS icon outline.
 *
 * It is not a rounded rectangle. Apple uses a continuous corner — a superellipse,
 * |x|^n + |y|^n = 1 — and n ≈ 5 lands on the shape the eye reads as an app icon.
 * A plain drawRoundRect gives corners that visibly kink where the arc meets the
 * straight edge; this does not.
 */
object Squircle {

    private const val N = 5.0
    private const val STEPS = 180

    private val unit: Path = build()

    private var cached: Path? = null
    private var cachedSize = -1f

    private fun build(): Path {
        val p = Path()
        var i = 0
        while (i <= STEPS) {
            val t = i.toDouble() / STEPS.toDouble() * 2.0 * Math.PI
            val c = Math.cos(t)
            val s = Math.sin(t)
            val x = Math.signum(c) * Math.pow(Math.abs(c), 2.0 / N)
            val y = Math.signum(s) * Math.pow(Math.abs(s), 2.0 / N)
            val fx = ((x + 1.0) / 2.0).toFloat()
            val fy = ((y + 1.0) / 2.0).toFloat()
            if (i == 0) p.moveTo(fx, fy) else p.lineTo(fx, fy)
            i++
        }
        p.close()
        return p
    }

    /** A squircle sized `size` with its top-left at the origin. */
    @Synchronized
    fun path(size: Float): Path {
        val c = cached
        if (c != null && cachedSize == size) return c
        val p = Path(unit)
        val m = Matrix()
        m.setScale(size, size)
        p.transform(m)
        cached = p
        cachedSize = size
        return p
    }
}
