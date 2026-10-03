package com.huc.fasttype

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * The whole keyboard drawn on one Canvas. Key rectangles are measured once per layout
 * change, so a touch is a plain hit test and a press commits inside the same frame.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onChar(s: String)
        fun onDelete()
        fun onEnter()
        fun onShift()
        fun onLang()
        fun onPage(page: Int)
        fun onSuggestionTap()
    }

    var listener: Listener? = null

    var theme: KbTheme = Themes.all[0]
    var arabic = true
    var shift = 0
    var page = Pages.LETTERS
    var numRow = false
    var showSugg = true
    var suggText = ""

    private var keyH = 44f
    private var gap = 5f
    private var rad = 9f
    private var panelRad = 24f

    private val zonePad get() = dp(7f)
    private val panelPadX get() = dp(5f)
    private val panelPadTop get() = dp(7f)
    private val panelPadBottom get() = dp(8f)
    private val suggH get() = dp(30f)
    private var outerH = dp(40f)
    private var bottomPad = dp(10f)
    private var fastKeys = true
    private val catH get() = dp(30f)

    private var rows: List<List<Key>> = emptyList()
    private var emojiKeys: List<Key> = emptyList()
    private var pressed: Key? = null

    private var emojiTab = 0
    private var emojiScroll = 0f
    private var emojiMaxScroll = 0f
    private val catRects = ArrayList<RectF>()
    private var emojiTop = 0f
    private var emojiBottom = 0f
    private var emojiCell = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val icoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rf = RectF()
    private val path = Path()

    private val arFont: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val enFont: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    private val handler = Handler(Looper.getMainLooper())
    private var repeating = false
    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (!repeating) return
            listener?.onDelete()
            handler.postDelayed(this, 55)
        }
    }

    fun dp(v: Float) = v * resources.displayMetrics.density

    fun applySettings() {
        keyH = dp(Store.kbKeyHeight.toFloat())
        gap = dp(Store.kbGap.toFloat())
        rad = dp(Store.kbRadius.toFloat())
        panelRad = dp(Store.kbPanelRadius.toFloat())
        theme = Themes.byId(Store.kbTheme)
        numRow = Store.kbNumberRow
        showSugg = Store.kbSuggBar
        outerH = dp(Store.kbOuterH.toFloat())
        bottomPad = dp(Store.kbBottomPad.toFloat())
        fastKeys = Store.kbFast
        requestLayout()
        invalidate()
    }

    fun rebuild() {
        rows = if (page == Pages.EMOJI) listOf(KbLayout.emojiBottom(arabic))
        else KbLayout.rows(page, arabic, shift, numRow)
        if (page == Pages.EMOJI) buildEmoji()
        if (width > 0) measureKeys(width.toFloat())
        requestLayout()
        invalidate()
    }

    private fun buildEmoji() {
        val list = Emoji.sets.getOrElse(emojiTab) { Emoji.sets[0] }
        emojiKeys = list.map { Key(label = it, out = it) }
        emojiScroll = 0f
    }

    private fun rowCount(): Int = rows.size

    private fun contentHeight(): Float {
        var h = zonePad * 2 + panelPadTop + panelPadBottom
        if (showSugg && page != Pages.EMOJI) h += suggH + gap
        if (page == Pages.EMOJI) {
            h += catH + gap
            h += keyH * 4 + gap * 3
            h += gap
        }
        h += rowCount() * keyH + max(0, rowCount() - 1) * gap
        h += outerH + bottomPad
        return h
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, contentHeight().toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        measureKeys(width.toFloat())
    }

    private fun measureKeys(width: Float) {
        if (rows.isEmpty()) return
        val left = zonePad + panelPadX
        val right = width - zonePad - panelPadX
        val usable = right - left

        var y = zonePad + panelPadTop
        if (showSugg && page != Pages.EMOJI) y += suggH + gap

        if (page == Pages.EMOJI) {
            catRects.clear()
            val tabW = usable / Emoji.tabs.size
            for (i in Emoji.tabs.indices) {
                catRects.add(RectF(left + i * tabW, y, left + (i + 1) * tabW, y + catH))
            }
            y += catH + gap
            emojiTop = y
            emojiCell = usable / 8f
            val gridH = keyH * 4 + gap * 3
            emojiBottom = y + gridH
            val rowsNeeded = Math.ceil(emojiKeys.size / 8.0).toInt()
            emojiMaxScroll = max(0f, rowsNeeded * emojiCell - gridH)
            y = emojiBottom + gap
        }

        for (row in rows) {
            var totalWeight = 0f
            for (k in row) totalWeight += k.weight
            val unit = (usable - gap * (row.size - 1)) / totalWeight
            var x = left
            for (k in row) {
                k.x = x
                k.y = y
                k.w = unit * k.weight
                k.h = keyH
                x += k.w + gap
            }
            y += keyH + gap
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        bgPaint.color = theme.bg
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // panel
        val pTop = zonePad
        val pBottom = h - outerH - bottomPad - zonePad
        rf.set(zonePad, pTop, w - zonePad, pBottom)
        bgPaint.color = theme.panel
        canvas.drawRoundRect(rf, panelRad, panelRad, bgPaint)
        edgePaint.color = theme.panelEdge
        edgePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(rf, panelRad, panelRad, edgePaint)

        // suggestion strip
        if (showSugg && page != Pages.EMOJI) {
            rf.set(zonePad + panelPadX, zonePad + panelPadTop,
                w - zonePad - panelPadX, zonePad + panelPadTop + suggH)
            bgPaint.color = theme.sugg
            canvas.drawRoundRect(rf, dp(12f), dp(12f), bgPaint)
            txtPaint.typeface = arFont
            txtPaint.textSize = dp(13f)
            txtPaint.color = if (suggText.isEmpty()) theme.dim else theme.text
            val label = if (suggText.isEmpty()) "اكتب اختصار ليظهر هنا" else suggText
            canvas.drawText(label, rf.centerX(), rf.centerY() + dp(4.5f), txtPaint)
        }

        if (page == Pages.EMOJI) drawEmoji(canvas)

        for (row in rows) for (k in row) drawKey(canvas, k)

        if (outerH > 0f) drawOuterRow(canvas, w, h)
    }

    private fun drawEmoji(canvas: Canvas) {
        // category tabs
        txtPaint.textSize = dp(16f)
        for (i in catRects.indices) {
            val r = catRects[i]
            if (i == emojiTab) {
                bgPaint.color = theme.keyDark
                canvas.drawRoundRect(r, dp(7f), dp(7f), bgPaint)
            }
            txtPaint.color = theme.text
            txtPaint.alpha = if (i == emojiTab) 255 else 115
            canvas.drawText(Emoji.tabs[i], r.centerX(), r.centerY() + dp(6f), txtPaint)
        }
        txtPaint.alpha = 255

        canvas.save()
        canvas.clipRect(zonePad + panelPadX, emojiTop, width - zonePad - panelPadX, emojiBottom)
        txtPaint.textSize = emojiCell * 0.62f
        txtPaint.color = theme.text
        val left = zonePad + panelPadX
        for (i in emojiKeys.indices) {
            val col = i % 8
            val row = i / 8
            val cx = left + col * emojiCell + emojiCell / 2f
            val cy = emojiTop + row * emojiCell - emojiScroll + emojiCell / 2f
            if (cy < emojiTop - emojiCell || cy > emojiBottom + emojiCell) continue
            canvas.drawText(emojiKeys[i].label, cx, cy + emojiCell * 0.22f, txtPaint)
        }
        canvas.restore()
    }

    private fun drawKey(canvas: Canvas, k: Key) {
        rf.set(k.x, k.y, k.x + k.w, k.y + k.h)

        val isOn = k.code == Code.SHIFT && shift > 0
        keyPaint.color = when {
            k === pressed -> theme.keyDown
            isOn -> theme.onBg
            k.style == Style.GO -> theme.go
            k.style == Style.DARK -> theme.keyDark
            else -> theme.key
        }
        val r = if (k.style == Style.GO) rad * 2.2f else rad
        canvas.drawRoundRect(rf, r, r, keyPaint)

        val fg = when {
            isOn -> theme.onText
            k.style == Style.GO -> theme.goIcon
            else -> theme.text
        }

        if (k.icon != Ico.NONE) {
            icoPaint.color = fg
            icoPaint.strokeWidth = dp(1.8f)
            drawIcon(canvas, k.icon, rf.centerX(), rf.centerY(), keyH * 0.46f)
            return
        }

        if (k.code == Code.SPACE) {
            txtPaint.typeface = enFont
            txtPaint.textSize = keyH * 0.23f
            txtPaint.color = theme.dim
            txtPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(k.label, rf.right - dp(10f), rf.centerY() + dp(4f), txtPaint)
            txtPaint.textAlign = Paint.Align.CENTER
            return
        }

        txtPaint.typeface = if (k.arabic) arFont else enFont
        txtPaint.textSize = if (k.smallText) keyH * 0.33f else keyH * 0.46f
        txtPaint.color = fg
        val fm = txtPaint.fontMetrics
        val baseline = rf.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(k.label, rf.centerX(), baseline, txtPaint)
    }

    private fun drawIcon(canvas: Canvas, icon: Int, cx: Float, cy: Float, size: Float) {
        val s = size / 2f
        path.reset()
        when (icon) {
            Ico.SHIFT, Ico.CAPS -> {
                path.moveTo(cx, cy - s)
                path.lineTo(cx - s, cy)
                path.lineTo(cx - s * 0.45f, cy)
                path.lineTo(cx - s * 0.45f, cy + s * 0.75f)
                path.lineTo(cx + s * 0.45f, cy + s * 0.75f)
                path.lineTo(cx + s * 0.45f, cy)
                path.lineTo(cx + s, cy)
                path.close()
                fillPaint.color = icoPaint.color
                canvas.drawPath(path, fillPaint)
                if (icon == Ico.CAPS) {
                    canvas.drawLine(
                        cx - s * 0.45f, cy + s * 1.05f,
                        cx + s * 0.45f, cy + s * 1.05f, icoPaint
                    )
                }
            }
            Ico.DEL -> {
                path.moveTo(cx - s, cy)
                path.lineTo(cx - s * 0.35f, cy - s * 0.72f)
                path.lineTo(cx + s, cy - s * 0.72f)
                path.lineTo(cx + s, cy + s * 0.72f)
                path.lineTo(cx - s * 0.35f, cy + s * 0.72f)
                path.close()
                canvas.drawPath(path, icoPaint)
                val d = s * 0.3f
                canvas.drawLine(cx + s * 0.05f - d, cy - d, cx + s * 0.05f + d, cy + d, icoPaint)
                canvas.drawLine(cx + s * 0.05f + d, cy - d, cx + s * 0.05f - d, cy + d, icoPaint)
            }
            Ico.ENTER -> {
                path.moveTo(cx + s * 0.85f, cy - s * 0.7f)
                path.lineTo(cx + s * 0.85f, cy + s * 0.1f)
                path.lineTo(cx - s * 0.6f, cy + s * 0.1f)
                canvas.drawPath(path, icoPaint)
                path.reset()
                path.moveTo(cx - s * 0.1f, cy - s * 0.4f)
                path.lineTo(cx - s * 0.75f, cy + s * 0.1f)
                path.lineTo(cx - s * 0.1f, cy + s * 0.6f)
                canvas.drawPath(path, icoPaint)
            }
            Ico.SPACE -> {
                path.moveTo(cx - s, cy - s * 0.25f)
                path.lineTo(cx - s, cy + s * 0.25f)
                path.lineTo(cx + s, cy + s * 0.25f)
                path.lineTo(cx + s, cy - s * 0.25f)
                canvas.drawPath(path, icoPaint)
            }
            Ico.SMILE -> {
                canvas.drawCircle(cx, cy, s * 0.92f, icoPaint)
                canvas.drawPoint(cx - s * 0.33f, cy - s * 0.22f, icoPaint)
                canvas.drawPoint(cx + s * 0.33f, cy - s * 0.22f, icoPaint)
                path.reset()
                path.moveTo(cx - s * 0.4f, cy + s * 0.22f)
                path.quadTo(cx, cy + s * 0.68f, cx + s * 0.4f, cy + s * 0.22f)
                canvas.drawPath(path, icoPaint)
            }
        }
    }

    private fun drawOuterRow(canvas: Canvas, w: Float, h: Float) {
        icoPaint.color = theme.outer
        icoPaint.strokeWidth = dp(1.7f)
        val cy = h - bottomPad - outerH / 2f
        val s = dp(11f)

        // globe
        val gx = dp(28f)
        canvas.drawCircle(gx, cy, s, icoPaint)
        canvas.drawLine(gx - s, cy, gx + s, cy, icoPaint)
        path.reset()
        path.moveTo(gx, cy - s)
        path.quadTo(gx + s * 0.75f, cy, gx, cy + s)
        path.quadTo(gx - s * 0.75f, cy, gx, cy - s)
        canvas.drawPath(path, icoPaint)

        // mic
        val mx = w - dp(28f)
        rf.set(mx - s * 0.42f, cy - s, mx + s * 0.42f, cy + s * 0.15f)
        canvas.drawRoundRect(rf, s * 0.42f, s * 0.42f, icoPaint)
        path.reset()
        path.moveTo(mx - s * 0.78f, cy + s * 0.05f)
        path.quadTo(mx, cy + s * 1.15f, mx + s * 0.78f, cy + s * 0.05f)
        canvas.drawPath(path, icoPaint)
        canvas.drawLine(mx, cy + s * 0.75f, mx, cy + s * 1.05f, icoPaint)
    }

    // ---------------- touch ----------------

    private var downY = 0f
    private var scrollStart = 0f
    private var scrolling = false
    private var firedOnDown = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = y
                scrolling = false
                firedOnDown = false

                if (page == Pages.EMOJI && y >= emojiTop && y <= emojiBottom) {
                    scrollStart = emojiScroll
                    return true
                }
                if (page == Pages.EMOJI) {
                    for (i in catRects.indices) {
                        if (catRects[i].contains(x, y)) {
                            emojiTab = i; buildEmoji(); measureKeys(width.toFloat()); invalidate()
                            return true
                        }
                    }
                }
                if (y > height - outerH) return true

                val k = find(x, y) ?: return true
                pressed = k
                invalidateKey(k)
                if (k.code == Code.DEL) {
                    repeating = true
                    handler.postDelayed(repeatRunnable, 380)
                }
                if (fastKeys) { firedOnDown = true; fire(k) } else firedOnDown = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (page == Pages.EMOJI && !scrolling &&
                    Math.abs(y - downY) > dp(6f) && downY >= emojiTop && downY <= emojiBottom
                ) scrolling = true
                if (scrolling) {
                    emojiScroll = min(emojiMaxScroll, max(0f, scrollStart - (y - downY)))
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                stopRepeat()

                if (firedOnDown) {
                    val fk = pressed
                    pressed = null
                    firedOnDown = false
                    invalidateKey(fk)
                    return true
                }

                if (page == Pages.EMOJI && !scrolling && y >= emojiTop && y <= emojiBottom) {
                    val col = ((x - zonePad - panelPadX) / emojiCell).toInt()
                    val row = ((y + emojiScroll - emojiTop) / emojiCell).toInt()
                    val idx = row * 8 + col
                    if (col in 0..7 && idx >= 0 && idx < emojiKeys.size) {
                        listener?.onChar(emojiKeys[idx].label)
                    }
                    return true
                }

                if (y > height - outerH) {
                    if (x < width / 2f) listener?.onLang()
                    return true
                }

                if (showSugg && page != Pages.EMOJI &&
                    y < zonePad + panelPadTop + suggH && suggText.isNotEmpty()
                ) {
                    listener?.onSuggestionTap()
                    return true
                }

                val k = pressed
                pressed = null
                invalidateKey(k)
                if (!firedOnDown && k != null && k.hit(x, y)) fire(k)
                firedOnDown = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                stopRepeat(); val k = pressed; pressed = null
                firedOnDown = false; invalidateKey(k); return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun invalidateKey(k: Key?) {
        if (k == null) { invalidate(); return }
        invalidate(
            (k.x - 2f).toInt(), (k.y - 2f).toInt(),
            (k.x + k.w + 2f).toInt(), (k.y + k.h + 2f).toInt()
        )
    }

    private fun stopRepeat() {
        repeating = false
        handler.removeCallbacks(repeatRunnable)
    }

    private fun find(x: Float, y: Float): Key? {
        for (row in rows) for (k in row) if (k.hit(x, y)) return k
        return null
    }

    private fun fire(k: Key) {
        when (k.code) {
            Code.DEL -> listener?.onDelete()
            Code.ENTER -> listener?.onEnter()
            Code.SHIFT -> listener?.onShift()
            Code.LANG -> listener?.onLang()
            Code.TO_SYM -> listener?.onPage(Pages.SYM1)
            Code.TO_SYM2 -> listener?.onPage(Pages.SYM2)
            Code.TO_ABC -> listener?.onPage(Pages.LETTERS)
            Code.TO_EMOJI -> listener?.onPage(Pages.EMOJI)
            Code.TO_NPAD -> listener?.onPage(Pages.NPAD)
            else -> if (k.out.isNotEmpty()) listener?.onChar(k.out)
        }
    }
}
