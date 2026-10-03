package com.huc.glass

import android.content.Context
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
 * The home screen, drawn on one canvas.
 *
 * Same shape as the keyboard: every rect is measured once and the whole screen is a
 * single view, so a scroll never walks a view tree. The glass itself lives in
 * Glass, the background and its frosted copy in Wall.
 */
class HomeView(ctx: Context) : View(ctx) {

    interface Host {
        fun openApp(e: AppEntry)
        fun appInfo(e: AppEntry)
        fun pickWall()
        fun forgetWall()
        fun homeSettings()
    }

    companion object {
        private const val COLS = 4
        private const val LONG_MS = 420L
    }

    var host: Host? = null

    var deviceDark = false

    private var insetTop = 0
    private var insetBottom = 0

    // measured layout
    private var sideM = 0f
    private var gapX = 0f
    private var iconW = 0f
    private var iconRad = 0f
    private var labelGap = 0f
    private var labelH = 0f
    private var rowH = 0f
    private var gridTop = 0f
    private var gridBottom = 0f
    private var dockPad = 0f
    private var dockRad = 0f
    private val dockR = RectF()

    private val dockApps = ArrayList<AppEntry>(4)
    private val gridApps = ArrayList<AppEntry>()

    private var scroll = 0f
    private var maxScroll = 0f

    private val scroller = OverScroller(ctx)
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var pressed: AppEntry? = null

    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmp = RectF()

    private var glyphSize = 0
    private var glyphGen = 0
    private var wallGen = 0

    // the long-press sheet
    private class MItem(val text: String, val act: Int)

    private var menu: List<MItem>? = null
    private var menuApp: AppEntry? = null
    private val menuR = RectF()
    private var menuRowH = 0f
    private var menuPress = -1

