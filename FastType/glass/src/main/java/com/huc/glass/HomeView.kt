package com.huc.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The home screen.
 *
 * Every ratio here was measured off a screenshot of the real thing, in pixels, on a
 * 1175-wide screen: icon 180, side margin 90, column gap 91.7, row pitch 272, dock
 * panel 1095x272 with a radius of 85 sitting 95 up from the bottom. Pages run
 * sideways; nothing scrolls down.
 *
 * The window shows the live wallpaper underneath, so this view paints no background
 * of its own — that is also why coming back from an app is instant.
 */
class HomeView(ctx: Context) : View(ctx) {

    interface Host {
        fun openApp(e: AppEntry)
        fun appInfo(e: AppEntry)
        fun pickWallpaper()
        fun homeSettings()
        fun search()
        fun openSettings()
        fun pageOffset(fraction: Float)
    }

    companion object {
        private const val COLS = 4
        private const val LONG_MS = 400L

        // fractions of the screen width, straight off the measurements
        private const val F_SIDE = 0.0766f
        private const val F_ICON = 0.1532f
        private const val F_GAPX = 0.0780f
        private const val F_PITCH = 0.2315f
        private const val F_LABEL_GAP = 0.0128f
        private const val F_LABEL = 0.0289f
        private const val F_GRID_TOP = 0.062f
        private const val F_DOCK_SIDE = 0.034f
        private const val F_DOCK_PAD = 0.0391f      // 46 / 1175
        private const val F_DOCK_BOTTOM = 0.0298f
        private const val F_PILL_H = 0.0443f
        private const val F_PILL_W = 0.1870f
        private const val F_PILL_GAP = 0.0545f
    }

    var host: Host? = null

    /** True when the wallpaper is pale, so the dock has to darken instead of lighten. */
    var lightWall = false

    private var insetTop = 0
    private var insetBottom = 0

    private var sideM = 0f
    private var gapX = 0f
    private var iconW = 0f
    private var pitch = 0f
    private var labelGap = 0f
    private var gridTop = 0f
    private var rows = 6
    private var perPage = 24
    /** Follows the phone's language: Arabic fills from the right, English from the left. */
    private var rtl = false
    private var dir = 1f

    private val dockR = RectF()
    private var dockRad = 0f
    private var dockPad = 0f

    private val pillR = RectF()
    private var dotsCy = 0f
    private var dotR = 0f
    private var dotGap = 0f

    private val dockApps = ArrayList<AppEntry>(4)
    private val gridApps = ArrayList<AppEntry>()
    private var pages = 1

    private var scrollX = 0f
    private var maxScrollX = 0f
    private var lastOffset = -1f

    private val scroller = OverScroller(ctx)
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var pressed: AppEntry? = null

    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmp = RectF()

    private var iconPx = 0
    private var iconGen = 0

    private class MItem(val text: String, val act: Int)

    private var menu: List<MItem>? = null
    private var menuApp: AppEntry? = null
    private val menuR = RectF()
    private var menuRowH = 0f
    private var menuPress = -1

