package com.huc.fasttype

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
        fun onPredictionTap(index: Int)
        fun onMic()
        fun onClipTap()
        fun onClipHold()
        fun onClipPick(index: Int)
        fun onClipClose()
        fun onDeleteWord()
        fun onRepeatState(active: Boolean)
        /** 0 voice, 1 translate, 2 clipboard, 3 the app's own settings. */
        fun onTool(which: Int)
        fun onTransClose()
        fun onTransSwap()
        fun onTransLang(dst: Boolean)
        fun onLangPick(code: String)
        /** One of the recent pictures was chosen from the clipboard page. */
        fun onPicPick(index: Int)
    }

    var listener: Listener? = null

    var theme: KbTheme = Themes.all[0]
    var arabic = true
    var shift = 0
    var page = Pages.LETTERS
    var numRow = false
    var showSugg = true
    var suggText = ""

    /** Everything the dictionary offered, in order. The row scrolls through them. */
    var suggs: List<String> = emptyList()
        set(v) { field = v; suggScroll = 0f; suggMeasuredFor = null }

    /** Voice typing is running — the mic is drawn filled and the keys recede. */
    var listening = false
        set(v) {
            if (field == v) return
            field = v
            if (v) { pulse = 0f; handler.post(pulseRunnable) }
            else handler.removeCallbacks(pulseRunnable)
            invalidate()
        }

    /** Microphone loudness while listening, smoothed into the level meter. */
    var level = 0f
        set(v) {
            field = v
            smoothLevel += ((v.coerceIn(-2f, 10f) + 2f) / 12f - smoothLevel) * 0.35f
        }

    private var smoothLevel = 0f
    private var pulse = 0f

    /** Drives the ring around the mic while a session is open. */
    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!listening) return
            pulse += 0.045f
            if (pulse > 1f) pulse -= 1f
            invalidateStrip()
            handler.postDelayed(this, 33)
        }
    }

    /** Repaints the suggestion strip alone — the keys have not changed. */
    fun stripChanged() = invalidateStrip()

    /**
     * Same keys, different labels.
     *
     * Shift only swaps the letters drawn on the caps; every rect stays exactly where
     * it was. Going through rebuild() would ask the whole window to measure and lay
     * out again for nothing, and that is felt as a stutter at speed.
     */
    fun relabel() {
        rows = if (page == Pages.EMOJI) listOf(KbLayout.emojiBottom(arabic))
        else KbLayout.rows(if (page == Pages.LANGS) Pages.CLIP else page, arabic, shift, numRow)
        if (width > 0) measureKeys(width.toFloat())
        invalidate()
    }

    private fun invalidateStrip() {
        val top = (zonePad + panelPadTop).toInt()
        invalidate(0, top - 2, width, (top + suggH).toInt() + 2)
    }

    /**
     * The strip is showing a status line rather than suggestions. It is drawn across
     * the whole width and tapping it does nothing — a message must never be typed.
     */
    var statusOnly = false

    private var keyH = 44f
    private var gap = 5f
    private var rad = 9f
    private var panelRad = 24f

    private var zonePad = dp(0f)
    /** Ratios measured off a real iOS keyboard, relative to key height. */
    private val sideMargin get() = keyH * 0.164f
    private val vGap get() = keyH * 0.265f
    private val panelPadTop get() = keyH * 0.164f
    private val panelPadBottom get() = keyH * 0.164f
    private var suggH = dp(30f)
    /** The strip is also the language bar, so translate mode keeps it even if off. */
    private val stripVisible get() = (showSugg || transOn) && page != Pages.EMOJI
    /** Height of the translate box row. */
    private val transH get() = suggH * 1.18f
    private var suggRad = dp(12f)
    private var hairOn = true
    private var hairH = 0.54f
    private var hairW = 1f
    private var micInStrip = true
    private var letterScale = 0.49f
    private var pressFx = true
    private var blankOnHold = true
    private var clearBottom = false
    private var pressedZone = -1
    private var pressedSugg = -1

    /** The icon bar that slides in over the suggestions, 0 shut, 1 fully open. */
    private var toolsOpen = false
    private var toolsT = 0f
    private val toolRects = ArrayList<RectF>(4)

    /** Translate mode: the language bar replaces the strip and a box opens below it. */
    var transOn = false
        private set
    var transText: String = ""
    var transStatus: String = ""
    private val trSrcRect = RectF()
    private val trSwapRect = RectF()
    private val trDstRect = RectF()
    private val trCloseRect = RectF()
    private val trBoxRect = RectF()
    private var langForDst = true
    private var langScroll = 0f
    private var langMaxScroll = 0f
    private var langPressed = -1
    private var langListTop = 0f
    private var langListBottom = 0f
    private var langScrolling = false
    private var langDownY = 0f
    private var langScroll0 = 0f
    private val langDoneRect = RectF()

    /** True for each suggestion that is a new word rather than a completion. */
    var suggNew: List<Boolean> = emptyList()

    private val suggW = ArrayList<Float>(16)
    private var suggMeasuredFor: List<String>? = null
    private var suggAvail = -1f
    private var suggTotal = 0f
    private var suggMax = 0f
    private var suggScroll = 0f
    private var suggDragging = false
    private var clipOn = true

    /** The long-press bubble: its options, where it sits, and which one is picked. */
    private var altKey: Key? = null
    private var altList: List<String> = emptyList()
    private var altSel = 0
    private var altArmed = false
    private val altRect = RectF()
    private var altCellW = 0f
    private var altsOn = true

    private val altRunnable = Runnable {
        val k = pressed ?: return@Runnable
        if (!altArmed) return@Runnable
        openAlts(k)
    }
    private val clipHeadH get() = keyH * 0.9f
    private val clipRowH get() = keyH * 1.18f
    private var clipScroll = 0f
    private var clipMaxScroll = 0f
    private var clipListTop = 0f
    private var clipListBottom = 0f
    private var clipPressed = -1
    private var clipDownY = 0f
    private var clipScroll0 = 0f
    private var clipScrolling = false
    private val clipDoneRect = RectF()
    private var stripDownX = 0f
    private var stripScroll0 = 0f
    private var clipArmed = false
    private var clipFired = false

    private val clipHoldRunnable = Runnable {
        if (!clipArmed) return@Runnable
        clipArmed = false
        clipFired = true
        performHapticFeedback(
            android.view.HapticFeedbackConstants.LONG_PRESS,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        listener?.onClipHold()
    }

    /**
     * Blank mode. A long press on the space bar hides every label, exactly like the
     * iPhone's trackpad mode looks. It stays on until the next key press, so the
     * keyboard can actually be photographed this way.
     */
    private var blank = false
    private var blankArmed = false

    private val blankRunnable = Runnable {
        if (!blankArmed) return@Runnable
        blank = true
        blankArmed = false
        // the space that fast-key typing already sent has to go back
        listener?.onDelete()
        performHapticFeedback(
            android.view.HapticFeedbackConstants.LONG_PRESS,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        invalidate()
    }
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
    /** Recording red, the one colour that reads the same in every theme. */
    private val REC = 0xFFD93025.toInt()

    /** Voice, translate, clipboard, settings — the order they sit in the bar. */
    private val TOOL_ICONS = intArrayOf(Ico.MIC, Ico.TRANS, Ico.CLIP, Ico.COG)

    private val toolsRunnable = object : Runnable {
        override fun run() {
            val target = if (toolsOpen) 1f else 0f
            toolsT += (target - toolsT) * 0.34f
            if (Math.abs(target - toolsT) < 0.02f) {
                toolsT = target
            } else {
                handler.postDelayed(this, 16)
            }
            invalidateStrip()
        }
    }

    /** Opens or shuts the icon bar that rides over the suggestions. */
    fun setToolsOpen(open: Boolean) {
        if (toolsOpen == open) return
        toolsOpen = open
        handler.removeCallbacks(toolsRunnable)
        handler.post(toolsRunnable)
    }

    fun toolsAreOpen(): Boolean = toolsOpen

    /** Switches the strip into the language bar and opens the box underneath. */
    fun setTranslate(on: Boolean) {
        if (transOn == on) return
        transOn = on
        transText = ""
        transStatus = ""
        setToolsOpen(false)
        toolsT = 0f
        requestLayout()
        invalidate()
    }

    fun setTransText(text: String, status: String) {
        if (text == transText && status == transStatus) return
        transText = text
        transStatus = status
        // repainting forty keys on every letter is what made the box feel heavy
        if (trBoxRect.width() > 1f) {
            invalidate(
                (trBoxRect.left - 2f).toInt(), (trBoxRect.top - 2f).toInt(),
                (trBoxRect.right + 2f).toInt(), (trBoxRect.bottom + 2f).toInt()
            )
        } else {
            invalidate()
        }
    }

    fun openLangs(dst: Boolean) {
        langForDst = dst
        langScroll = 0f
        langPressed = -1
        page = Pages.LANGS
        rebuild()
    }

    private val rf = RectF()
    private val picPath = Path()
    private val icoBmp = Paint(Paint.ANTI_ALIAS_FLAG).also { it.isFilterBitmap = true }
    /** Thumbnails for the clipboard page, and where each one was drawn. */
    var picShelf: List<Bitmap> = emptyList()
    private val shelfRects = ArrayList<RectF>(8)
    /** Reused for the dirty rectangle, so onDraw allocates nothing. */
    private val clipR = Rect()
    private val path = Path()

    private val arFont: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val enFont: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    private val handler = Handler(Looper.getMainLooper())
    private var repeating = false
    private var repeatTicks = 0

    /**
     * Backspace repeat. It starts quickly, speeds up as it goes, and after about a
     * second and a half switches to whole words, so clearing a line never crawls.
     */
    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (!repeating) return
            repeatTicks++
            val delay = when {
                repeatTicks > 44 -> { listener?.onDeleteWord(); 70L }
                repeatTicks > 16 -> { listener?.onDelete(); 16L }
                else -> { listener?.onDelete(); 28L }
            }
            handler.postDelayed(this, delay)
        }
    }

    fun dp(v: Float) = v * resources.displayMetrics.density

    /** Whether the phone itself is in dark mode right now. */
    private fun deviceIsDark(): Boolean =
        (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun applySettings() {
        keyH = dp(Store.kbKeyHeight.toFloat())
        gap = dp(Store.kbGap.toFloat())
        rad = dp(Store.kbRadius.toFloat())
        panelRad = dp(Store.kbPanelRadius.toFloat())
        theme = Themes.resolve(Store.kbTheme, Store.kbFollowSystem, deviceIsDark())
        numRow = Store.kbNumberRow
        showSugg = Store.kbSuggBar
        zonePad = dp(Store.kbInset.toFloat())
        // the language key lives in the last row now, so the old bottom strip with
        // its globe and mic must never come back, whatever that slider says
        outerH = if (Store.kbGlobeRow) 0f else dp(Store.kbOuterH.toFloat())
        bottomPad = dp(Store.kbBottomPad.toFloat())
        fastKeys = Store.kbFast
        suggH = dp(Store.kbSuggH.toFloat())
        suggRad = dp(Store.kbSuggRad.toFloat())
        hairOn = Store.kbHair
        hairH = Store.kbHairH / 100f
        hairW = Store.kbHairW.toFloat()
        micInStrip = Store.kbMicStrip
        clipOn = Store.kbClip
        altsOn = Store.kbAlts
        letterScale = Store.kbLetter / 100f
        pressFx = Store.kbPressFx
        blankOnHold = Store.kbBlankHold
        clearBottom = Store.kbClearBottom
        KbLayout.globeInRow = Store.kbGlobeRow
        requestLayout()
        invalidate()
    }

    fun rebuild() {
        if (page == Pages.CLIP) clipScroll = 0f
        if (page == Pages.LANGS) langScroll = 0f
        val forRows = if (page == Pages.LANGS) Pages.CLIP else page
        rows = if (page == Pages.EMOJI) listOf(KbLayout.emojiBottom(arabic))
        else KbLayout.rows(forRows, arabic, shift, numRow)
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
        // The clipboard page has no key rows of its own, so it borrows the height of
        // the letter keyboard — otherwise it collapses to nothing and the list has
        // nowhere to appear.
        if (page == Pages.CLIP || page == Pages.LANGS) {
            return zonePad * 2 + panelPadTop + panelPadBottom +
                suggH + vGap + keyH * 4 + vGap * 3 + outerH + bottomPad
        }
        var h = zonePad * 2 + panelPadTop + panelPadBottom
        if (stripVisible) h += suggH + vGap
        if (transOn && page != Pages.EMOJI) h += transH + vGap
        if (page == Pages.EMOJI) {
            h += catH + gap
            h += keyH * 4 + gap * 3
            h += vGap
        }
        h += rowCount() * keyH + max(0, rowCount() - 1) * vGap
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
        val left = zonePad + sideMargin
        val right = width - zonePad - sideMargin
        val usable = right - left

        var y = zonePad + panelPadTop
        if (stripVisible) y += suggH + vGap
        if (transOn && page != Pages.EMOJI) y += transH + vGap

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
            y = emojiBottom + vGap
        }

        val stripTop = contentHeight() - bottomPad - outerH
        val stripBottom = contentHeight() - bottomPad
        globeRect.set(0f, stripTop, width * 0.4f, stripBottom)
        micRect.set(width * 0.6f, stripTop, width.toFloat(), stripBottom)

        for (row in rows) {
            var totalWeight = 0f
            var totalGap = 0f
            for ((i, k) in row.withIndex()) {
                totalWeight += k.weight
                if (i < row.size - 1) totalGap += gap * k.gapAfter
            }
            val unit = (usable - totalGap) / totalWeight
            var x = left
            for ((i, k) in row.withIndex()) {
                k.x = x
                k.y = y
                k.w = unit * k.weight
                k.h = keyH
                x += k.w + if (i < row.size - 1) gap * k.gapAfter else 0f
            }
            y += keyH + vGap
        }

        growTouchRects(width)
    }

    /**
     * Stretches every key's touch rectangle halfway into the gaps around it, and the
     * outermost keys all the way to the panel edges. The drawn keys do not move, but a
     * light tap anywhere on the panel now lands on a key instead of a dead gap.
     */
    private fun growTouchRects(width: Float) {
        val halfGap = gap * 0.5f
        val halfV = vGap * 0.5f
        val panelLeft = zonePad
        val panelRight = width - zonePad

        for ((ri, row) in rows.withIndex()) {
            // spacers reserve width but never catch a touch; their area goes to the
            // key beside them, so the row still tiles edge to edge
            val live = row.filter { !it.spacer }
            if (live.isEmpty()) continue
            val first = ri == 0
            val last = ri == rows.size - 1
            for ((ki, k) in live.withIndex()) {
                val padL = if (ki == 0) k.x - panelLeft
                else (k.x - (live[ki - 1].x + live[ki - 1].w)) * 0.5f
                val padR = if (ki == live.size - 1) panelRight - (k.x + k.w)
                else (live[ki + 1].x - (k.x + k.w)) * 0.5f
                k.tx = k.x - max(0f, padL)
                k.tw = k.w + max(0f, padL) + max(0f, padR)
                // never reach up into the suggestion strip, nor down into the globe strip
                k.ty = k.y - if (first) min(halfV, panelPadTop) else halfV
                k.th = k.h + (k.y - k.ty) + if (last) min(halfV, panelPadBottom) else halfV
            }
            for (k in row) if (k.spacer) { k.tx = 0f; k.ty = 0f; k.tw = 0f; k.th = 0f }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Nothing is painted outside the panel, so the rounded top corners show the
        // app behind them — the same way the iOS keyboard does it.
        val pTop = zonePad
        val pBottom = h - bottomPad - zonePad
        rf.set(zonePad, pTop, w - zonePad, pBottom)
        bgPaint.color = theme.panel
        canvas.drawRoundRect(rf, panelRad, panelRad, bgPaint)
        if (panelRad > 0f) {
            // square off the bottom; only the top two corners are rounded
            canvas.drawRect(rf.left, pTop + panelRad, rf.right, pBottom, bgPaint)
        }
        if (zonePad > 0.5f) {
            edgePaint.color = theme.panelEdge
            edgePaint.strokeWidth = dp(1f)
            canvas.drawRoundRect(rf, panelRad, panelRad, edgePaint)
        }
        if (bottomPad > 0f && !clearBottom) {
            bgPaint.color = theme.panel
            canvas.drawRect(zonePad, pBottom, w - zonePad, h, bgPaint)
        }

        if (page == Pages.CLIP) { drawClipPage(canvas, w, h); return }
        if (page == Pages.LANGS) { drawLangPage(canvas, w, h); return }
        if (transOn) {
            drawTransBar(canvas, w)
            drawTransBox(canvas, w)
        } else if (stripVisible && !blank) {
            drawStrip(canvas, w)
        }

        if (page == Pages.EMOJI) drawEmoji(canvas)

        // One key pressed means one key repainted. Without this every tap walked all
        // forty keys through drawKey and let the clip throw most of the work away.
        val haveClip = canvas.getClipBounds(clipR)
        if (listening) {
            // the keys step back so the strip is clearly where the action is
            canvas.saveLayerAlpha(0f, 0f, w, h, 120)
            for (row in rows) for (k in row) if (!k.spacer) drawKey(canvas, k)
            canvas.restore()
        } else {
            for (row in rows) for (k in row) {
                if (k.spacer) continue
                if (haveClip && (k.x + k.w < clipR.left || k.x > clipR.right ||
                        k.y + k.h < clipR.top || k.y > clipR.bottom)
                ) continue
                drawKey(canvas, k)
            }
        }

        if (outerH > 0f) drawOuterRow(canvas, w, h)

        drawAlts(canvas)
    }

    /**
     * The prediction strip. Measured off a real iOS screenshot: flat, no box, the
     * same colour as the panel, split into three equal zones by two hairlines that
     * sit at a third and two thirds of the FULL panel width — not of the key area —
     * and run 54% of the strip height, centred.
     */
    /**
     * The prediction strip.
     *
     * A scrolling row of as many guesses as the dictionary has, with the clipboard
     * key and the microphone sitting at either end as a matched pair — same size,
     * same weight, nothing boxed around either of them.
     */
    private fun drawStrip(canvas: Canvas, w: Float) {
        val top = zonePad + panelPadTop
        val bottom = top + suggH
        val cy = (top + bottom) / 2f
        val left = zonePad
        val right = w - zonePad

        val clipL = left + micEdge
        val micC = right - micEdge - micW / 2f
        val clipC = clipL + micW / 2f
        val zoneLeft = clipL + micW + dp(2f)
        val zoneRight = right - micEdge - micW - dp(2f)

        // ---- the suggestions, clipped to the space between the two keys ----
        canvas.save()
        canvas.clipRect(zoneLeft, top, zoneRight, bottom)
        txtPaint.typeface = arFont
        txtPaint.textSize = keyH * 0.33f
        val fm = txtPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f

        measureSuggs(zoneRight - zoneLeft)
        var x = zoneLeft - suggScroll
        for (i in suggs.indices) {
            val wItem = suggW.getOrNull(i) ?: continue
            if (x + wItem > zoneLeft - dp(40f) && x < zoneRight + dp(40f)) {
                if (i == pressedSugg) {
                    rf.set(x + dp(2f), top + suggH * 0.13f,
                        x + wItem - dp(2f), bottom - suggH * 0.13f)
                    bgPaint.color = theme.keyDown
                    canvas.drawRoundRect(rf, suggRad.coerceAtLeast(dp(6f)),
                        suggRad.coerceAtLeast(dp(6f)), bgPaint)
                }
                if (hairOn && i > 0) {
                    edgePaint.style = Paint.Style.STROKE
                    edgePaint.color = Themes.hairline(theme)
                    edgePaint.strokeWidth = dp(hairW)
                    val hh = suggH * hairH
                    canvas.drawLine(x, cy - hh / 2f, x, cy + hh / 2f, edgePaint)
                }
                // the first guess is the likely one; a whole new word is tinted
                txtPaint.color = when {
                    suggNew.getOrNull(i) == true -> theme.go
                    i == 0 -> theme.text
                    else -> theme.dim
                }
                canvas.drawText(suggs[i], x + wItem / 2f, baseline, txtPaint)
            }
            x += wItem
        }
        canvas.restore()

        // ---- the two end keys ----
        if (clipOn) {
            if (pressedZone == -3) {
                bgPaint.color = theme.keyDown
                canvas.drawCircle(clipC, cy, micW * 0.46f, bgPaint)
            }
            val pic = Pics.readyThumb
            if (pic != null && !pic.isRecycled) {
                // a picture waiting to be sent shows itself, so there is nothing to
                // read and nothing to remember — he just sees it is there
                val r = suggH * 0.36f
                rf.set(clipC - r, cy - r, clipC + r, cy + r)
                picPath.reset()
                picPath.addRoundRect(rf, r * 0.32f, r * 0.32f, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(picPath)
                canvas.drawBitmap(pic, null, rf, icoBmp)
                canvas.restore()
                edgePaint.style = Paint.Style.STROKE
                edgePaint.color = theme.go
                edgePaint.strokeWidth = dp(1.6f)
                canvas.drawRoundRect(rf, r * 0.32f, r * 0.32f, edgePaint)
            } else {
                icoPaint.color = if (Clip.fresh) theme.go else theme.outer
                icoPaint.strokeWidth = dp(1.7f)
                drawIcon(canvas, Ico.CLIP, clipC, cy, suggH * 0.46f)
            }
        }

        // ---- the icon bar, sliding in over the suggestions ----
        if (toolsT > 0.001f) {
            canvas.save()
            canvas.clipRect(zoneLeft, top, zoneRight, bottom)
            bgPaint.color = theme.panel
            bgPaint.alpha = (255 * toolsT).toInt()
            canvas.drawRect(zoneLeft, top, zoneRight, bottom, bgPaint)
            bgPaint.alpha = 255
            layoutTools(zoneLeft, zoneRight, top, bottom)
            val slide = (1f - toolsT) * (zoneRight - zoneLeft)
            for (i in toolRects.indices) {
                val r = toolRects[i]
                val tcx = r.centerX() + slide
                if (pressedZone == -(4 + i)) {
                    bgPaint.color = theme.keyDown
                    canvas.drawCircle(tcx, cy, micW * 0.46f, bgPaint)
                }
                val rec = i == 0 && listening
                if (rec) {
                    bgPaint.color = REC
                    canvas.drawCircle(tcx, cy, micW * 0.46f, bgPaint)
                }
                icoPaint.color = if (rec) 0xFFFFFFFF.toInt() else theme.outer
                icoPaint.alpha = (255 * toolsT).toInt()
                icoPaint.strokeWidth = dp(1.7f)
                drawIcon(canvas, TOOL_ICONS[i], tcx, cy, suggH * 0.44f)
                icoPaint.alpha = 255
            }
            canvas.restore()
        }

        // ---- the end key: a gear where the microphone used to be ----
        if (micInStrip) {
            val r = micW * 0.46f
            if (listening && !toolsOpen) {
                edgePaint.style = Paint.Style.STROKE
                edgePaint.color = REC
                edgePaint.strokeWidth = dp(2f)
                edgePaint.alpha = ((1f - pulse) * 190f).toInt()
                canvas.drawCircle(micC, cy, r + dp(4f) + pulse * dp(7f), edgePaint)
                edgePaint.alpha = 255
                drawLevel(canvas, micC - micW * 0.8f, cy, suggH)
            }
            if (pressedZone == -2 || toolsOpen) {
                bgPaint.color = theme.keyDown
                canvas.drawCircle(micC, cy, r, bgPaint)
            }
            icoPaint.color = if (listening) REC else theme.outer
            icoPaint.strokeWidth = dp(1.7f)
            canvas.save()
            canvas.rotate(toolsT * 90f, micC, cy)
            drawIcon(canvas, Ico.GEAR, micC, cy, suggH * 0.44f)
            canvas.restore()
        }
    }

    /** Four evenly spaced circles at the end of the strip nearest the gear. */
    private fun layoutTools(zoneLeft: Float, zoneRight: Float, top: Float, bottom: Float) {
        toolRects.clear()
        val step = micW + dp(3f)
        for (i in 0 until 4) {
            val cx = zoneRight - micW / 2f - i * step
            toolRects.add(RectF(cx - micW / 2f, top, cx + micW / 2f, bottom))
        }
    }

    /** The language bar that stands in for the strip while translating. */
    private fun drawTransBar(canvas: Canvas, w: Float) {
        val top = zonePad + panelPadTop
        val bottom = top + suggH
        val cy = (top + bottom) / 2f
        val left = zonePad + sideMargin
        val right = w - zonePad - sideMargin

        val closeW = suggH * 0.92f
        val swapW = suggH * 0.86f
        val chipH = suggH * 0.80f
        val chipY = cy - chipH / 2f
        val chipW = (right - left - closeW - swapW - dp(16f)) / 2f

        trCloseRect.set(right - closeW, cy - closeW / 2f, right, cy + closeW / 2f)
        trDstRect.set(trCloseRect.left - dp(6f) - chipW, chipY, trCloseRect.left - dp(6f), chipY + chipH)
        trSwapRect.set(trDstRect.left - swapW, cy - swapW / 2f, trDstRect.left, cy + swapW / 2f)
        trSrcRect.set(trSwapRect.left - chipW, chipY, trSwapRect.left, chipY + chipH)

        txtPaint.typeface = arFont
        txtPaint.textSize = suggH * 0.30f
        val fm = txtPaint.fontMetrics
        val base = cy - (fm.ascent + fm.descent) / 2f

        drawChip(canvas, trSrcRect, Tr.nameOf(Store.kbTrSrc), pressedZone == -10, base)
        drawChip(canvas, trDstRect, Tr.nameOf(Store.kbTrDst), pressedZone == -12, base)

        if (pressedZone == -11) {
            bgPaint.color = theme.keyDown
            canvas.drawCircle(trSwapRect.centerX(), cy, swapW * 0.46f, bgPaint)
        }
        icoPaint.color = theme.outer
        icoPaint.strokeWidth = dp(1.7f)
        drawIcon(canvas, Ico.SWAP, trSwapRect.centerX(), cy, suggH * 0.36f)

        bgPaint.color = if (pressedZone == -13) theme.keyDown else theme.key
        canvas.drawCircle(trCloseRect.centerX(), cy, closeW * 0.46f, bgPaint)
        icoPaint.color = theme.outer
        drawIcon(canvas, Ico.BACK, trCloseRect.centerX(), cy, suggH * 0.34f)
    }

    private fun drawChip(canvas: Canvas, r: RectF, text: String, down: Boolean, base: Float) {
        bgPaint.color = if (down) theme.keyDown else theme.key
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, bgPaint)
        txtPaint.color = theme.text
        canvas.drawText(ellipsize(text, r.width() - dp(14f)), r.centerX(), base, txtPaint)
    }

    /** The box he types into while translating. */
    private fun drawTransBox(canvas: Canvas, w: Float) {
        val left = zonePad + sideMargin
        val right = w - zonePad - sideMargin
        val top = zonePad + panelPadTop + suggH + vGap
        trBoxRect.set(left, top, right, top + transH)

        bgPaint.color = theme.key
        canvas.drawRoundRect(trBoxRect, transH / 2f, transH / 2f, bgPaint)
        edgePaint.style = Paint.Style.STROKE
        edgePaint.color = theme.go
        edgePaint.strokeWidth = dp(1.6f)
        rf.set(
            trBoxRect.left + dp(0.8f), trBoxRect.top + dp(0.8f),
            trBoxRect.right - dp(0.8f), trBoxRect.bottom - dp(0.8f)
        )
        canvas.drawRoundRect(rf, transH / 2f, transH / 2f, edgePaint)

        txtPaint.typeface = arFont
        txtPaint.textSize = keyH * 0.33f
        val fm = txtPaint.fontMetrics
        val base = trBoxRect.centerY() - (fm.ascent + fm.descent) / 2f
        val pad = dp(16f)

        val shown = when {
            transText.isNotEmpty() -> transText
            transStatus.isNotEmpty() -> transStatus
            else -> "اكتب هنا والترجمة تطلع بالرسالة"
        }
        txtPaint.color = if (transText.isNotEmpty()) theme.text else theme.dim
        // the tail is what matters while typing, so a long line scrolls from the end
        canvas.drawText(tailFit(shown, right - left - pad * 2f), (left + right) / 2f, base, txtPaint)

        if (transText.isNotEmpty() && transStatus.isNotEmpty()) {
            txtPaint.textSize = keyH * 0.22f
            txtPaint.color = theme.dim
            canvas.drawText(transStatus, (left + right) / 2f, trBoxRect.bottom - dp(3f), txtPaint)
        }
    }

    /** Keeps the end of a string, which is where the cursor is. */
    private fun tailFit(s: String, maxW: Float): String {
        if (txtPaint.measureText(s) <= maxW) return s
        var i = 0
        while (i < s.length - 1 && txtPaint.measureText("…" + s.substring(i)) > maxW) i++
        return "…" + s.substring(i)
    }

    /** Widths of each suggestion, and how far the row can scroll. */
    private fun measureSuggs(available: Float) {
        if (suggs === suggMeasuredFor && available == suggAvail) return
        suggMeasuredFor = suggs
        suggAvail = available
        suggW.clear()
        txtPaint.typeface = arFont
        txtPaint.textSize = keyH * 0.33f
        var total = 0f
        val pad = dp(18f)
        for (sText in suggs) {
            val wItem = txtPaint.measureText(sText) + pad * 2f
            suggW.add(wItem)
            total += wItem
        }
        suggTotal = total
        suggMax = max(0f, total - available)
        suggScroll = suggScroll.coerceIn(0f, suggMax)
    }

    /** Which suggestion sits under this x, or -1. */
    private fun suggAt(x: Float, w: Float): Int {
        val clipL = zonePad + micEdge
        val zoneLeft = clipL + micW + dp(2f)
        var cur = zoneLeft - suggScroll
        for (i in suggs.indices) {
            val wItem = suggW.getOrNull(i) ?: return -1
            if (x >= cur && x < cur + wItem) return i
            cur += wItem
        }
        return -1
    }

    /** Rows of what was copied lately, newest first. */
    private fun drawClipPage(canvas: Canvas, w: Float, h: Float) {
        val left = zonePad + sideMargin
        val right = w - zonePad - sideMargin
        val top = zonePad + panelPadTop

        // header: a title and a way out
        txtPaint.typeface = arFont
        txtPaint.textSize = keyH * 0.33f
        txtPaint.color = theme.dim
        val fmH = txtPaint.fontMetrics
        val hCy = top + clipHeadH / 2f
        canvas.drawText("الحافظة", left + dp(40f), hCy - (fmH.ascent + fmH.descent) / 2f, txtPaint)
        txtPaint.color = theme.go
        canvas.drawText("تم", right - dp(22f), hCy - (fmH.ascent + fmH.descent) / 2f, txtPaint)
        clipDoneRect.set(right - dp(56f), top, right, top + clipHeadH)

        var listTop = top + clipHeadH
        val listBottom = h - bottomPad - zonePad - panelPadBottom

        // the pictures ride above the copied text, as one scrolling row
        shelfRects.clear()
        val shelf = picShelf
        if (shelf.isNotEmpty()) {
            val thH = clipRowH * 1.5f
            val thW = thH * 0.62f
            var x = left
            canvas.save()
            canvas.clipRect(left, listTop, right, listTop + thH)
            for (bm in shelf) {
                if (bm.isRecycled) continue
                rf.set(x, listTop + dp(2f), x + thW, listTop + thH - dp(6f))
                shelfRects.add(RectF(rf))
                picPath.reset()
                picPath.addRoundRect(rf, dp(8f), dp(8f), Path.Direction.CW)
                canvas.save()
                canvas.clipPath(picPath)
                canvas.drawBitmap(bm, null, rf, icoBmp)
                canvas.restore()
                edgePaint.style = Paint.Style.STROKE
                edgePaint.color = Themes.hairline(theme)
                edgePaint.strokeWidth = dp(1f)
                canvas.drawRoundRect(rf, dp(8f), dp(8f), edgePaint)
                x += thW + dp(6f)
                if (x > right) break
            }
            canvas.restore()
            listTop += thH
        }

        clipListTop = listTop
        clipListBottom = listBottom

        val items = Clip.all
        val visible = max(clipRowH, listBottom - listTop)
        clipMaxScroll = max(0f, items.size * clipRowH - visible)
        clipScroll = clipScroll.coerceIn(0f, clipMaxScroll)

        if (items.isEmpty()) {
            if (shelf.isEmpty()) {
                txtPaint.color = theme.dim
                canvas.drawText("ماكو شي منسوخ بعد",
                    (left + right) / 2f, (listTop + listBottom) / 2f, txtPaint)
            }
            return
        }

        canvas.save()
        canvas.clipRect(left, listTop, right, listBottom)
        for (i in items.indices) {
            val y = listTop + i * clipRowH - clipScroll
            if (y > listBottom || y + clipRowH < listTop) continue
            rf.set(left, y + dp(3f), right, y + clipRowH - dp(3f))
            bgPaint.color = if (i == clipPressed) theme.keyDown else theme.key
            canvas.drawRoundRect(rf, rad * 1.6f, rad * 1.6f, bgPaint)

            val e = items[i]
            txtPaint.textSize = keyH * 0.31f
            txtPaint.color = theme.text
            val fm = txtPaint.fontMetrics
            val oneLine = e.text.replace('\n', ' ').trim()
            canvas.drawText(
                ellipsize(oneLine, right - left - dp(78f)),
                (left + right) / 2f, y + clipRowH * 0.42f - (fm.ascent + fm.descent) / 2f,
                txtPaint
            )
            txtPaint.textSize = keyH * 0.24f
            txtPaint.color = theme.dim
            canvas.drawText(
                if (e.pinned) "مثبّت · " + Clip.ago(e.at) else Clip.ago(e.at),
                (left + right) / 2f, y + clipRowH * 0.76f, txtPaint
            )
        }
        canvas.restore()
    }

    /** The list of languages, for whichever side of the bar was tapped. */
    private fun drawLangPage(canvas: Canvas, w: Float, h: Float) {
        val left = zonePad + sideMargin
        val right = w - zonePad - sideMargin
        val top = zonePad + panelPadTop

        txtPaint.typeface = arFont
        txtPaint.textSize = keyH * 0.33f
        txtPaint.color = theme.dim
        val fmH = txtPaint.fontMetrics
        val hCy = top + clipHeadH / 2f
        canvas.drawText(
            if (langForDst) "الترجمة إلى" else "الترجمة من",
            left + dp(40f), hCy - (fmH.ascent + fmH.descent) / 2f, txtPaint
        )
        txtPaint.color = theme.go
        canvas.drawText("تم", right - dp(22f), hCy - (fmH.ascent + fmH.descent) / 2f, txtPaint)
        langDoneRect.set(right - dp(56f), top, right, top + clipHeadH)

        val listTop = top + clipHeadH
        val listBottom = h - bottomPad - zonePad - panelPadBottom
        langListTop = listTop
        langListBottom = listBottom

        // "auto" only makes sense as a source
        val items = if (langForDst) Tr.langs.filter { it.code != Tr.AUTO } else Tr.langs
        val rowH = clipRowH * 0.72f
        val visible = max(rowH, listBottom - listTop)
        langMaxScroll = max(0f, items.size * rowH - visible)
        langScroll = langScroll.coerceIn(0f, langMaxScroll)
        val current = if (langForDst) Store.kbTrDst else Store.kbTrSrc

        canvas.save()
        canvas.clipRect(left, listTop, right, listBottom)
        for (i in items.indices) {
            val y = listTop + i * rowH - langScroll
            if (y > listBottom || y + rowH < listTop) continue
            rf.set(left, y + dp(2.5f), right, y + rowH - dp(2.5f))
            bgPaint.color = if (i == langPressed) theme.keyDown else theme.key
            canvas.drawRoundRect(rf, rad * 1.6f, rad * 1.6f, bgPaint)
            txtPaint.textSize = keyH * 0.31f
            txtPaint.color = if (items[i].code == current) theme.go else theme.text
            val fm = txtPaint.fontMetrics
            canvas.drawText(
                items[i].name, (left + right) / 2f,
                y + rowH / 2f - (fm.ascent + fm.descent) / 2f, txtPaint
            )
        }
        canvas.restore()
    }

    private fun langRowAt(y: Float): Int {
        if (y < langListTop || y > langListBottom) return -1
        val rowH = clipRowH * 0.72f
        val items = if (langForDst) Tr.langs.size - 1 else Tr.langs.size
        val i = ((y - langListTop + langScroll) / rowH).toInt()
        return if (i in 0 until items) i else -1
    }

    private fun langCodeAt(i: Int): String? {
        val items = if (langForDst) Tr.langs.filter { it.code != Tr.AUTO } else Tr.langs
        return items.getOrNull(i)?.code
    }

    /** Five little bars that ride the microphone level. */
    private fun drawLevel(canvas: Canvas, rightX: Float, cy: Float, h: Float) {
        val w = dp(2.5f)
        val gap = dp(2.5f)
        val base = h * 0.16f
        val span = h * 0.30f
        bgPaint.color = REC
        for (i in 0 until 5) {
            val wobble = 0.55f + 0.45f * sin(
                (pulse * 6.283f) + i * 1.1f
            )
            val bh = base + span * smoothLevel * wobble
            val x = rightX - i * (w + gap)
            rf.set(x - w / 2f, cy - bh / 2f, x + w / 2f, cy + bh / 2f)
            canvas.drawRoundRect(rf, w / 2f, w / 2f, bgPaint)
        }
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (txtPaint.measureText(s) <= maxW) return s
        var n = s.length
        while (n > 1 && txtPaint.measureText(s.substring(0, n) + "…") > maxW) n--
        return s.substring(0, n) + "…"
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
        canvas.clipRect(zonePad + sideMargin, emojiTop, width - zonePad - sideMargin, emojiBottom)
        txtPaint.textSize = emojiCell * 0.62f
        txtPaint.color = theme.text
        val left = zonePad + sideMargin
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
        val r = rad
        canvas.drawRoundRect(rf, r, r, keyPaint)

        // blank mode: the key shapes stay, everything written on them goes
        if (blank) return

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
            if (k.label.isEmpty()) return
            txtPaint.typeface = arFont
            txtPaint.textSize = keyH * 0.3f
            txtPaint.color = theme.dim
            val fmS = txtPaint.fontMetrics
            canvas.drawText(
                k.label, rf.centerX(), rf.centerY() - (fmS.ascent + fmS.descent) / 2f, txtPaint
            )
            return
        }

        txtPaint.typeface = if (k.arabic) arFont else enFont
        txtPaint.textSize = if (k.smallText) keyH * 0.33f else keyH * letterScale
        txtPaint.color = fg
        val fm = txtPaint.fontMetrics
        val baseline = rf.centerY() - (fm.ascent + fm.descent) / 2f - keyH * 0.03f
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
            Ico.GLOBE -> {
                canvas.drawCircle(cx, cy, s * 0.92f, icoPaint)
                canvas.drawLine(cx - s * 0.92f, cy, cx + s * 0.92f, cy, icoPaint)
                path.reset()
                path.moveTo(cx, cy - s * 0.92f)
                path.quadTo(cx + s * 0.68f, cy, cx, cy + s * 0.92f)
                path.quadTo(cx - s * 0.68f, cy, cx, cy - s * 0.92f)
                canvas.drawPath(path, icoPaint)
            }
            Ico.CLIP -> {
                rf.set(cx - s * 0.52f, cy - s * 0.78f, cx + s * 0.52f, cy + s * 0.92f)
                canvas.drawRoundRect(rf, s * 0.22f, s * 0.22f, icoPaint)
                rf.set(cx - s * 0.26f, cy - s * 0.98f, cx + s * 0.26f, cy - s * 0.58f)
                canvas.drawRoundRect(rf, s * 0.14f, s * 0.14f, icoPaint)
                canvas.drawLine(cx - s * 0.24f, cy - s * 0.1f, cx + s * 0.24f, cy - s * 0.1f, icoPaint)
                canvas.drawLine(cx - s * 0.24f, cy + s * 0.26f, cx + s * 0.24f, cy + s * 0.26f, icoPaint)
                canvas.drawLine(cx - s * 0.24f, cy + s * 0.62f, cx + s * 0.02f, cy + s * 0.62f, icoPaint)
            }
            Ico.MIC -> {
                rf.set(cx - s * 0.4f, cy - s * 0.95f, cx + s * 0.4f, cy + s * 0.15f)
                canvas.drawRoundRect(rf, s * 0.4f, s * 0.4f, icoPaint)
                path.reset()
                path.moveTo(cx - s * 0.74f, cy + s * 0.02f)
                path.quadTo(cx, cy + s * 1.1f, cx + s * 0.74f, cy + s * 0.02f)
                canvas.drawPath(path, icoPaint)
                canvas.drawLine(cx, cy + s * 0.72f, cx, cy + s * 1.0f, icoPaint)
            }
            Ico.GEAR, Ico.COG -> {
                // eight teeth around a ring; cheap to draw and reads as a gear at any size
                canvas.drawCircle(cx, cy, s * 0.40f, icoPaint)
                var i = 0
                while (i < 8) {
                    val a = i * (Math.PI / 4.0)
                    val ca = Math.cos(a).toFloat()
                    val sa = Math.sin(a).toFloat()
                    canvas.drawLine(
                        cx + ca * s * 0.66f, cy + sa * s * 0.66f,
                        cx + ca * s * 0.98f, cy + sa * s * 0.98f, icoPaint
                    )
                    i++
                }
            }
            Ico.TRANS -> {
                // the 文/A pair every translate button uses
                canvas.drawLine(cx - s * 0.95f, cy - s * 0.72f, cx - s * 0.05f, cy - s * 0.72f, icoPaint)
                canvas.drawLine(cx - s * 0.50f, cy - s * 0.95f, cx - s * 0.50f, cy - s * 0.62f, icoPaint)
                path.reset()
                path.moveTo(cx - s * 0.86f, cy + s * 0.22f)
                path.quadTo(cx - s * 0.26f, cy - s * 0.10f, cx - s * 0.14f, cy - s * 0.52f)
                canvas.drawPath(path, icoPaint)
                path.reset()
                path.moveTo(cx - s * 0.74f, cy - s * 0.42f)
                path.quadTo(cx - s * 0.40f, cy + s * 0.28f, cx - s * 0.02f, cy + s * 0.40f)
                canvas.drawPath(path, icoPaint)
                canvas.drawLine(cx + s * 0.18f, cy + s * 0.95f, cx + s * 0.62f, cy - s * 0.18f, icoPaint)
                canvas.drawLine(cx + s * 0.62f, cy - s * 0.18f, cx + s * 1.02f, cy + s * 0.95f, icoPaint)
                canvas.drawLine(cx + s * 0.34f, cy + s * 0.56f, cx + s * 0.88f, cy + s * 0.56f, icoPaint)
            }
            Ico.BACK -> {
                canvas.drawLine(cx + s * 0.85f, cy, cx - s * 0.75f, cy, icoPaint)
                path.reset()
                path.moveTo(cx - s * 0.10f, cy - s * 0.62f)
                path.lineTo(cx - s * 0.78f, cy)
                path.lineTo(cx - s * 0.10f, cy + s * 0.62f)
                canvas.drawPath(path, icoPaint)
            }
            Ico.SWAP -> {
                canvas.drawLine(cx - s * 0.85f, cy - s * 0.38f, cx + s * 0.70f, cy - s * 0.38f, icoPaint)
                path.reset()
                path.moveTo(cx + s * 0.22f, cy - s * 0.82f)
                path.lineTo(cx + s * 0.74f, cy - s * 0.38f)
                canvas.drawPath(path, icoPaint)
                canvas.drawLine(cx + s * 0.85f, cy + s * 0.38f, cx - s * 0.70f, cy + s * 0.38f, icoPaint)
                path.reset()
                path.moveTo(cx - s * 0.22f, cy + s * 0.82f)
                path.lineTo(cx - s * 0.74f, cy + s * 0.38f)
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

    private var downX = 0f
    private var downY = 0f
    private var scrollStart = 0f
    private var scrolling = false
    private var firedOnDown = false
    private val globeRect = RectF()
    private val micRect = RectF()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Android batches motion events to the display frame by default.
                // Asking for them unbuffered hands each one over the moment it
                // arrives, which takes up to a frame of waiting out of every tap.
                try { requestUnbufferedDispatch(e) } catch (_: Throwable) {}
                downX = x
                downY = y
                scrolling = false
                firedOnDown = false

                if (page == Pages.CLIP) {
                    clipDownY = y
                    clipScroll0 = clipScroll
                    clipScrolling = false
                    clipPressed =
                        if (y in clipListTop..clipListBottom)
                            ((y - clipListTop + clipScroll) / clipRowH).toInt()
                                .takeIf { it in Clip.all.indices } ?: -1
                        else -1
                    invalidate()
                    return true
                }

                if (page == Pages.LANGS) {
                    langDownY = y
                    langScroll0 = langScroll
                    langScrolling = false
                    langPressed = langRowAt(y)
                    invalidate()
                    return true
                }

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
                if (outerH > 0f && y >= globeRect.top) return true

                // the suggestion strip owns its own band, so the nearest-key fallback
                // below must never reach up into it
                if (stripVisible &&
                    y < zonePad + panelPadTop + suggH
                ) {
                    pressedZone = stripZone(x)
                    pressedSugg = if (pressedZone >= 0) pressedZone else -1
                    suggDragging = false
                    stripDownX = x
                    stripScroll0 = suggScroll
                    if (pressedZone == -3 && !toolsOpen) {
                        clipArmed = true
                        handler.postDelayed(clipHoldRunnable, 380)
                    }
                    if (pressedZone != -1) invalidateStrip()
                    return true
                }

                if (toolsOpen) setToolsOpen(false)

                val k = find(x, y) ?: return true

                // any key press brings the labels back, and that press does nothing else
                if (blank) {
                    blank = false
                    blankArmed = false
                    handler.removeCallbacks(blankRunnable)
                    invalidate()
                    return true
                }
                if (blankOnHold && k.code == Code.SPACE) {
                    blankArmed = true
                    handler.postDelayed(blankRunnable, 320)
                }

                pressed = k
                // a key with alternates waits before typing: the character is only
                // committed once it is clear this is a tap and not a hold
                if (altsOn && KbLayout.altsFor(k) != null) {
                    altArmed = true
                    handler.postDelayed(altRunnable, 300)
                }
                if (!pressFx) pressed = null
                if (fastKeys) { firedOnDown = true; fire(k) } else firedOnDown = false
                if (pressFx) invalidateKey(k)
                if (k.code == Code.DEL) {
                    repeating = true
                    repeatTicks = 0
                    listener?.onRepeatState(true)
                    handler.postDelayed(repeatRunnable, 210)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger landing while the first is still down is what fast
                // typing looks like. Handling only the first pointer meant every such
                // letter was silently lost.
                if (page == Pages.EMOJI || page == Pages.CLIP || page == Pages.LANGS) return true
                if (altList.isNotEmpty() || blank || transOn) return true
                val i = e.actionIndex
                val px = e.getX(i)
                val py = e.getY(i)
                if (stripVisible && py < zonePad + panelPadTop + suggH) return true
                if (outerH > 0f && py >= globeRect.top) return true
                val k2 = find(px, py) ?: return true
                // a held delete or a modifier is a gesture, not a tap to repeat
                if (k2.code == Code.DEL || k2.code == Code.SHIFT) return true
                fire(k2)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> return true

            MotionEvent.ACTION_MOVE -> {
                if (page == Pages.CLIP) {
                    val dy = y - clipDownY
                    if (!clipScrolling && Math.abs(dy) > dp(8f)) {
                        clipScrolling = true
                        clipPressed = -1
                    }
                    if (clipScrolling) {
                        clipScroll = (clipScroll0 - dy).coerceIn(0f, clipMaxScroll)
                        invalidate()
                    }
                    return true
                }

                if (page == Pages.LANGS) {
                    val dy = y - langDownY
                    if (!langScrolling && Math.abs(dy) > dp(8f)) {
                        langScrolling = true
                        langPressed = -1
                    }
                    if (langScrolling) {
                        langScroll = (langScroll0 - dy).coerceIn(0f, langMaxScroll)
                        invalidate()
                    }
                    return true
                }
                if (pressedZone != -1 && downY < zonePad + panelPadTop + suggH) {
                    val dx = x - stripDownX
                    if (!suggDragging && Math.abs(dx) > dp(9f)) {
                        suggDragging = true
                        pressedSugg = -1
                        clipArmed = false
                        handler.removeCallbacks(clipHoldRunnable)
                    }
                    if (suggDragging && suggMax > 0f) {
                        suggScroll = (stripScroll0 - dx).coerceIn(0f, suggMax)
                        invalidateStrip()
                    }
                    return true
                }
                if (altList.isNotEmpty()) { moveAlts(x); return true }
                if (altArmed && (Math.abs(x - downX) > dp(12f) ||
                        Math.abs(y - downY) > dp(12f))
                ) {
                    altArmed = false
                    handler.removeCallbacks(altRunnable)
                }
                if (blankArmed && (Math.abs(x - downX) > dp(10f) ||
                        Math.abs(y - downY) > dp(10f))
                ) {
                    blankArmed = false
                    handler.removeCallbacks(blankRunnable)
                }
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
                blankArmed = false
                handler.removeCallbacks(blankRunnable)

                if (page == Pages.CLIP) {
                    val i = clipPressed
                    val dragged = clipScrolling
                    clipPressed = -1
                    clipScrolling = false
                    invalidate()
                    if (dragged) return true
                    if (clipDoneRect.contains(x, y)) { listener?.onClipClose(); return true }
                    for (si in shelfRects.indices) {
                        if (shelfRects[si].contains(x, y)) { listener?.onPicPick(si); return true }
                    }
                    if (i >= 0) listener?.onClipPick(i)
                    return true
                }

                if (page == Pages.LANGS) {
                    val i = langPressed
                    val dragged = langScrolling
                    langPressed = -1
                    langScrolling = false
                    invalidate()
                    if (dragged) return true
                    if (langDoneRect.contains(x, y)) { listener?.onLangPick(""); return true }
                    if (i >= 0) {
                        val code = langCodeAt(i)
                        if (code != null) {
                            Store.setTrLang(context, langForDst, code)
                            listener?.onLangPick(code)
                        }
                    }
                    return true
                }

                if (altList.isNotEmpty()) {
                    val out = closeAlts(true)
                    pressed = null
                    if (!out.isNullOrEmpty()) listener?.onChar(out)
                    return true
                }
                altArmed = false
                handler.removeCallbacks(altRunnable)

                if (firedOnDown) {
                    val fk = pressed
                    pressed = null
                    firedOnDown = false
                    invalidateKey(fk)
                    return true
                }

                if (page == Pages.EMOJI && !scrolling && y >= emojiTop && y <= emojiBottom) {
                    val col = ((x - zonePad - sideMargin) / emojiCell).toInt()
                    val row = ((y + emojiScroll - emojiTop) / emojiCell).toInt()
                    val idx = row * 8 + col
                    if (col in 0..7 && idx >= 0 && idx < emojiKeys.size) {
                        listener?.onChar(emojiKeys[idx].label)
                    }
                    return true
                }

                if (outerH > 0f && y >= globeRect.top) {
                    if (globeRect.contains(x, y)) listener?.onLang()
                    return true
                }

                if (stripVisible &&
                    y < zonePad + panelPadTop + suggH
                ) {
                    val z = pressedZone
                    val dragged = suggDragging
                    val hadHold = clipFired
                    pressedZone = -1
                    pressedSugg = -1
                    suggDragging = false
                    clipArmed = false
                    clipFired = false
                    handler.removeCallbacks(clipHoldRunnable)
                    invalidateStrip()
                    if (dragged || hadHold) return true
                    when {
                        z == -2 -> setToolsOpen(!toolsOpen)
                        z == -3 -> listener?.onClipTap()
                        z <= -4 && z >= -7 -> {
                            setToolsOpen(false)
                            listener?.onTool(-(z + 4))
                        }
                        z == -10 -> listener?.onTransLang(false)
                        z == -11 -> listener?.onTransSwap()
                        z == -12 -> listener?.onTransLang(true)
                        z == -13 -> listener?.onTransClose()
                        z >= 0 && suggAt(x, width.toFloat()) == z -> {
                            if (suggs.getOrNull(z).isNullOrEmpty()) {
                                if (suggText.isNotEmpty()) listener?.onSuggestionTap()
                            } else listener?.onPredictionTap(z)
                        }
                    }
                    return true
                }

                val k = pressed
                pressed = null
                invalidateKey(k)
                if (!firedOnDown && k != null && k.hitT(x, y)) fire(k)
                firedOnDown = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                altArmed = false
                handler.removeCallbacks(altRunnable)
                if (altList.isNotEmpty()) closeAlts(false)
                stopRepeat(); blankArmed = false; clipArmed = false; clipFired = false
                handler.removeCallbacks(clipHoldRunnable)
                pressedSugg = -1; suggDragging = false
                handler.removeCallbacks(blankRunnable); pressedZone = -1; val k = pressed; pressed = null
                firedOnDown = false; invalidateKey(k); return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** Width the mic reserves at the right end of the strip. */
    private val micW get() = if (micInStrip) suggH * 0.9f else 0f

    /** Clear space kept between the mic and the screen edge. */
    private val micEdge get() = if (micInStrip) sideMargin + dp(4f) else 0f

    /**
     * Opens the alternates bubble over a key, the way the iPhone does: a panel
     * directly above the key, the pressed character already selected, and the
     * choice made by sliding and lifting in one gesture.
     */
    private fun openAlts(k: Key) {
        val list = KbLayout.altsFor(k) ?: return
        altArmed = false
        altKey = k
        altList = list
        altSel = 0

        txtPaint.typeface = if (k.arabic) arFont else enFont
        txtPaint.textSize = keyH * 0.52f
        var cell = keyH * 0.95f
        for (a in list) cell = max(cell, txtPaint.measureText(a) + dp(20f))
        altCellW = cell

        val padding = dp(4f)
        val w = cell * list.size + padding * 2f
        val h = keyH * 1.12f + padding * 2f
        // centred over the key, pulled inside when it would run past an edge
        val cx = k.x + k.w / 2f
        var left = cx - w / 2f
        left = left.coerceIn(zonePad + dp(2f), width - zonePad - w - dp(2f))
        var top = k.y - h - dp(6f)
        if (top < zonePad + dp(2f)) top = zonePad + dp(2f)
        altRect.set(left, top, left + w, top + h)

        // the character already went in on touch-down, which is what keeps typing
        // instant; the hold takes it back before offering the choice
        if (firedOnDown) {
            listener?.onDelete()
            firedOnDown = false
        }
        pressed = null
        performHapticFeedback(
            android.view.HapticFeedbackConstants.LONG_PRESS,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        invalidate()
    }

    private fun moveAlts(x: Float) {
        if (altList.isEmpty()) return
        val padding = dp(4f)
        val i = ((x - altRect.left - padding) / altCellW).toInt()
            .coerceIn(0, altList.size - 1)
        if (i != altSel) { altSel = i; invalidate() }
    }

    /** Lifts the bubble. Returns what should be typed, or null. */
    private fun closeAlts(commit: Boolean): String? {
        if (altList.isEmpty()) return null
        val out = if (commit) altList.getOrNull(altSel) else null
        altKey = null
        altList = emptyList()
        altSel = 0
        invalidate()
        return out
    }

    private fun drawAlts(canvas: Canvas) {
        if (altList.isEmpty()) return
        val padding = dp(4f)
        val r = dp(11f)

        // the bubble takes the theme's own key colour, so it never looks bolted on
        rf.set(altRect.left - dp(1f), altRect.top, altRect.right + dp(1f),
            altRect.bottom + dp(2.5f))
        bgPaint.color = theme.keyDark
        canvas.drawRoundRect(rf, r, r, bgPaint)
        bgPaint.color = theme.key
        canvas.drawRoundRect(altRect, r, r, bgPaint)

        txtPaint.typeface = if (altKey?.arabic == true) arFont else enFont
        txtPaint.textSize = keyH * 0.52f
        val fm = txtPaint.fontMetrics
        val cy = altRect.centerY()
        for (i in altList.indices) {
            val l = altRect.left + padding + altCellW * i
            if (i == altSel) {
                rf.set(l, altRect.top + padding, l + altCellW, altRect.bottom - padding)
                bgPaint.color = theme.go
                canvas.drawRoundRect(rf, dp(8f), dp(8f), bgPaint)
            }
            txtPaint.color = if (i == altSel) theme.goIcon else theme.text
            canvas.drawText(
                altList[i], l + altCellW / 2f, cy - (fm.ascent + fm.descent) / 2f, txtPaint
            )
        }
    }

    /** -3 the clipboard key, -2 the microphone, 0.. a suggestion, -1 nothing. */
    private fun stripZone(x: Float): Int {
        val left = zonePad
        val right = width - zonePad

        if (transOn) {
            if (trCloseRect.left - dp(4f) <= x) return -13
            if (trDstRect.contains(x, trDstRect.centerY())) return -12
            if (trSwapRect.left <= x && x <= trSwapRect.right) return -11
            if (trSrcRect.left - dp(4f) <= x) return -10
            return -1
        }

        if (micInStrip && x > right - micEdge - micW * 1.25f) return -2
        if (toolsOpen) {
            for (i in toolRects.indices) {
                val r = toolRects[i]
                if (x >= r.left - dp(2f) && x <= r.right + dp(2f)) return -(4 + i)
            }
            return -1
        }
        if (clipOn && x < left + micEdge + micW * 1.25f) return -3
        return suggAt(x, width.toFloat())
    }

    private fun invalidateKey(k: Key?) {
        if (k == null) { invalidate(); return }
        invalidate(
            (k.x - 2f).toInt(), (k.y - 2f).toInt(),
            (k.x + k.w + 2f).toInt(), (k.y + k.h + 2f).toInt()
        )
    }

    private fun stopRepeat() {
        if (repeating) listener?.onRepeatState(false)
        repeating = false
        repeatTicks = 0
        handler.removeCallbacks(repeatRunnable)
    }

    /**
     * Finds the key under a touch. First the grown rectangles, which tile the whole
     * panel; then, as a safety net, the nearest key within a short reach, so a tap that
     * lands just outside the rows still types instead of doing nothing.
     */
    private fun find(x: Float, y: Float): Key? {
        for (row in rows) for (k in row) if (!k.spacer && k.hitT(x, y)) return k

        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (row in rows) for (k in row) {
            if (k.spacer) continue
            val d = k.distT(x, y)
            if (d < bestD) { bestD = d; best = k }
        }
        val reach = keyH * 0.6f
        return if (bestD <= reach * reach) best else null
    }

    /**
     * The letters whose keys the finger was nearly on, for the tap just made.
     *
     * The keyboard is the only thing that knows a tap landed two pixels inside 'س'
     * with 'ش' right beside it. Handing that to the corrector turns a blind guess
     * over every letter in the alphabet into a short list of what he plausibly meant.
     */
    var lastNear: String = ""
        private set

    private fun neighbours(k: Key): String {
        if (k.code != Code.CHAR || k.out.length != 1) return ""
        val cx = k.x + k.w / 2f
        val cy = k.y + k.h / 2f
        val reach = (k.w * 1.35f) * (k.w * 1.35f)
        val sb = StringBuilder(4)
        for (row in rows) for (o in row) {
            if (o.spacer || o === k) continue
            if (o.code != Code.CHAR || o.out.length != 1) continue
            val dx = (o.x + o.w / 2f) - cx
            val dy = (o.y + o.h / 2f) - cy
            // only the row above, below and either side — not the whole board
            if (Math.abs(dy) > k.h * 1.2f) continue
            if (dx * dx + dy * dy <= reach) sb.append(o.out)
            if (sb.length >= 5) break
        }
        return sb.toString()
    }

    private fun fire(k: Key) {
        lastNear = if (k.code == Code.CHAR) neighbours(k) else ""
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