    /** One timer for both sheets: whatever the finger landed on decides which opens. */
    private val longPress = Runnable {
        if (!dragging && menu == null) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val e = pressed
            if (e != null) openAppMenu(e) else openWallMenu()
        }
    }

    init {
        isClickable = true
        label.color = Color.WHITE
        label.textAlign = Paint.Align.CENTER
        label.setShadowLayer(3.5f, 0f, 1f, Color.argb(140, 0, 0, 0))
        glyphPaint.isFilterBitmap = true
    }

    fun setInsets(top: Int, bottom: Int) {
        insetTop = top
        insetBottom = bottom
        measureAll()
        invalidate()
    }

    /** Called once the app list has been read, and again when a package changes. */
    fun appsChanged() {
        rebuildLists()
        glyphSize = 0
        measureAll()
        ensureGlyphs()
        invalidate()
    }

    fun wallChanged() {
        Glass.reset()
        ensureWall()
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

    fun toTop() {
        scroller.forceFinished(true)
        scroll = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        measureAll()
        ensureWall()
        ensureGlyphs()
    }

    // ---- layout ------------------------------------------------------------

    private fun measureAll() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        sideM = w * 0.082f
        gapX = w * 0.068f
        iconW = (w - sideM * 2f - gapX * (COLS - 1)) / COLS
        iconRad = iconW * 0.225f

        label.textSize = iconW * 0.175f
        labelGap = iconW * 0.11f
        labelH = if (GStore.labels) label.textSize * 1.3f else 0f
        rowH = iconW + labelGap + labelH + iconW * 0.26f

        dockPad = iconW * 0.185f
        val dockH = iconW + dockPad * 2f
        val dockSide = w * 0.034f
        val dockBottom = h - insetBottom - w * 0.03f
        dockR.set(dockSide, dockBottom - dockH, w - dockSide, dockBottom)
        dockRad = dockH * 0.40f

        gridTop = insetTop + w * 0.055f
        gridBottom = dockR.top - w * 0.035f

        val rows = ceil(gridApps.size.toFloat() / COLS.toFloat()).toInt()
        val contentH = rows * rowH
        val viewport = gridBottom - gridTop
        maxScroll = if (contentH > viewport) contentH - viewport else 0f
        if (scroll > maxScroll) scroll = maxScroll
        if (scroll < 0f) scroll = 0f
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

    /** Right to left, the way an Arabic home screen fills. */
    private fun gridRect(i: Int, out: RectF) {
        val row = i / COLS
        val col = i % COLS
        val x = width - sideM - (col + 1) * iconW - col * gapX
        val y = gridTop + row * rowH - scroll
        out.set(x, y, x + iconW, y + iconW)
    }

    private fun dockRect(i: Int, out: RectF) {
        val inner = dockR.width() - dockPad * 2f
        val cell = inner / COLS
        val right = dockR.right - dockPad - cell * i
        val cx = right - cell / 2f
        val cy = dockR.centerY()
        out.set(cx - iconW / 2f, cy - iconW / 2f, cx + iconW / 2f, cy + iconW / 2f)
    }

    // ---- background and glyphs --------------------------------------------

    private fun ensureWall() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val gen = ++wallGen
        val appCtx = context.applicationContext
        val d = deviceDark
        Thread {
            Wall.build(appCtx, w, h, d)
            if (gen == wallGen) {
                post {
                    Glass.reset()
                    invalidate()
                }
            }
        }.start()
    }

    private fun ensureGlyphs() {
        if (iconW <= 0f || Apps.all.isEmpty()) return
        val size = (iconW * 0.74f).toInt()
        if (size <= 8 || size == glyphSize) return
        glyphSize = size
        val gen = ++glyphGen
        val appCtx = context.applicationContext
        val list = ArrayList(Apps.all)
        Thread {
            var n = 0
            for (e in list) {
                if (gen != glyphGen) return@Thread
                Apps.buildGlyph(appCtx, e, size)
                n++
                if (n % 6 == 0) postInvalidateOnAnimation()
            }
            postInvalidateOnAnimation()
        }.start()
    }

    // ---- drawing -----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat()
            if (scroll < 0f) scroll = 0f
            if (scroll > maxScroll) scroll = maxScroll
            postInvalidateOnAnimation()
        }

        val w = width.toFloat()
        val h = height.toFloat()

        val bg = Wall.src
        if (bg != null) {
            canvas.drawBitmap(bg, 0f, 0f, null)
        } else {
            plain.shader = null
            plain.color = if (deviceDark) Color.BLACK else Color.argb(255, 32, 34, 40)
            canvas.drawRect(0f, 0f, w, h, plain)
        }

        if (iconW <= 0f) return
        val blur = Wall.blur
        val dark = Wall.dark

        // the grid
        canvas.save()
        canvas.clipRect(0f, insetTop.toFloat(), w, gridBottom)
        var i = 0
        while (i < gridApps.size) {
            gridRect(i, tmp)
            if (tmp.bottom > insetTop - rowH && tmp.top < gridBottom + rowH) {
                drawApp(canvas, gridApps[i], tmp, blur, dark, GStore.labels)
            }
            i++
        }
        canvas.restore()

        // the dock
        Glass.draw(canvas, dockR, dockRad, blur, dark, true)
        i = 0
        while (i < dockApps.size) {
            dockRect(i, tmp)
            drawApp(canvas, dockApps[i], tmp, blur, dark, false)
            i++
        }

        drawMenu(canvas, blur, dark)
    }

    private fun drawApp(
        c: Canvas, e: AppEntry, r: RectF, blur: android.graphics.Bitmap?,
        dark: Boolean, withLabel: Boolean
    ) {
        val down = e === pressed && menu == null
        var box = r
        if (down) {
            val k = r.width() * 0.05f
            box = RectF(r.left + k, r.top + k, r.right - k, r.bottom - k)
        }

        Glass.draw(c, box, iconRad * (box.width() / r.width()), blur, dark, true)

        val g = e.glyph
        if (g != null) {
            val frac = if (e.mono) 0.60f else 0.74f
            val gw = box.width() * frac
            val cx = box.centerX()
            val cy = box.centerY()
            c.drawBitmap(
                g, null,
                RectF(cx - gw / 2f, cy - gw / 2f, cx + gw / 2f, cy + gw / 2f),
                glyphPaint
            )
        }

        if (withLabel) {
            val t = fit(label, e.label, iconW * 1.18f)
            c.drawText(t, r.centerX(), r.bottom + labelGap + label.textSize, label)
        }
    }

    private fun drawMenu(c: Canvas, blur: android.graphics.Bitmap?, dark: Boolean) {
        val items = menu ?: return
        Glass.draw(c, menuR, iconW * 0.17f, blur, dark, true)

        label.textAlign = Paint.Align.RIGHT
        val pad = iconW * 0.22f
        var i = 0
        while (i < items.size) {
            val top = menuR.top + i * menuRowH
            if (i == menuPress) {
                plain.shader = null
                plain.color = Color.argb(34, 255, 255, 255)
                val rr = RectF(menuR.left, top, menuR.right, top + menuRowH)
                c.drawRect(rr, plain)
            }
            if (i > 0) {
                plain.shader = null
                plain.color = Color.argb(26, 255, 255, 255)
                c.drawRect(menuR.left + pad, top, menuR.right - pad, top + 1f, plain)
            }
            val ts = iconW * 0.185f
            label.textSize = ts
            c.drawText(
                fit(label, items[i].text, menuR.width() - pad * 2f),
                menuR.right - pad, top + menuRowH / 2f + ts * 0.36f, label
            )
            i++
        }
        label.textAlign = Paint.Align.CENTER
        label.textSize = iconW * 0.175f
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

    // ---- the sheets --------------------------------------------------------

    private fun openAppMenu(e: AppEntry) {
        val inDock = dockApps.contains(e)
        val items = ArrayList<MItem>(3)
        items.add(MItem(if (inDock) "شيله من الشريط السفلي" else "ضيفه للشريط السفلي", 1))
        items.add(MItem("معلومات التطبيق", 2))
        items.add(MItem("إعدادات الشاشة", 8))
        menuApp = e
        showMenu(items)
    }

    private fun openWallMenu() {
        val items = ArrayList<MItem>(5)
        items.add(MItem("تغيير الخلفية", 3))
        if (GStore.ownWall) items.add(MItem("رجّع خلفية الجهاز", 4))
        items.add(MItem("درجة الزجاج: " + glassName(), 5))
        items.add(MItem(if (GStore.labels) "خفّي أسماء التطبيقات" else "ظهّر أسماء التطبيقات", 6))
        items.add(MItem("اختر الشاشة الرئيسية", 7))
        menuApp = null
        showMenu(items)
    }

    private fun glassName(): String = when (GStore.glass) {
        0 -> "خفيف"
        2 -> "قوي"
        else -> "متوسط"
    }

    private fun showMenu(items: List<MItem>) {
        pressed = null
        dragging = false
        removeCallbacks(longPress)
        menuRowH = iconW * 0.50f
        val mw = width * 0.70f
        val mh = items.size * menuRowH
        val left = (width - mw) / 2f
        var top = height * 0.5f - mh / 2f
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
            3 -> host?.pickWall()
            4 -> host?.forgetWall()
            5 -> {
                GStore.glass = (GStore.glass + 1) % 3
                invalidate()
            }
            6 -> {
                GStore.labels = !GStore.labels
                measureAll()
                invalidate()
            }
            7 -> host?.homeSettings()
            8 -> openWallMenu()
        }
    }

    // ---- touch -------------------------------------------------------------

    private fun hitRow(y: Float): Int {
        if (menuRowH <= 0f) return -1
        val i = ((y - menuR.top) / menuRowH).toInt()
        val items = menu ?: return -1
        return if (i in items.indices) i else -1
    }

    private fun findApp(x: Float, y: Float): AppEntry? {
        if (dockR.contains(x, y)) {
            var i = 0
            while (i < dockApps.size) {
                dockRect(i, tmp)
                if (grown(tmp).contains(x, y)) return dockApps[i]
                i++
            }
            return null
        }
        if (y < gridTop - rowH || y > gridBottom) return null
        var i = 0
        while (i < gridApps.size) {
            gridRect(i, tmp)
            if (grown(tmp).contains(x, y)) return gridApps[i]
            i++
        }
        return null
    }

    /** A finger is wider than an icon; take the label strip and the gaps too. */
    private fun grown(r: RectF): RectF {
        val gx = gapX * 0.45f
        return RectF(r.left - gx, r.top - iconW * 0.12f, r.right + gx, r.bottom + labelGap + labelH)
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
                    if (items != null && r >= 0 && r < items.size) {
                        act(items[r].act)
                    } else {
                        closeMenu()
                    }
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
                if (!dragging && (abs(y - downY) > slop || abs(x - downX) > slop)) {
                    dragging = true
                    pressed = null
                    removeCallbacks(longPress)
                    invalidate()
                }
                if (dragging && maxScroll > 0f) {
                    scroll += (downY - y)
                    downY = y
                    if (scroll < 0f) scroll = 0f
                    if (scroll > maxScroll) scroll = maxScroll
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val e = pressed
                if (dragging) {
                    vt?.addMovement(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    if (maxScroll > 0f && abs(vy) > 180f) {
                        scroller.fling(
                            0, scroll.toInt(), 0, (-vy).toInt(),
                            0, 0, 0, maxScroll.toInt()
                        )
                        postInvalidateOnAnimation()
                    }
                } else if (e != null) {
                    pressed = null
                    invalidate()
                    host?.openApp(e)
                }
                pressed = null
                dragging = false
                vt?.recycle()
                vt = null
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
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