    private val longPress = Runnable {
        if (!dragging && menu == null) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val e = pressed
            if (e != null) openAppMenu(e) else openHomeMenu()
        }
    }

    init {
        isClickable = true
        label.color = Color.WHITE
        label.textAlign = Paint.Align.CENTER
        label.setShadowLayer(4f, 0f, 1f, Color.argb(150, 0, 0, 0))
        icoPaint.isFilterBitmap = true
        stroke.style = Paint.Style.STROKE
    }

    fun setInsets(top: Int, bottom: Int) {
        insetTop = top
        insetBottom = bottom
        measureAll()
        invalidate()
    }

    fun appsChanged() {
        rebuildLists()
        iconPx = 0
        measureAll()
        ensureIcons()
        invalidate()
    }

    fun closeMenu(): Boolean {
        if (menu == null) return false
        menu = null
        menuApp = null
        menuPress = -1
        invalidate()
        return true
    }

    /** Home pressed while already here: back to the first page. */
    fun toFirstPage() {
        if (scrollX == 0f) return
        scroller.forceFinished(true)
        scroller.startScroll(scrollX.toInt(), 0, -scrollX.toInt(), 0, 260)
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        measureAll()
        ensureIcons()
    }

    // ---- layout ------------------------------------------------------------

    private fun measureAll() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        dir = if (rtl) -1f else 1f

        sideM = w * F_SIDE
        gapX = w * F_GAPX
        iconW = w * F_ICON
        pitch = w * F_PITCH
        labelGap = w * F_LABEL_GAP
        label.textSize = w * F_LABEL

        dockPad = w * F_DOCK_PAD
        val dockH = iconW + dockPad * 2f
        val dockSide = w * F_DOCK_SIDE
        val dockBottom = h - insetBottom - w * F_DOCK_BOTTOM
        dockR.set(dockSide, dockBottom - dockH, w - dockSide, dockBottom)
        dockRad = dockH * 0.3125f

        val pillH = w * F_PILL_H
        val pillW = w * F_PILL_W
        val pillBottom = dockR.top - w * F_PILL_GAP
        pillR.set((w - pillW) / 2f, pillBottom - pillH, (w + pillW) / 2f, pillBottom)

        dotR = w * 0.0043f
        dotGap = w * 0.0145f
        dotsCy = pillR.top - w * 0.035f

        gridTop = insetTop + w * F_GRID_TOP
        val room = (dotsCy - dotR - w * 0.02f) - gridTop
        var r = (room / pitch).toInt()
        if (r > 6) r = 6
        if (r < 3) r = 3
        rows = r
        perPage = rows * COLS

        pages = if (gridApps.isEmpty()) 1
        else ceil(gridApps.size.toFloat() / perPage.toFloat()).toInt()
        if (pages < 1) pages = 1
        maxScrollX = (pages - 1) * w
        if (scrollX > maxScrollX) scrollX = maxScrollX
        if (scrollX < 0f) scrollX = 0f
    }

    private fun rebuildLists() {
        dockApps.clear()
        var keys = GStore.dock
        if (keys.isEmpty() && Apps.all.isNotEmpty()) {
            keys = Apps.defaultDock(context)
            GStore.dock = keys
        }
        for (k in keys) {
            if (dockApps.size >= COLS) break
            val e = Apps.byKey(k)
            if (e != null) dockApps.add(e)
        }
        gridApps.clear()
        for (e in Apps.all) if (!dockApps.contains(e)) gridApps.add(e)
    }

    /** Column 0 sits where reading starts: right in Arabic, left in English. */
    private fun colX(col: Int): Float =
        if (rtl) width - sideM - (col + 1) * iconW - col * gapX
        else sideM + col * (iconW + gapX)

    private fun ensureIcons() {
        if (iconW <= 0f || Apps.all.isEmpty()) return
        val size = iconW.toInt()
        if (size <= 8 || size == iconPx) return
        iconPx = size
        val gen = ++iconGen
        val appCtx = context.applicationContext
        val list = ArrayList(Apps.all)
        Thread {
            var n = 0
            for (e in list) {
                if (gen != iconGen) return@Thread
                Apps.buildIcon(appCtx, e, size)
                n++
                if (n % 6 == 0) postInvalidateOnAnimation()
            }
            postInvalidateOnAnimation()
        }.start()
    }

    // ---- drawing -----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat()
            postInvalidateOnAnimation()
        }
        if (iconW <= 0f) return

        val w = width.toFloat()
        val off = if (maxScrollX > 0f) scrollX / maxScrollX else 0f
        if (abs(off - lastOffset) > 0.004f) {
            lastOffset = off
            host?.pageOffset(off)
        }

        // only the one or two pages on screen
        val first = (scrollX / w).toInt()
        var p = first
        while (p <= first + 1 && p < pages) {
            if (p >= 0) drawPage(canvas, p, (p * w - scrollX) * dir)
            p++
        }

        if (pages > 1) drawDots(canvas)
        drawPill(canvas)
        drawDock(canvas)
        drawMenu(canvas)
    }

    private fun drawPage(c: Canvas, page: Int, dx: Float) {
        val start = page * perPage
        var i = 0
        while (i < perPage) {
            val idx = start + i
            if (idx >= gridApps.size) break
            val row = i / COLS
            val col = i % COLS
            val x = colX(col) + dx
            val y = gridTop + row * pitch
            tmp.set(x, y, x + iconW, y + iconW)
            drawApp(c, gridApps[idx], tmp, true)
            i++
        }
    }

    private fun drawApp(c: Canvas, e: AppEntry, r: RectF, withLabel: Boolean) {
        val bm: Bitmap? = e.icon
        icoPaint.alpha = if (e === pressed && menu == null) 150 else 255
        if (bm != null) {
            c.drawBitmap(bm, null, r, icoPaint)
        } else {
            fill.color = Color.argb(60, 255, 255, 255)
            c.drawRoundRect(r, iconW * 0.225f, iconW * 0.225f, fill)
        }
        if (withLabel && GStore.labels) {
            label.alpha = icoPaint.alpha
            c.drawText(
                fit(label, e.label, iconW * 1.22f),
                r.centerX(), r.bottom + labelGap + label.textSize * 0.86f, label
            )
            label.alpha = 255
        }
    }

    private fun drawDock(c: Canvas) {
        fill.color = if (lightWall) Color.argb(40, 0, 0, 0) else Color.argb(33, 255, 255, 255)
        c.drawRoundRect(dockR, dockRad, dockRad, fill)
        stroke.strokeWidth = 1.2f
        stroke.color = if (lightWall) Color.argb(26, 0, 0, 0) else Color.argb(44, 255, 255, 255)
        c.drawRoundRect(
            dockR.left + 0.6f, dockR.top + 0.6f, dockR.right - 0.6f, dockR.bottom - 0.6f,
            dockRad, dockRad, stroke
        )
        var i = 0
        while (i < dockApps.size) {
            val x = colX(i)
            val y = dockR.top + dockPad
            tmp.set(x, y, x + iconW, y + iconW)
            drawApp(c, dockApps[i], tmp, false)
            i++
        }
    }

    private fun drawPill(c: Canvas) {
        val rad = pillR.height() / 2f
        fill.color = if (lightWall) Color.argb(36, 0, 0, 0) else Color.argb(38, 255, 255, 255)
        c.drawRoundRect(pillR, rad, rad, fill)

        val ts = pillR.height() * 0.46f
        label.textSize = ts
        val cy = pillR.centerY() + ts * 0.36f
        val gl = pillR.height() * 0.30f
        // the magnifier leads the word: on its right in Arabic, on its left in English
        val gx = pillR.centerX() + pillR.width() * 0.18f * dir
        stroke.strokeWidth = pillR.height() * 0.075f
        stroke.color = Color.WHITE
        c.drawCircle(gx, pillR.centerY() - gl * 0.12f, gl * 0.42f, stroke)
        c.drawLine(
            gx + gl * 0.30f, pillR.centerY() + gl * 0.18f,
            gx + gl * 0.52f, pillR.centerY() + gl * 0.42f, stroke
        )
        c.drawText(
            context.getString(R.string.search),
            pillR.centerX() - pillR.width() * 0.05f * dir, cy, label
        )
        label.textSize = width * F_LABEL
    }

    private fun drawDots(c: Canvas) {
        val span = (pages - 1) * dotGap
        val startX = width / 2f - span / 2f * dir
        val cur = if (maxScrollX > 0f) Math.round(scrollX / width.toFloat()) else 0
        var i = 0
        while (i < pages) {
            fill.color = if (i == cur) Color.argb(235, 255, 255, 255)
            else Color.argb(92, 255, 255, 255)
            c.drawCircle(startX + i * dotGap * dir, dotsCy, dotR, fill)
            i++
        }
    }

    private fun drawMenu(c: Canvas) {
        val items = menu ?: return
        val rad = iconW * 0.16f
        fill.color = Color.argb(238, 28, 28, 30)
        c.drawRoundRect(menuR, rad, rad, fill)
        stroke.strokeWidth = 1f
        stroke.color = Color.argb(30, 255, 255, 255)
        c.drawRoundRect(
            menuR.left + 0.5f, menuR.top + 0.5f, menuR.right - 0.5f, menuR.bottom - 0.5f,
            rad, rad, stroke
        )

        label.textAlign = Paint.Align.RIGHT
        label.setShadowLayer(0f, 0f, 0f, 0)
        val pad = iconW * 0.20f
        val ts = iconW * 0.185f
        label.textSize = ts
        var i = 0
        while (i < items.size) {
            val top = menuR.top + i * menuRowH
            if (i == menuPress) {
                fill.color = Color.argb(30, 255, 255, 255)
                c.drawRect(menuR.left, top, menuR.right, top + menuRowH, fill)
            }
            if (i > 0) {
                fill.color = Color.argb(26, 255, 255, 255)
                c.drawRect(menuR.left + pad, top, menuR.right - pad, top + 1f, fill)
            }
            c.drawText(
                fit(label, items[i].text, menuR.width() - pad * 2f),
                menuR.right - pad, top + menuRowH / 2f + ts * 0.36f, label
            )
            i++
        }
        label.textAlign = Paint.Align.CENTER
        label.textSize = width * F_LABEL
        label.setShadowLayer(4f, 0f, 1f, Color.argb(150, 0, 0, 0))
    }

    private fun fit(p: Paint, s: String, max: Float): String {
        if (p.measureText(s) <= max) return s
        var cut = s.length - 1
        while (cut > 1) {
            val t = s.substring(0, cut) + "…"
            if (p.measureText(t) <= max) return t
            cut--
        }
        return "…"
    }

    // ---- sheets ------------------------------------------------------------

    private fun openAppMenu(e: AppEntry) {
        val inDock = dockApps.contains(e)
        val items = ArrayList<MItem>(3)
        items.add(
            MItem(context.getString(if (inDock) R.string.dock_remove else R.string.dock_add), 1)
        )
        items.add(MItem(context.getString(R.string.app_info), 2))
        items.add(MItem(context.getString(R.string.home_settings), 8))
        menuApp = e
        showMenu(items)
    }

    private fun openHomeMenu() {
        val items = ArrayList<MItem>(4)
        items.add(MItem(context.getString(R.string.wallpaper), 3))
        items.add(
            MItem(context.getString(if (GStore.labels) R.string.labels_hide else R.string.labels_show), 6)
        )
        items.add(MItem(context.getString(R.string.default_home), 7))
        items.add(MItem(context.getString(R.string.sec_speed), 9))
        menuApp = null
        showMenu(items)
    }

    private fun showMenu(items: List<MItem>) {
        pressed = null
        dragging = false
        removeCallbacks(longPress)
        menuRowH = iconW * 0.50f
        val mw = width * 0.70f
        val mh = items.size * menuRowH
        val left = (width - mw) / 2f
        var top = height * 0.46f - mh / 2f
        val minTop = insetTop + iconW * 0.3f
        val maxTop = dockR.top - mh - iconW * 0.3f
        if (top < minTop) top = minTop
        if (maxTop > minTop && top > maxTop) top = maxTop
        menuR.set(left, top, left + mw, top + mh)
        menu = items
        menuPress = -1
        invalidate()
    }

    private fun act(a: Int) {
        val e = menuApp
        closeMenu()
        when (a) {
            1 -> {
                if (e != null) {
                    val keys = ArrayList(GStore.dock)
                    if (keys.contains(e.key)) {
                        keys.remove(e.key)
                    } else {
                        while (keys.size >= COLS) keys.removeAt(keys.size - 1)
                        keys.add(0, e.key)
                    }
                    GStore.dock = keys
                    appsChanged()
                }
            }
            2 -> if (e != null) host?.appInfo(e)
            3 -> host?.pickWallpaper()
            6 -> {
                GStore.labels = !GStore.labels
                invalidate()
            }
            7 -> host?.homeSettings()
            8 -> openHomeMenu()
            9 -> host?.openSettings()
        }
    }

    // ---- touch -------------------------------------------------------------

    private fun hitRow(y: Float): Int {
        if (menuRowH <= 0f) return -1
        val items = menu ?: return -1
        val i = ((y - menuR.top) / menuRowH).toInt()
        return if (i in items.indices) i else -1
    }

    private fun findApp(x: Float, y: Float): AppEntry? {
        if (y >= dockR.top && y <= dockR.bottom) {
            var i = 0
            while (i < dockApps.size) {
                val cx = colX(i)
                tmp.set(cx, dockR.top + dockPad, cx + iconW, dockR.top + dockPad + iconW)
                if (grown(tmp, false).contains(x, y)) return dockApps[i]
                i++
            }
            return null
        }
        if (y < gridTop - iconW * 0.2f) return null
        val w = width.toFloat()
        val page = Math.round(scrollX / w)
        val dx = (page * w - scrollX) * dir
        val start = page * perPage
        var i = 0
        while (i < perPage) {
            val idx = start + i
            if (idx >= gridApps.size) break
            val row = i / COLS
            val col = i % COLS
            val cx = colX(col) + dx
            val cy = gridTop + row * pitch
            tmp.set(cx, cy, cx + iconW, cy + iconW)
            if (grown(tmp, true).contains(x, y)) return gridApps[idx]
            i++
        }
        return null
    }

    private fun grown(r: RectF, withLabel: Boolean): RectF {
        val gx = gapX * 0.45f
        val below = if (withLabel) labelGap + label.textSize * 1.2f else iconW * 0.14f
        return RectF(r.left - gx, r.top - iconW * 0.12f, r.right + gx, r.bottom + below)
    }

    private fun snap(velocity: Float) {
        val w = width.toFloat()
        if (w <= 0f) return
        var target = Math.round(scrollX / w)
        if (abs(velocity) > 700f) {
            // a flick decides the direction regardless of how far it travelled
            target = if (velocity < 0f) (scrollX / w).toInt() + 1 else ceil(scrollX / w).toInt() - 1
        }
        if (target < 0) target = 0
        if (target > pages - 1) target = pages - 1
        val dest = target * w
        scroller.forceFinished(true)
        scroller.startScroll(scrollX.toInt(), 0, (dest - scrollX).toInt(), 0, 300)
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y

        if (menu != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (menuR.contains(x, y)) {
                        menuPress = hitRow(y)
                        invalidate()
                    } else {
                        closeMenu()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val r = if (menuR.contains(x, y)) hitRow(y) else -1
                    if (r != menuPress) {
                        menuPress = r
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val items = menu
                    val r = menuPress
                    if (items != null && r >= 0 && r < items.size) act(items[r].act) else closeMenu()
                }
                MotionEvent.ACTION_CANCEL -> closeMenu()
            }
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = x
                downY = y
                lastX = x
                dragging = false
                pressed = findApp(x, y)
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                removeCallbacks(longPress)
                postDelayed(longPress, LONG_MS)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (!dragging && abs(x - downX) > slop && abs(x - downX) > abs(y - downY)) {
                    dragging = true
                    pressed = null
                    removeCallbacks(longPress)
                }
                if (!dragging && abs(y - downY) > slop) {
                    pressed = null
                    removeCallbacks(longPress)
                    invalidate()
                }
                if (dragging && maxScrollX > 0f) {
                    scrollX -= (x - lastX) * dir
                    val over = width * 0.12f
                    if (scrollX < -over) scrollX = -over
                    if (scrollX > maxScrollX + over) scrollX = maxScrollX + over
                    invalidate()
                }
                lastX = x
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val e = pressed
                if (dragging) {
                    vt?.addMovement(ev)
                    vt?.computeCurrentVelocity(1000)
                    snap(vt?.xVelocity ?: 0f)
                } else if (e != null) {
                    pressed = null
                    invalidate()
                    host?.openApp(e)
                } else if (pillR.contains(x, y)) {
                    host?.search()
                }
                pressed = null
                dragging = false
                vt?.recycle()
                vt = null
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) snap(0f)
                pressed = null
                dragging = false
                vt?.recycle()
                vt = null
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(longPress)
        vt?.recycle()
        vt = null
    }
}
