package com.huc.fasttype

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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

        /** The action: the translation is settled, keep it and close. */
        fun onTransGo()

        /** The line was tapped: bring in whatever was copied. */
        fun onTransPaste()

        /** One of the rewrite choices, by index into [KeyboardView.fixOpts]. */
        fun onFixPick(index: Int)

        /** Put the finished rewrite into the field. */
        fun onFixApply()

        /** One of the suggested replies was tapped. */
        fun onFixReply(index: Int)

        /** Close the rewrite panel and change nothing. */
        fun onFixClose()

        /** Empty the line in one go. */
        fun onTransClear()
        fun onLangPick(code: String)
        /** One of the recent pictures was chosen from the clipboard page. */
        fun onPicPick(index: Int)
        /** The finger settled on a different key than the one it landed on. */
        fun onReplaceChar(s: String)
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
    // The faces page keeps the strip: it is the one place that has nothing to
    // suggest, and the one place he most needs a way back out.
    private val stripVisible get() = showSugg || transOn || page == Pages.EMOJI
    /** Height of the translate box row. */
    private val transH get() = suggH * 1.18f
    private var suggRad = dp(12f)
    private var hairOn = true
    private var hairH = 0.54f
    private var hairW = 1f
    private var micInStrip = true
    private var letterScale = 0.49f
    private var pressFx = true

    /**
     * How far above the touch point the keyboard reads the tap, in hundredths of
     * a key height.
     *
     * A finger aims with the tip and lands with the pad, so the contact patch
     * sits below where the person thinks they are pointing. Every row of keys
     * therefore collects taps meant for the row above it. Zero turns it off.
     */
    private var fingerY = 12

    /** Whether a held letter key lifts a copy of itself above the finger. */
    private var peekOn = true

    /**
     * Glass: how much of the app behind shows through.
     *
     * 0 is the solid keyboard. Above that the panel and the keys are painted with
     * alpha, and — on Android 12 and up, when the system has blurs on — the
     * window blurs what is behind it, which is what makes translucency read as
     * glass rather than as a keyboard someone forgot to finish.
     */
    private var glassOn = false
    private var glassPanel = 62
    private var glassKey = 78

    /**
     * There is no blur behind this translucency, and there cannot be.
     *
     * FLAG_BLUR_BEHIND blurs everything behind the window, and an IME's window
     * spans the whole screen with the keyboard drawn at its foot — so asking
     * for it blurred the entire app, not the strip behind the keys. Nothing in
     * the API scopes it to a region, so the flag is gone and what is left is
     * plain translucency.
     */

    /** How far the whole keyboard is veiled, 0-60. */
    private var shadePct = 0

    /** How much amber is laid over it, 0-40. */
    private var warmPct = 0
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

    /**
     * The translation itself. The bar shows this; the source is already visible.
     *
     * Private setter: a public one would compile to the same JVM method as
     * [setTransOut] below, which takes the same single String.
     */
    var transOut: String = ""
        private set
    private val trSrcRect = RectF()
    private val trGoRect = RectF()
    private val trClearRect = RectF()

    /** Set while the translate line is held down, for its pressed fill. */
    // ---- the rewrite panel ----

    var fixOn = false
        private set

    /** The choices on offer, which depend on the language of the text. */
    var fixOpts: List<String> = emptyList()
        private set

    /** -1 nothing chosen, otherwise the one being worked on or finished. */
    var fixChosen = -1
        private set

    var fixBusy = false
        private set

    /** The rewrite, once it is back. Empty until then. */
    var fixResult = ""
        private set

    /** What he wrote, kept so the panel can show it above the result. */
    var fixSource = ""
        private set

    var fixError = ""
        private set

    /** The suggested replies, when the reply choice was the one taken. */
    var fixReplies: List<String> = emptyList()
        private set

    private val fixReplyRects = ArrayList<RectF>(3)

    private val fixRects = ArrayList<RectF>(4)
    private val fixApplyRect = RectF()
    private val fixCancelRect = RectF()
    private val fixCloseRect = RectF()
    private var fixPressed = -99

    fun openFix(source: String, opts: List<String>) {
        fixOn = true
        fixSource = source
        fixOpts = opts
        fixChosen = -1
        fixBusy = false
        fixResult = ""
        fixError = ""
        fixReplies = emptyList()
        requestLayout()
        invalidate()
    }

    fun closeFix() {
        if (!fixOn) return
        fixOn = false
        fixResult = ""
        fixError = ""
        fixReplies = emptyList()
        fixChosen = -1
        fixBusy = false
        requestLayout()
        invalidate()
    }

    fun setFixBusy(i: Int) {
        fixChosen = i
        fixBusy = true
        fixResult = ""
        fixError = ""
        fixReplies = emptyList()
        requestLayout()
        invalidate()
    }

    fun setFixReplies(list: List<String>, error: String) {
        fixBusy = false
        fixReplies = list
        fixReplyLines = list.map { measureFixLines(it, keyH * 0.29f, 3) }
        fixResult = ""
        fixError = error
        requestLayout()
        invalidate()
    }

    /** Lines each reply row needs, same reason as the result box. */
    private var fixReplyLines: List<Int> = emptyList()

    private fun fixReplyRowH(i: Int): Float {
        txtPaint.typeface = arFont
        txtPaint.textSize = keyH * 0.29f
        val fm = txtPaint.fontMetrics
        val lh = (fm.descent - fm.ascent) * 1.08f
        return dp(8f) * 2 + lh * (fixReplyLines.getOrNull(i) ?: 2)
    }

    fun setFixResult(text: String, error: String) {
        fixBusy = false
        fixResult = text
        fixResultLines = measureFixLines(text)
        fixReplies = emptyList()
        fixError = error
        requestLayout()
        invalidate()
    }

    /**
     * How many lines the result box stands at.
     *
     * The box used to be a fixed four and anything longer was cut with an
     * ellipsis — so a rewrite that came back whole still *looked* half done,
     * and the full text went in on استبدل anyway. It measures instead.
     */
    private var fixResultLines = 2

    /** Up to here the panel grows with the answer; past it the phone runs out. */
    private val FIX_MAX_LINES = 8

    private fun measureFixLines(
        text: String, size: Float = -1f, max: Int = FIX_MAX_LINES
    ): Int {
        if (width <= 0 || keyH <= 0f || text.isEmpty()) return 2
        txtPaint.typeface = arFont
        txtPaint.textSize = if (size > 0f) size else keyH * 0.30f
        val avail = (width - (zonePad + sideMargin) * 2) - dp(12f) * 2
        if (avail <= 0f) return 2
        var n = 1
        val line = StringBuilder()
        for (wd in text.split(' ')) {
            val probe = if (line.isEmpty()) wd else line.toString() + " " + wd
            if (txtPaint.measureText(probe) <= avail) {
                line.setLength(0); line.append(probe)
            } else if (line.isEmpty()) {
                line.append(wd)
            } else {
                n++
                line.setLength(0); line.append(wd)
            }
        }
        return n.coerceIn(1, max)
    }

    /** The height of the result box for the number of lines in it. */
    private fun fixResultBoxH(): Float {
        txtPaint.typeface = arFont
        txtPaint.textSize = keyH * 0.30f
        val fm = txtPaint.fontMetrics
        val lh = (fm.descent - fm.ascent) * 1.08f
        return dp(10f) * 2 + lh * fixResultLines
    }

    private var transBoxDown = false

    /** Set while the clear button inside that line is held down. */
    private var transClearDown = false

    /** Amber while the engine works, green once the line is settled. */
    private val WORK_DOT = Color.parseColor("#E8A33C")
    private val OK_DOT = Color.parseColor("#17A871")
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

    /**
     * What each choice looks like, which is not always what it types.
     *
     * A vowel mark on its own has nothing to sit on and renders as a smudge, so
     * it is drawn on a dotted circle — the same way every Arabic keyboard and
     * every grammar book shows one.
     */
    private var altShow: List<String> = emptyList()
    private var altCols = 1
    private var altRows = 1
    private var altCellH = 0f
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

    /** Holding the direction chip opens the full language list. */
    private val langHoldRunnable = Runnable {
        if (!clipArmed) return@Runnable
        clipArmed = false
        clipFired = true
        performHapticFeedback(
            android.view.HapticFeedbackConstants.LONG_PRESS,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        listener?.onTransLang(true)
    }

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
    /** The key that fired the instant the finger touched down. */
    private var firedKey: Key? = null

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
    private val TOOL_ICONS = intArrayOf(Ico.SMILE, Ico.MIC, Ico.TRANS, Ico.WAND, Ico.CLIP, Ico.COG)

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
        // on the faces page the tools are the only way back, so they stay
        if (!open && page == Pages.EMOJI) return
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
        transOut = ""
        setToolsOpen(false)
        toolsT = 0f
        requestLayout()
        invalidate()
    }

    fun setTransOut(out: String) {
        if (out == transOut) return
        transOut = out
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

    /**
     * The letters on the keys, at whatever weight he has set.
     *
     * They were drawn at 400 — plain regular — and that is the whole of why his
     * keyboard looked lighter than an iPhone's in the two screenshots side by
     * side. Android gives a real weight axis from 28 onwards; below that there is
     * only regular and bold, so the dial lands on whichever is nearer.
     */
    private var arFont: Typeface = Typeface.DEFAULT
    private var enFont: Typeface = Typeface.DEFAULT

    private var fontWeight = -1
    private var fontArabicOn = false

    /**
     * The bundled Arabic face, loaded once.
     *
     * Null while it has not been asked for, and null again if it cannot be read,
     * which is the same thing to every caller: fall back to the system face.
     */
    private var packFont: Typeface? = null
    private var packTried = false

    private fun pack(): Typeface? {
        if (packTried) return packFont
        packTried = true
        packFont = try {
            Typeface.createFromAsset(context.assets, "fonts/arabic_ui.ttf")
        } catch (_: Throwable) {
            null
        }
        return packFont
    }

    /**
     * Picks the two faces.
     *
     * They are chosen separately because the bundled file has no Latin letters
     * and no digits in it at all — 255 Arabic glyphs and nothing else. Setting
     * it on both would leave the English layout drawing blanks, so it is only
     * ever the Arabic one, and the weight slider keeps working on the Latin
     * side as before.
     */
    private fun applyFont() {
        val w = Store.kbWeight
        val useArabic = Store.kbArFont
        if (w == fontWeight && useArabic == fontArabicOn) return
        fontWeight = w
        fontArabicOn = useArabic
        val sys = if (android.os.Build.VERSION.SDK_INT >= 28) {
            Typeface.create(Typeface.SANS_SERIF, w, false)
        } else {
            Typeface.create(
                if (w >= 600) "sans-serif-medium" else "sans-serif",
                if (w >= 700) Typeface.BOLD else Typeface.NORMAL
            )
        }
        enFont = sys
        arFont = if (useArabic) (pack() ?: sys) else sys
    }

    private val handler = Handler(Looper.getMainLooper())
    private var repeating = false
    private var repeatTicks = 0

    /**
     * Backspace repeat. It starts quickly, speeds up as it goes, and after about a
     * second and a half switches to whole words, so clearing a line never crawls.
     */
    /**
     * Backspace repeat: fast, but it has to be asked for.
     *
     * The guard against a resting thumb is the delay before any of this starts,
     * not the speed once it has. Slowing the repeat itself only made clearing a
     * line a chore, so the original pace is back and the wait in front of it
     * stays long.
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

    /**
     * The panel colour, with glass applied.
     *
     * The alpha multiplies whatever the theme already asked for rather than
     * replacing it, so a theme that was itself translucent stays that way.
     */
    private fun panelCol(c: Int = theme.panel): Int =
        if (!glassOn) c
        else Color.argb(
            (Color.alpha(c) * glassPanel / 100f).toInt().coerceIn(0, 255),
            Color.red(c), Color.green(c), Color.blue(c)
        )

    /** The same for a key face. Keys stay more solid than the panel: they are hit. */
    private fun keyCol(c: Int): Int =
        if (!glassOn) c
        else Color.argb(
            (Color.alpha(c) * glassKey / 100f).toInt().coerceIn(0, 255),
            Color.red(c), Color.green(c), Color.blue(c)
        )

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
        applyFont()
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
        fingerY = Store.kbFingerY
        peekOn = Store.kbPeek
        glassOn = Store.kbGlass
        glassPanel = Store.kbGlassPanel
        glassKey = Store.kbGlassKey
        shadePct = Store.kbShade
        warmPct = Store.kbWarm
        blankOnHold = Store.kbBlankHold
        clearBottom = Store.kbClearBottom
        KbLayout.globeInRow = Store.kbGlobeRow
        KbLayout.dotInRow = Store.kbDotKey
        requestLayout()
        invalidate()
    }

    fun rebuild() {
        if (page == Pages.CLIP) clipScroll = 0f
        if (page == Pages.LANGS) langScroll = 0f
        val was = if (width > 0) contentHeight() else -1f
        val forRows = if (page == Pages.LANGS) Pages.CLIP else page
        rows = if (page == Pages.EMOJI) listOf(KbLayout.emojiBottom(arabic))
        else KbLayout.rows(forRows, arabic, shift, numRow)
        if (page == Pages.EMOJI) buildEmoji()
        if (width > 0) measureKeys(width.toFloat())
        // Letters and symbols are the same height, and that is the switch he taps
        // most. Asking the window to measure itself again for a board that has not
        // changed size costs a frame for nothing, so only ask when it has.
        if (was < 0f || Math.abs(contentHeight() - was) > 0.5f) requestLayout()
        invalidate()
    }

    private fun buildEmoji() {
        val list = Emoji.sets.getOrElse(emojiTab) { Emoji.sets[0] }
        emojiKeys = list.map { Key(label = it, out = it) }
        emojiScroll = 0f
    }

    private fun rowCount(): Int = rows.size

    /**
     * How tall the rewrite panel is right now.
     *
     * It grows as the exchange does — the choices, then the answer, then the
     * two buttons — rather than reserving room for an answer that has not come
     * back yet and may not.
     */
    private fun fixHeight(): Float {
        // The panel has to stand as tall as the keys it replaces, or the whole
        // window shrinks under the field and the chat jumps up and back down
        // around it. Below that floor it grows with the exchange.
        val floor = keyH * 4 + vGap * 3
        var h = keyH * 1.05f                      // the choices
        h += keyH * 0.95f                         // what he wrote, over two lines
        if (fixBusy) h += keyH * 0.85f
        if (fixError.isNotEmpty()) h += keyH * 0.90f
        if (fixResult.isNotEmpty()) h += fixResultBoxH() + dp(6f) + keyH * 0.95f
        // each reply is a row he taps, so they stand instead of a result box
        for (i in fixReplies.indices) h += fixReplyRowH(i) + dp(6f)
        return maxOf(h + vGap * 2, floor)
    }

    private fun contentHeight(): Float {
        // The clipboard page has no key rows of its own, so it borrows the height of
        // the letter keyboard — otherwise it collapses to nothing and the list has
        // nowhere to appear.
        if (page == Pages.CLIP || page == Pages.LANGS) {
            return zonePad * 2 + panelPadTop + panelPadBottom +
                suggH + vGap + keyH * 4 + vGap * 3 + outerH + bottomPad
        }
        if (fixOn) {
            return zonePad * 2 + panelPadTop + panelPadBottom +
                suggH + vGap + fixHeight() + outerH + bottomPad
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

    /**
     * Everything a flat colour cannot say.
     *
     * The keyboard painted one solid colour per surface, so every theme was the
     * same keyboard in another colour. Three things change that and none of them
     * cost a frame: a gradient down the panel, one or two wide soft colour
     * fields behind the keys, and a gradient inside the key itself. All three
     * are built once per size and reused, because this runs forty times a frame.
     */
    private var gradId = ""
    private var gradW = 0f
    private var gradH = 0f
    private var panelShader: LinearGradient? = null
    private var auraA: RadialGradient? = null
    private var auraB: RadialGradient? = null

    private var keyShader: LinearGradient? = null
    private var keyShaderH = 0f
    private var keyShaderId = ""
    private val keyMatrix = Matrix()

    private var sheenShader: LinearGradient? = null
    private var sheenW = 0f
    private var sheenId = ""

    private fun ensureGradients(w: Float, top: Float, bottom: Float) {
        val h = bottom - top
        if (gradId == theme.id && gradW == w && gradH == h) return
        gradId = theme.id; gradW = w; gradH = h
        panelShader = if (theme.panelTop == 0) null else LinearGradient(
            0f, top, 0f, bottom, theme.panelTop, theme.panelBottom, Shader.TileMode.CLAMP
        )
        auraA = aura(theme.aura1, theme.aura1x, theme.aura1y, theme.aura1r, w, top, h)
        auraB = aura(theme.aura2, theme.aura2x, theme.aura2y, theme.aura2r, w, top, h)
    }

    private fun aura(
        c: Int, fx: Float, fy: Float, fr: Float, w: Float, top: Float, h: Float
    ): RadialGradient? {
        if (c == 0 || fr <= 0f) return null
        return RadialGradient(
            w * fx, top + h * fy, max(1f, h * fr),
            c, c and 0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Nothing is painted outside the panel, so the rounded top corners show the
        // app behind them — the same way the iOS keyboard does it.
        val pTop = zonePad
        val pBottom = h - bottomPad - zonePad
        rf.set(zonePad, pTop, w - zonePad, pBottom)
        ensureGradients(w, pTop, pBottom)
        bgPaint.color = panelCol()
        if (panelShader != null) {
            val a = Color.alpha(panelCol())
            bgPaint.shader = panelShader
            bgPaint.alpha = a
        }
        canvas.drawRoundRect(rf, panelRad, panelRad, bgPaint)
        if (panelRad > 0f) {
            // square off the bottom; only the top two corners are rounded
            canvas.drawRect(rf.left, pTop + panelRad, rf.right, pBottom, bgPaint)
        }
        // the colour fields, inside the panel's own corners
        if (auraA != null || auraB != null) {
            canvas.save()
            path.reset()
            path.addRoundRect(rf, panelRad, panelRad, Path.Direction.CW)
            canvas.clipPath(path)
            canvas.drawRect(rf.left, pTop, rf.right, pBottom, bgPaint)
            for (sh in arrayOf(auraA, auraB)) {
                if (sh == null) continue
                bgPaint.shader = sh
                bgPaint.alpha = Color.alpha(panelCol())
                canvas.drawRect(rf.left, pTop, rf.right, pBottom, bgPaint)
            }
            canvas.restore()
        }
        bgPaint.shader = null
        bgPaint.alpha = 255
        if (zonePad > 0.5f) {
            edgePaint.color = theme.panelEdge
            edgePaint.strokeWidth = dp(1f)
            canvas.drawRoundRect(rf, panelRad, panelRad, edgePaint)
        }
        if (bottomPad > 0f && !clearBottom) {
            // The strip under the keys is the panel continued, so on a theme with
            // a gradient it has to take the colour the gradient ended on. Painting
            // it the flat panel colour drew a visible seam across the bottom.
            bgPaint.color = panelCol(
                if (theme.panelBottom != 0) theme.panelBottom else theme.panel
            )
            canvas.drawRect(zonePad, pBottom, w - zonePad, h, bgPaint)
        }

        if (fixOn) { drawStrip(canvas, w); drawFix(canvas, w); return }
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

        drawPeek(canvas)
        drawAlts(canvas)
        drawShade(canvas)
    }

    /**
     * Takes the glare off, over whatever theme is on.
     *
     * A keyboard of white keys is not a colour on the screen, it is a lamp: it
     * is the brightest thing in the room, the nearest thing to the eye, and it
     * is there for as long as the typing lasts. No choice of white fixes that,
     * because the problem is how much light leaves the panel, not which white it
     * is. So the last thing drawn is a veil over everything — keys, letters,
     * strip and bubbles alike.
     *
     * Drawn after the alternates and the preview deliberately: something left
     * undimmed on a dimmed keyboard is a brighter lamp than before.
     *
     * The warm pass is separate because it is a different complaint. Dimming
     * lowers the amount of light; the amber lowers the blue in it, which is what
     * makes a screen hard to look at late at night rather than merely bright.
     */
    private fun drawShade(canvas: Canvas) {
        if (shadePct <= 0 && warmPct <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        bgPaint.style = Paint.Style.FILL
        if (shadePct > 0) {
            // capped well below opaque: the keys have to stay readable
            bgPaint.color = Color.argb((shadePct * 2.3f).toInt().coerceIn(0, 170), 0, 0, 0)
            canvas.drawRect(0f, 0f, w, h, bgPaint)
        }
        if (warmPct > 0) {
            bgPaint.color = Color.argb(
                (warmPct * 1.9f).toInt().coerceIn(0, 110), 0xFF, 0x9A, 0x3C
            )
            canvas.drawRect(0f, 0f, w, h, bgPaint)
        }
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
    /**
     * The rewrite panel.
     *
     * It replaces the keys rather than sitting over them: there is nothing to
     * type while it is open, and a half-covered keyboard invites a tap that
     * does nothing. Every row is drawn in the theme's own key colour, so it
     * belongs to whichever theme is on.
     */
    private fun drawFix(canvas: Canvas, w: Float) {
        val left = zonePad + sideMargin
        val right = w - zonePad - sideMargin
        var y = zonePad + panelPadTop + suggH + vGap

        txtPaint.typeface = arFont
        val pad = dp(12f)

        // ---- the choices ----
        val chH = keyH * 1.05f - dp(6f)
        fixRects.clear()
        if (fixOpts.isNotEmpty()) {
            val gapN = dp(5f)
            val cw = (right - left - gapN * (fixOpts.size - 1)) / fixOpts.size
            txtPaint.textSize = keyH * 0.30f
            val fm0 = txtPaint.fontMetrics
            for (i in fixOpts.indices) {
                val x0 = left + i * (cw + gapN)
                rf.set(x0, y, x0 + cw, y + chH)
                fixRects.add(RectF(rf))
                val live = i == fixChosen && (fixBusy || fixResult.isNotEmpty())
                bgPaint.color = when {
                    live -> theme.go
                    fixPressed == i -> theme.keyDown
                    else -> theme.key
                }
                canvas.drawRoundRect(rf, dp(10f), dp(10f), bgPaint)
                txtPaint.color = if (live) theme.goIcon else theme.text
                canvas.drawText(
                    ellipsize(fixOpts[i], cw - dp(8f)), rf.centerX(),
                    rf.centerY() - (fm0.ascent + fm0.descent) / 2f, txtPaint
                )
            }
        }
        y += chH + vGap

        // ---- what he wrote, so the two can be compared ----
        txtPaint.textSize = keyH * 0.26f
        txtPaint.color = theme.dim
        wrapText(canvas, fixSource, left + pad, right - pad, y + dp(2f), 2)
        y += keyH * 0.95f

        if (fixBusy) {
            rf.set(left, y, right, y + keyH * 0.85f - dp(6f))
            bgPaint.color = theme.key
            canvas.drawRoundRect(rf, dp(10f), dp(10f), bgPaint)
            txtPaint.textSize = keyH * 0.30f
            txtPaint.color = theme.go
            val fm2 = txtPaint.fontMetrics
            canvas.drawText("يشتغل…", rf.centerX(),
                rf.centerY() - (fm2.ascent + fm2.descent) / 2f, txtPaint)
            y += keyH * 0.85f
        }

        if (fixError.isNotEmpty()) {
            rf.set(left, y, right, y + keyH * 0.90f - dp(6f))
            bgPaint.color = theme.key
            canvas.drawRoundRect(rf, dp(10f), dp(10f), bgPaint)
            txtPaint.textSize = keyH * 0.28f
            txtPaint.color = theme.dim
            val fm3 = txtPaint.fontMetrics
            canvas.drawText(ellipsize(fixError, right - left - pad * 2), rf.centerX(),
                rf.centerY() - (fm3.ascent + fm3.descent) / 2f, txtPaint)
            y += keyH * 0.90f
        }

        // ---- the suggested replies, each one a row that types itself ----
        fixReplyRects.clear()
        if (fixReplies.isNotEmpty()) {
            txtPaint.textSize = keyH * 0.29f
            for (i in fixReplies.indices) {
                val rowH = fixReplyRowH(i)
                rf.set(left, y, right, y + rowH)
                fixReplyRects.add(RectF(rf))
                bgPaint.color =
                    if (fixPressed == -60 - i) theme.keyDown
                    else theme.key
                canvas.drawRoundRect(rf, dp(10f), dp(10f), bgPaint)
                txtPaint.textSize = keyH * 0.29f
                txtPaint.color = theme.text
                wrapText(
                    canvas, fixReplies[i], left + pad, right - pad, y + dp(8f),
                    fixReplyLines.getOrNull(i) ?: 2
                )
                y += rowH + dp(6f)
            }
        }

        if (fixResult.isNotEmpty()) {
            val boxH = fixResultBoxH()
            rf.set(left, y, right, y + boxH)
            bgPaint.color = theme.key
            canvas.drawRoundRect(rf, dp(10f), dp(10f), bgPaint)
            txtPaint.textSize = keyH * 0.30f
            txtPaint.color = theme.text
            wrapText(canvas, fixResult, left + pad, right - pad, y + dp(10f), fixResultLines)
            y += boxH + dp(6f)

            // ---- apply / cancel ----
            val bh = keyH * 0.95f - dp(8f)
            val half = (right - left - dp(6f)) / 2f
            fixApplyRect.set(left, y, left + half, y + bh)
            fixCancelRect.set(right - half, y, right, y + bh)
            txtPaint.textSize = keyH * 0.30f
            val fm4 = txtPaint.fontMetrics

            bgPaint.color = if (fixPressed == -50) theme.onBg else theme.go
            canvas.drawRoundRect(fixApplyRect, dp(10f), dp(10f), bgPaint)
            txtPaint.color = theme.goIcon
            canvas.drawText("استبدل", fixApplyRect.centerX(),
                fixApplyRect.centerY() - (fm4.ascent + fm4.descent) / 2f, txtPaint)

            bgPaint.color = if (fixPressed == -51) theme.keyDown else theme.key
            canvas.drawRoundRect(fixCancelRect, dp(10f), dp(10f), bgPaint)
            txtPaint.color = theme.text
            canvas.drawText("لا", fixCancelRect.centerX(),
                fixCancelRect.centerY() - (fm4.ascent + fm4.descent) / 2f, txtPaint)
        } else {
            fixApplyRect.setEmpty()
            fixCancelRect.setEmpty()
        }
    }

    /** Draws [text] over at most [maxLines] lines, breaking on spaces. */
    private fun wrapText(
        canvas: Canvas, text: String, left: Float, right: Float, top: Float, maxLines: Int
    ) {
        val avail = right - left
        val fm = txtPaint.fontMetrics
        val lh = (fm.descent - fm.ascent) * 1.08f
        val words = text.split(' ')
        val line = StringBuilder()
        var n = 0
        var y = top - fm.ascent
        for (wd in words) {
            val probe = if (line.isEmpty()) wd else line.toString() + " " + wd
            if (txtPaint.measureText(probe) <= avail) {
                line.setLength(0); line.append(probe); continue
            }
            if (line.isEmpty()) { line.append(wd); continue }
            n++
            if (n >= maxLines) {
                canvas.drawText(ellipsize(line.toString() + " " + wd, avail),
                    (left + right) / 2f, y, txtPaint)
                return
            }
            canvas.drawText(line.toString(), (left + right) / 2f, y, txtPaint)
            y += lh
            line.setLength(0); line.append(wd)
        }
        if (line.isNotEmpty()) canvas.drawText(
            ellipsize(line.toString(), avail), (left + right) / 2f, y, txtPaint)
    }

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
                val rec = TOOL_ICONS[i] == Ico.MIC && listening
                if (rec) {
                    bgPaint.color = REC
                    canvas.drawCircle(tcx, cy, micW * 0.46f, bgPaint)
                }
                // the faces icon is lit while he is on that page, so the strip says
                // where he is and the same tap takes him back
                val here = TOOL_ICONS[i] == Ico.SMILE && page == Pages.EMOJI
                if (here) {
                    bgPaint.color = theme.go
                    canvas.drawCircle(tcx, cy, micW * 0.46f, bgPaint)
                }
                icoPaint.color = when {
                    rec -> 0xFFFFFFFF.toInt()
                    here -> theme.goIcon
                    else -> theme.outer
                }
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

    /** Evenly spaced circles at the end of the strip nearest the gear. */
    private fun layoutTools(zoneLeft: Float, zoneRight: Float, top: Float, bottom: Float) {
        toolRects.clear()
        val step = micW + dp(3f)
        for (i in TOOL_ICONS.indices) {
            val cx = zoneRight - micW / 2f - i * step
            toolRects.add(RectF(cx - micW / 2f, top, cx + micW / 2f, bottom))
        }
    }

    /** The language bar that stands in for the strip while translating. */
    /**
     * Back, one direction chip, and the action.
     *
     * Two full-width language pills and a swap arrow took the whole row to say
     * something that fits in a chip, and left nowhere to show the translation —
     * so the box below it showed what he had just typed, which he could already
     * see in the message. The chip carries both languages; a tap swaps them and
     * a hold opens the full list.
     */
    private fun drawTransBar(canvas: Canvas, w: Float) {
        val top = zonePad + panelPadTop
        val bottom = top + suggH
        val cy = (top + bottom) / 2f
        val left = zonePad + sideMargin
        val right = w - zonePad - sideMargin

        val h = suggH * 0.80f
        val y = cy - h / 2f
        val closeW = suggH * 0.86f

        trCloseRect.set(right - closeW, cy - closeW / 2f, right, cy + closeW / 2f)

        txtPaint.typeface = arFont
        txtPaint.textSize = suggH * 0.30f
        val fm = txtPaint.fontMetrics
        val base = cy - (fm.ascent + fm.descent) / 2f

        // the action sizes itself to its word, and the chip takes what is left
        val goLabel = "ترجمة"
        val goW = (txtPaint.measureText(goLabel) + dp(30f)).coerceAtLeast(suggH * 1.5f)
        trGoRect.set(left, y, left + goW, y + h)

        val chipRight = trCloseRect.left - dp(6f)
        val chipLeft = (trGoRect.right + dp(6f)).coerceAtMost(chipRight - dp(40f))
        trSrcRect.set(chipLeft, y, chipRight, y + h)

        // ---- chip: "عربي ⇄ إنجليزي" ----
        bgPaint.color = if (pressedZone == -10) theme.keyDown else theme.key
        canvas.drawRoundRect(trSrcRect, dp(10f), dp(10f), bgPaint)

        val srcName = shortLang(Store.kbTrSrc)
        val dstName = shortLang(Store.kbTrDst)
        val arrowW = suggH * 0.34f
        val gapW = dp(6f)
        val totalW = txtPaint.measureText(srcName) + txtPaint.measureText(dstName) + arrowW + gapW * 2
        var cx = trSrcRect.centerX() + totalW / 2f

        txtPaint.color = theme.text
        txtPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(srcName, cx, base, txtPaint)
        cx -= txtPaint.measureText(srcName) + gapW
        icoPaint.color = theme.go
        icoPaint.strokeWidth = dp(1.6f)
        drawIcon(canvas, Ico.SWAP, cx - arrowW / 2f, cy, arrowW)
        cx -= arrowW + gapW
        canvas.drawText(dstName, cx, base, txtPaint)
        txtPaint.textAlign = Paint.Align.CENTER

        // ---- action: lit only once there is something to commit ----
        val armed = transOut.isNotEmpty()
        bgPaint.color = when {
            !armed -> theme.keyDark
            pressedZone == -14 -> theme.onBg
            else -> theme.go
        }
        canvas.drawRoundRect(trGoRect, dp(10f), dp(10f), bgPaint)
        txtPaint.color = if (armed) theme.goIcon else theme.dim
        canvas.drawText(goLabel, trGoRect.centerX(), base, txtPaint)

        // ---- back ----
        bgPaint.color = if (pressedZone == -13) theme.keyDown else theme.key
        canvas.drawCircle(trCloseRect.centerX(), cy, closeW * 0.46f, bgPaint)
        icoPaint.color = theme.outer
        icoPaint.strokeWidth = dp(1.7f)
        drawIcon(canvas, Ico.BACK, trCloseRect.centerX(), cy, suggH * 0.34f)
    }

    /** Names have to fit a chip, so the long ones lose their tail. */
    private fun shortLang(code: String): String {
        val n = Tr.nameOf(code)
        return if (n.length <= 9) n else n.substring(0, 8) + "…"
    }

    private fun drawChip(canvas: Canvas, r: RectF, text: String, down: Boolean, base: Float) {
        bgPaint.color = if (down) theme.keyDown else theme.key
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, bgPaint)
        txtPaint.color = theme.text
        canvas.drawText(ellipsize(text, r.width() - dp(14f)), r.centerX(), base, txtPaint)
    }

    /**
     * What he is typing, with a dot for whether its translation is ready.
     *
     * The message field stays empty until he presses the action, so this line
     * is the only place his sentence exists while he writes it — it has to show
     * the words themselves, not the translation of them.
     *
     * The dot carries the part he cannot see: grey idle, amber while the engine
     * works, green once there is a finished translation behind the button.
     */
    private fun drawTransBox(canvas: Canvas, w: Float) {
        val left = zonePad + sideMargin
        val right = w - zonePad - sideMargin
        val top = zonePad + panelPadTop + suggH + vGap
        trBoxRect.set(left, top, right, top + transH)

        bgPaint.color = if (transBoxDown) theme.keyDown else theme.key
        canvas.drawRoundRect(trBoxRect, dp(10f), dp(10f), bgPaint)

        val working = transText.trim().isNotEmpty() && transOut.isEmpty()
        val dotC = when {
            transOut.isNotEmpty() -> OK_DOT
            working -> WORK_DOT
            else -> theme.dim
        }
        val dotR = dp(3.5f)
        val dotX = right - dp(14f)
        bgPaint.color = dotC
        canvas.drawCircle(dotX, trBoxRect.centerY(), dotR, bgPaint)

        // There is no caret and no selection to drag in a line painted onto a
        // canvas, so the only way to empty it was one backspace per letter. The
        // button appears only when there is something to clear.
        val hasText = transText.isNotEmpty()
        if (hasText) {
            val r = trBoxRect.height() * 0.30f
            val cxc = left + dp(15f)
            trClearRect.set(
                cxc - r - dp(5f), trBoxRect.centerY() - r - dp(5f),
                cxc + r + dp(5f), trBoxRect.centerY() + r + dp(5f)
            )
            bgPaint.color = if (transClearDown) theme.go else theme.keyDark
            canvas.drawCircle(cxc, trBoxRect.centerY(), r, bgPaint)
            edgePaint.style = Paint.Style.STROKE
            edgePaint.strokeWidth = dp(1.5f)
            edgePaint.color = if (transClearDown) theme.goIcon else theme.dim
            val a = r * 0.42f
            canvas.drawLine(cxc - a, trBoxRect.centerY() - a, cxc + a, trBoxRect.centerY() + a, edgePaint)
            canvas.drawLine(cxc + a, trBoxRect.centerY() - a, cxc - a, trBoxRect.centerY() + a, edgePaint)
        } else {
            trClearRect.setEmpty()
        }

        txtPaint.typeface = arFont
        txtPaint.textSize = keyH * 0.31f
        val fm = txtPaint.fontMetrics
        val base = trBoxRect.centerY() - (fm.ascent + fm.descent) / 2f

        val typed = transText.trim()
        val shown = when {
            typed.isNotEmpty() -> transText
            transStatus.isNotEmpty() -> transStatus
            else -> "اكتب هنا — أو دوس للّصق"
        }
        txtPaint.color = if (typed.isNotEmpty()) theme.text else theme.dim
        val textLeft = if (hasText) trClearRect.right + dp(6f) else left + dp(14f)
        val textRight = dotX - dotR - dp(8f)
        // the tail is what matters while typing, so a long line scrolls from the end
        canvas.drawText(
            tailFit(shown, textRight - textLeft),
            (textLeft + textRight) / 2f, base, txtPaint
        )
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

    /**
     * [rest] for a key at rest, or the pressed colour on its way back to it for
     * the one just released. Eased out, so it leaves quickly and lands softly
     * rather than sliding at one speed.
     */
    private fun restOrFading(k: Key, rest: Int): Int {
        if (k !== fadeKey) return rest
        val dt = android.os.SystemClock.uptimeMillis() - fadeAt
        if (dt >= FADE_MS) { fadeKey = null; return rest }
        val t = dt / FADE_MS.toFloat()
        val e = 1f - (1f - t) * (1f - t)        // ease-out
        postInvalidateOnAnimation(
            (k.x - 2f).toInt(), (k.y - 2f).toInt(),
            (k.x + k.w + 2f).toInt(), (k.y + k.h + 2f).toInt()
        )
        return mix(theme.keyDown, rest, e)
    }

    /** [a] towards [b] by [t]. */
    private fun mix(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt().coerceIn(0, 255),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt().coerceIn(0, 255),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt().coerceIn(0, 255),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt().coerceIn(0, 255)
    )

    private fun drawKey(canvas: Canvas, k: Key) {
        rf.set(k.x, k.y, k.x + k.w, k.y + k.h)

        val isOn = k.code == Code.SHIFT && shift > 0
        val rest = when {
            isOn -> theme.onBg
            k.style == Style.GO -> theme.go
            k.style == Style.DARK -> theme.keyDark
            else -> theme.key
        }
        keyPaint.color = keyCol(
            if (k === pressed) theme.keyDown else restOrFading(k, rest)
        )
        val r = rad

        // The halo: the enter key is the one coloured thing on the board, so in
        // a dark theme it gets to cast light. Three rings, widest and faintest
        // first, which is a blur that costs three fills.
        if (theme.glow != 0 && k.style == Style.GO) {
            val base = Color.alpha(theme.glow)
            for (i in 3 downTo 1) {
                keyPaint.color = Color.argb(
                    (base * (0.30f - 0.07f * i)).toInt().coerceIn(0, 255),
                    Color.red(theme.glow), Color.green(theme.glow), Color.blue(theme.glow)
                )
                val sp = dp(i * 2.2f)
                rf.set(k.x - sp, k.y - sp * 0.6f, k.x + k.w + sp, k.y + k.h + sp)
                canvas.drawRoundRect(rf, r + sp, r + sp, keyPaint)
            }
            rf.set(k.x, k.y, k.x + k.w, k.y + k.h)
            keyPaint.color = keyCol(theme.go)
        }

        // A white key on a white panel. The shadow is laid down first, in three
        // thin passes rather than one blurred one — a blur needs a software layer
        // and this is drawn forty times a frame, where three fills cost nothing.
        if (theme.lift != 0 && k !== pressed) {
            val base = Color.alpha(theme.lift)
            for (i in 3 downTo 1) {
                keyPaint.color = Color.argb(
                    (base * (0.34f + 0.22f * (4 - i))).toInt().coerceIn(0, 255),
                    Color.red(theme.lift), Color.green(theme.lift), Color.blue(theme.lift)
                )
                rf.set(k.x, k.y + dp(i * 0.55f), k.x + k.w, k.y + k.h + dp(i * 0.55f))
                canvas.drawRoundRect(rf, r, r, keyPaint)
            }
            rf.set(k.x, k.y, k.x + k.w, k.y + k.h)
            keyPaint.color = keyCol(restOrFading(k, rest))
        }

        // A letter key with a gradient in it: lighter at the top, a shade deeper
        // at the bottom. One shader for the key height, slid down to each key,
        // rather than forty of them a frame.
        val grad = theme.keyTop != 0 && k !== pressed && !isOn &&
            k.style != Style.GO && k.style != Style.DARK
        if (grad) {
            if (keyShader == null || keyShaderH != k.h || keyShaderId != theme.id) {
                keyShaderH = k.h
                keyShaderId = theme.id
                keyShader = LinearGradient(
                    0f, 0f, 0f, k.h, theme.keyTop, theme.keyBottom, Shader.TileMode.CLAMP
                )
            }
            keyMatrix.setTranslate(0f, k.y)
            keyShader!!.setLocalMatrix(keyMatrix)
            keyPaint.shader = keyShader
            keyPaint.alpha = Color.alpha(keyCol(theme.key))
        }
        canvas.drawRoundRect(rf, r, r, keyPaint)
        if (grad) {
            keyPaint.shader = null
            keyPaint.alpha = 255
        }

        // The carve: a dark line inside the top edge and a light one just under the
        // key. Without the light underneath the key only looks dirty along its top.
        if (theme.carve != 0 && k !== pressed) {
            canvas.save()
            path.reset()
            path.addRoundRect(rf, r, r, Path.Direction.CW)
            canvas.clipPath(path)
            edgePaint.style = Paint.Style.STROKE
            edgePaint.strokeWidth = dp(1.1f)
            edgePaint.color = theme.carve
            canvas.drawLine(k.x, k.y + dp(0.55f), k.x + k.w, k.y + dp(0.55f), edgePaint)
            canvas.restore()
            if (theme.carveLight != 0) {
                edgePaint.color = theme.carveLight
                canvas.drawLine(
                    k.x + r, k.y + k.h + dp(0.6f), k.x + k.w - r, k.y + k.h + dp(0.6f),
                    edgePaint
                )
            }
        }

        // The glass sheen: one bright line along the top inside edge, clipped to
        // the key's own corners so it stops where the rounding starts, the way
        // light actually catches an edge. This single line is what reads as
        // glass — the transparency on its own just looks faded.
        if (theme.sheen != 0 && k !== pressed) {
            // It sat on the key's top edge at full strength from end to end,
            // which does not read as light on an edge — it reads as a white bar
            // laid across the key. Light does not stop dead at a corner: it is
            // brightest in the middle and gone by the ends. So the line moved
            // down off the edge, pulled in past the rounding, and now fades out
            // at both ends.
            val inset = r * 0.9f + dp(1f)
            if (k.w > inset * 2f + dp(6f)) {
                if (sheenShader == null || sheenW != k.w || sheenId != theme.id) {
                    sheenW = k.w
                    sheenId = theme.id
                    val clear = theme.sheen and 0x00FFFFFF
                    sheenShader = LinearGradient(
                        0f, 0f, k.w, 0f,
                        intArrayOf(clear, theme.sheen, theme.sheen, clear),
                        floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP
                    )
                }
                keyMatrix.setTranslate(k.x, 0f)
                sheenShader!!.setLocalMatrix(keyMatrix)
                edgePaint.style = Paint.Style.STROKE
                edgePaint.strokeWidth = dp(1.1f)
                edgePaint.shader = sheenShader
                edgePaint.alpha = Color.alpha(keyCol(theme.key))
                val y = k.y + dp(1.8f)
                canvas.drawLine(k.x + inset, y, k.x + k.w - inset, y, edgePaint)
                edgePaint.shader = null
                edgePaint.alpha = 255
            }
        }

        if (theme.edge != 0) {
            edgePaint.style = Paint.Style.STROKE
            edgePaint.strokeWidth = dp(1f)
            edgePaint.color = theme.edge
            val h = dp(0.5f)
            rf.set(k.x + h, k.y + h, k.x + k.w - h, k.y + k.h - h)
            canvas.drawRoundRect(rf, r, r, edgePaint)
            rf.set(k.x, k.y, k.x + k.w, k.y + k.h)
        }

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
            // the return arrow sits on the widest key in the row and reads large
            // there at the size the rest of the icons want
            val size = if (k.icon == Ico.ENTER) keyH * 0.40f else keyH * 0.46f
            drawIcon(canvas, k.icon, rf.centerX(), rf.centerY(), size)
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
            Ico.WAND -> {
                // A four-pointed star, stroked at the same weight as its
                // neighbours. It is drawn rather than tinted on purpose: a
                // coloured button in a row of outlines reads as an advert.
                fun star(ox: Float, oy: Float, r: Float) {
                    val w = r * 0.30f
                    path.reset()
                    path.moveTo(ox, oy - r)
                    path.cubicTo(ox + w, oy - w, ox + w, oy - w, ox + r, oy)
                    path.cubicTo(ox + w, oy + w, ox + w, oy + w, ox, oy + r)
                    path.cubicTo(ox - w, oy + w, ox - w, oy + w, ox - r, oy)
                    path.cubicTo(ox - w, oy - w, ox - w, oy - w, ox, oy - r)
                    path.close()
                    canvas.drawPath(path, icoPaint)
                }
                star(cx - s * 0.16f, cy + s * 0.10f, s * 0.80f)
                star(cx + s * 0.62f, cy - s * 0.60f, s * 0.34f)
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

    /**
     * Holding the switch key.
     *
     * The tap already changed the page the instant his finger landed — that is the
     * whole point of the key — so the hold cannot work out where to go from where it
     * finds itself. It remembers the page the finger came down on and decides from
     * that, which makes a hold land in the same place every time.
     */
    private var cycleArmed = false
    private var cycleFrom = Pages.LETTERS
    private val cycleHoldRunnable = Runnable {
        if (!cycleArmed) return@Runnable
        cycleArmed = false
        pressed = null
        val to = KbLayout.holdPage(cycleFrom)
        if (to != page) {
            try {
                performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            } catch (_: Throwable) {}
            listener?.onPage(to)
        }
    }

    private fun cancelCycleHold() {
        if (!cycleArmed) return
        cycleArmed = false
        handler.removeCallbacks(cycleHoldRunnable)
    }

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
                    if (pressedZone == -10) {
                        clipArmed = true
                        handler.postDelayed(langHoldRunnable, 420)
                    }
                    if (pressedZone != -1) invalidateStrip()
                    return true
                }

                if (toolsOpen) setToolsOpen(false)

                // The rewrite panel stands where the keys do. Without this a tap
                // on one of its choices fell through to find(), which has a
                // nearest-key fallback and so always answers with a letter.
                if (fixOn) {
                    fixPressed = -99
                    for (i in fixRects.indices) {
                        if (fixRects[i].contains(x, y)) { fixPressed = i; break }
                    }
                    if (fixPressed == -99) {
                        for (i in fixReplyRects.indices) {
                            if (fixReplyRects[i].contains(x, y)) { fixPressed = -60 - i; break }
                        }
                    }
                    if (fixPressed == -99 && !fixApplyRect.isEmpty &&
                        fixApplyRect.contains(x, y)) fixPressed = -50
                    if (fixPressed == -99 && !fixCancelRect.isEmpty &&
                        fixCancelRect.contains(x, y)) fixPressed = -51
                    invalidate()
                    return true
                }

                // the translate line is not a key and not the strip; without this
                // a tap on it fell through to the nearest letter
                if (transOn && trBoxRect.contains(x, y)) {
                    if (!trClearRect.isEmpty && trClearRect.contains(x, y)) {
                        transClearDown = true
                    } else {
                        transBoxDown = true
                    }
                    invalidate()
                    return true
                }

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
                if (k.code == Code.CYCLE) {
                    cycleArmed = true
                    cycleFrom = page
                    handler.postDelayed(cycleHoldRunnable, 320)
                }
                // a key with alternates waits before typing: the character is only
                // committed once it is clear this is a tap and not a hold
                if (altsOn && KbLayout.altsFor(k) != null) {
                    altArmed = true
                    handler.postDelayed(altRunnable, 300)
                }
                if (!pressFx) pressed = null
                if (fastKeys) {
                    firedOnDown = true
                    firedKey = k
                    fire(k, x, y)
                } else {
                    firedOnDown = false
                    firedKey = null
                }
                if (pressFx) invalidateKey(k)
                if (k.code == Code.DEL) {
                    repeating = true
                    repeatTicks = 0
                    listener?.onRepeatState(true)
                    // the one guard that costs nothing when the press is meant
                    handler.postDelayed(repeatRunnable, 400)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (fixOn) return true
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
                if (k2.code == Code.DEL || k2.code == Code.SHIFT ||
                    k2.code == Code.CYCLE
                ) return true
                fire(k2, px, py)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> return true

            MotionEvent.ACTION_MOVE -> {
                if (fixOn) return true
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
                        handler.removeCallbacks(langHoldRunnable)
                    }
                    if (suggDragging && suggMax > 0f) {
                        suggScroll = (stripScroll0 - dx).coerceIn(0f, suggMax)
                        invalidateStrip()
                    }
                    return true
                }
                if (altList.isNotEmpty()) { moveAlts(x, y); return true }
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
                if (cycleArmed && (Math.abs(x - downX) > dp(12f) ||
                        Math.abs(y - downY) > dp(12f))
                ) cancelCycleHold()
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
                cancelCycleHold()

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

                if (fixOn) {
                    val was = fixPressed
                    fixPressed = -99
                    invalidate()
                    when {
                        was >= 0 && fixRects.getOrNull(was)?.contains(x, y) == true ->
                            listener?.onFixPick(was)
                        was <= -60 && fixReplyRects.getOrNull(-60 - was)
                            ?.contains(x, y) == true -> listener?.onFixReply(-60 - was)
                        was == -50 && fixApplyRect.contains(x, y) -> listener?.onFixApply()
                        was == -51 && fixCancelRect.contains(x, y) -> listener?.onFixClose()
                    }
                    return true
                }

                if (transClearDown) {
                    transClearDown = false
                    invalidate()
                    if (!trClearRect.isEmpty && trClearRect.contains(x, y)) {
                        listener?.onTransClear()
                    }
                    return true
                }

                if (transBoxDown) {
                    transBoxDown = false
                    invalidate()
                    if (trBoxRect.contains(x, y)) listener?.onTransPaste()
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
                    handler.removeCallbacks(langHoldRunnable)
                    invalidateStrip()
                    if (dragged || hadHold) return true
                    when {
                        z == -2 -> setToolsOpen(!toolsOpen)
                        z == -3 -> listener?.onClipTap()
                        z <= -4 && z >= -(3 + TOOL_ICONS.size) -> {
                            setToolsOpen(false)
                            listener?.onTool(-(z + 4))
                        }
                        // a tap flips the pair; the hold that opens the full list
                        // has already fired by here and set hadHold
                        z == -10 -> listener?.onTransSwap()
                        z == -13 -> listener?.onTransClose()
                        z == -14 -> listener?.onTransGo()
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
                if (!firedOnDown && k != null && k.hitT(x, y)) {
                    fire(k, x, y)
                } else if (firedOnDown) {
                    // Typing on down is what makes the letter appear under the finger
                    // instead of after it. The cost is that a finger landing a little
                    // off has already committed, where a keyboard that waits for the
                    // lift quietly forgives it. So the lift is checked too: settle on
                    // a different letter and that is the one that stays.
                    val fk = firedKey
                    if (fk != null && fk.code == Code.CHAR && fk.out.isNotEmpty() &&
                        (Math.abs(x - downX) > dp(5f) || Math.abs(y - downY) > dp(5f))
                    ) {
                        val now = find(x, y)
                        if (now != null && now !== fk &&
                            now.code == Code.CHAR && now.out.isNotEmpty()
                        ) {
                            lastNear = neighbours(now, x, y)
                            listener?.onReplaceChar(now.out)
                        }
                    }
                }
                firedKey = null
                firedOnDown = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (fixPressed != -99) { fixPressed = -99; invalidate() }
                if (transBoxDown || transClearDown) {
                    transBoxDown = false
                    transClearDown = false
                    invalidate()
                }
                firedKey = null
                cancelCycleHold()
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

        altShow = list.map { carrier(it) }

        // sixteen marks will not sit in one row, so anything past eight wraps
        altCols = if (list.size > 8) (list.size + 1) / 2 else list.size
        altRows = if (list.size > 8) 2 else 1

        txtPaint.typeface = if (k.arabic) arFont else enFont
        txtPaint.textSize = keyH * (if (altRows > 1) 0.46f else 0.52f)
        val grid = altRows > 1
        var cell = keyH * (if (grid) 0.74f else 0.95f)
        for (a in altShow) cell = max(cell, txtPaint.measureText(a) + dp(if (grid) 8f else 14f))
        val padding = dp(4f)
        // A bubble that reaches both edges of the keyboard reads as another row of
        // keys rather than something floating over them. It is held well inside,
        // and the cell gives way before the row does.
        val room = (width - zonePad * 2f) * 0.84f - padding * 2f
        if (cell * altCols > room) cell = room / altCols
        altCellW = cell
        altCellH = keyH * (if (grid) 0.88f else 1.02f)

        val w = cell * altCols + padding * 2f
        val h = altCellH * altRows + padding * 2f
        // one row sits over its key; a grid is too wide for that and is centred
        val cx = if (grid) width / 2f else k.x + k.w / 2f
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

    private fun moveAlts(x: Float, y: Float) {
        if (altList.isEmpty()) return
        val padding = dp(4f)
        val col = ((x - altRect.left - padding) / altCellW).toInt()
            .coerceIn(0, altCols - 1)
        val row = ((y - altRect.top - padding) / altCellH).toInt()
            .coerceIn(0, altRows - 1)
        val i = (row * altCols + col).coerceIn(0, altList.size - 1)
        if (i != altSel) { altSel = i; invalidate() }
    }

    /** A combining mark drawn on its own is a smudge; it gets a circle to sit on. */
    private fun carrier(a: String): String {
        if (a.length != 1) return a
        val c = a[0]
        val mark = (c in '\u064B'..'\u0652') || c == '\u0670' ||
            c == '\u0653' || c == '\u0654' || c == '\u0655'
        return if (mark) "\u25CC" + a else a
    }

    /** Lifts the bubble. Returns what should be typed, or null. */
    private fun closeAlts(commit: Boolean): String? {
        if (altList.isEmpty()) return null
        val out = if (commit) altList.getOrNull(altSel) else null
        altKey = null
        altList = emptyList()
        altShow = emptyList()
        altSel = 0
        invalidate()
        return out
    }

    /**
     * The letter lifted above the finger while a key is held, the way the iPhone
     * does it.
     *
     * The finger covers the key it is pressing, so without this there is no way
     * to see a slip until the letter is already in the text. Drawn only for
     * letter keys, and only while nothing else owns the screen.
     *
     * The top row has nothing above it to draw into, so there the bubble sits
     * over the key itself rather than being clipped away.
     */
    private fun drawPeek(canvas: Canvas) {
        if (!pressFx || !peekOn) return
        if (altList.isNotEmpty() || blank || transOn) return
        val k = pressed ?: return
        if (k.code != Code.CHAR || k.out.isEmpty()) return

        val w = k.w * 1.32f
        val h = k.h * 1.16f
        val gap = dp(5f)
        var left = k.x + k.w / 2f - w / 2f
        left = left.coerceIn(dp(2f), width - w - dp(2f))
        var top = k.y - gap - h
        if (top < dp(2f)) top = k.y + (k.h - h) / 2f

        val r = dp(11f)
        rf.set(left - dp(1f), top, left + w + dp(1f), top + h + dp(2.5f))
        bgPaint.color = theme.keyDark
        canvas.drawRoundRect(rf, r, r, bgPaint)
        rf.set(left, top, left + w, top + h)
        bgPaint.color = theme.key
        canvas.drawRoundRect(rf, r, r, bgPaint)

        txtPaint.typeface = if (k.arabic) arFont else enFont
        txtPaint.textSize = keyH * 0.62f
        txtPaint.color = theme.text
        val fm = txtPaint.fontMetrics
        canvas.drawText(
            k.out, left + w / 2f,
            top + h / 2f - (fm.ascent + fm.descent) / 2f, txtPaint
        )
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
        for (i in altShow.indices) {
            val col = i % altCols
            val row = i / altCols
            val l = altRect.left + padding + altCellW * col
            val t = altRect.top + padding + altCellH * row
            if (i == altSel) {
                rf.set(l + dp(1f), t + dp(1f), l + altCellW - dp(1f), t + altCellH - dp(1f))
                bgPaint.color = theme.go
                canvas.drawRoundRect(rf, dp(8f), dp(8f), bgPaint)
            }
            txtPaint.color = if (i == altSel) theme.goIcon else theme.text
            canvas.drawText(
                altShow[i], l + altCellW / 2f,
                t + altCellH / 2f - (fm.ascent + fm.descent) / 2f, txtPaint
            )
        }
    }

    /** -3 the clipboard key, -2 the microphone, 0.. a suggestion, -1 nothing. */
    private fun stripZone(x: Float): Int {
        val left = zonePad
        val right = width - zonePad

        if (transOn) {
            if (trCloseRect.left - dp(4f) <= x) return -13
            if (trSrcRect.left - dp(4f) <= x) return -10
            if (x <= trGoRect.right + dp(4f)) return -14
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

    /**
     * The key that was just let go, and when.
     *
     * The press itself stays instant — he asked for that and was right. It is
     * the release that used to snap: the grey vanished in one frame and the
     * whole keyboard felt like a row of light switches. Letting the pressed
     * colour fade out over a tenth of a second costs nothing, changes no
     * timing, and is most of what "smooth" actually means.
     */
    private var fadeKey: Key? = null
    private var fadeAt = 0L
    private val FADE_MS = 110L

    private fun invalidateKey(k: Key?) {
        if (k != null && k !== pressed && pressFx) {
            fadeKey = k
            fadeAt = android.os.SystemClock.uptimeMillis()
        }
        if (k == null) { invalidate(); return }
        // The preview bubble is drawn above the key and wider than it, so the
        // repainted area has to cover where it lands or it leaves a trail.
        val padX = if (peekOn) k.w * 0.22f + 4f else 2f
        val padTop = if (peekOn) k.h * 1.35f else 2f
        invalidate(
            (k.x - padX).toInt(), (k.y - padTop).toInt(),
            (k.x + k.w + padX).toInt(), (k.y + k.h + 2f).toInt()
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
        // The corrected point first. If the correction pushes the tap off every
        // key — at the very top row, say — the raw point still counts, so the
        // offset can never turn a real tap into nothing.
        if (fingerY > 0) {
            val lift = keyH * (fingerY / 100f)
            for (row in rows) for (k in row) if (!k.spacer && k.hitT(x, y - lift)) return k
        }
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

    private fun neighbours(k: Key, tx: Float = Float.NaN, ty: Float = Float.NaN): String {
        if (k.code != Code.CHAR || k.out.length != 1) return ""
        // Measured from where the finger actually landed.
        //
        // This used to measure from the middle of the key, which threw away the
        // one thing only the keyboard knows: a tap two pixels inside 'س' with
        // 'ش' against that edge produced exactly the same list as a tap dead
        // centre. The corrector was being handed a blind guess where a reading
        // existed.
        //
        // Clamped into the key, because find() falls back to the nearest key for
        // a tap that lands outside the rows, and an unclamped point there would
        // drag the whole list toward the edge of the keyboard.
        val useTouch = !tx.isNaN() && !ty.isNaN()
        val lift = if (fingerY > 0) k.h * (fingerY / 100f) else 0f
        val cx = if (useTouch) tx.coerceIn(k.x, k.x + k.w) else k.x + k.w / 2f
        val cy = if (useTouch) (ty - lift).coerceIn(k.y, k.y + k.h) else k.y + k.h / 2f
        // A key is wider than it is far from the row above, so a reach measured in
        // key widths reached sideways and nowhere else: the letters directly above
        // and below a finger were never offered to the corrector at all.
        //
        // The first attempt at this used h * 1.25 and did nothing whatsoever. On a
        // 411dp screen a key is 31.5 x 44dp and the rows are 55.7dp apart, so that
        // reach came to 55.0dp and missed the key directly above by seven tenths of
        // a millimetre. Measured against five thousand slips it was worth nothing;
        // at 1.45 the same change is worth forty-seven points on words with two
        // letters off by a key. A number reasoned about is not a number measured.
        val reach = max(k.w * 1.45f, k.h * 1.45f)
        val reach2 = reach * reach
        val found = ArrayList<Pair<Float, String>>(8)
        for (row in rows) for (o in row) {
            if (o.spacer || o === k) continue
            if (o.code != Code.CHAR || o.out.length != 1) continue
            val dx = (o.x + o.w / 2f) - cx
            val dy = (o.y + o.h / 2f) - cy
            val d2 = dx * dx + dy * dy
            if (d2 <= reach2) found.add(d2 to o.out)
        }
        if (found.isEmpty()) return ""
        found.sortBy { it.first }
        val sb = StringBuilder(6)
        for (i in 0 until minOf(6, found.size)) sb.append(found[i].second)
        return sb.toString()
    }

    private fun fire(k: Key, tx: Float = Float.NaN, ty: Float = Float.NaN) {
        lastNear = if (k.code == Code.CHAR) neighbours(k, tx, ty) else ""
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
            Code.CYCLE -> listener?.onPage(KbLayout.nextPage(page))
            else -> if (k.out.isNotEmpty()) listener?.onChar(k.out)
        }
    }
}
