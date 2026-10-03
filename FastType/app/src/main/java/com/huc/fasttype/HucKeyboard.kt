package com.huc.fasttype

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.util.Log
import android.os.Handler
import android.os.Looper

/**
 * The HUC keyboard. Shortcut expansion happens right here, so it needs no
 * accessibility service and cannot be throttled by the system.
 */
class HucKeyboard : InputMethodService(), KeyboardView.Listener, Voice.Sink {

    private var kv: KeyboardView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var voice: Voice? = null
    private var voiceBase = ""
    private var voicePartial = 0
    /** Word the auto-correction just replaced, so one backspace puts it back. */
    private var undoTyped: String? = null
    private var undoFixed: String? = null
    private var repeatingDel = false

    private var arabic = true
    private var shift = 0
    private var page = Pages.LETTERS
    private var lastShiftAt = 0L

    /** Characters typed since the last word break — the shortcut candidate. */
    private val buffer = StringBuilder(32)

    private var pendingShortcut: Shortcut? = null

    override fun onCreate() {
        setTheme(R.style.KbWindow)
        super.onCreate()
        Store.load(this)
        Dict.warm(this)
        UserDict.load(this)
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this)
        v.listener = this
        kv = v
        Store.load(this)
        arabic = Store.kbArabicFirst
        v.arabic = arabic
        v.applySettings()
        v.page = page
        v.rebuild()
        v.post { clearWindowBackground() }
        return v
    }

    /**
     * The panel draws its own rounded top corners, so everything behind it has to be
     * see-through — otherwise the corners show the IME window's own background instead
     * of the app, which is what iOS shows there.
     */
    private fun clearWindowBackground() {
        try {
            val w = window?.window
            w?.setBackgroundDrawable(null)
            w?.setDimAmount(0f)
            w?.decorView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            w?.findViewById<View>(android.R.id.inputArea)
                ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // belt and braces: every container between the panel and the decor view
            var p = kv?.parent
            var depth = 0
            while (p is View && depth < 12) {
                p.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                p = p.parent
                depth++
            }
            kv?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } catch (_: Exception) {
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        clearWindowBackground()
        Dict.warm(this)
        commitDictated()
    }

    /** Types whatever the system voice screen heard while we were off screen. */
    private fun commitDictated() {
        val text = VoiceResult.take() ?: return
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: run { VoiceResult.pending = text; return }
        ic.beginBatchEdit()
        ic.commitText("$text ", 1)
        ic.endBatchEdit()
        buffer.setLength(0)
        refreshSugg()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        voice?.stop()
        kv?.listening = false
        UserDict.save()
    }

    override fun onDestroy() {
        voice?.stop()
        voice = null
        UserDict.save()
        super.onDestroy()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Store.load(this)
        buffer.setLength(0)
        pendingShortcut = null
        shift = 0
        page = Pages.LETTERS
        kv?.let {
            it.applySettings()
            it.shift = 0
            it.page = page
            it.suggText = ""
            it.suggs = emptyList()
            it.rebuild()
        }
        clearWindowBackground()
        commitDictated()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        buffer.setLength(0)
        pendingShortcut = null
    }

    // ---------------- listener ----------------

    override fun onChar(s: String) {
        val ic = currentInputConnection ?: return
        undoTyped = null
        undoFixed = null

        val isBreak = s.length == 1 && isWordBreak(s[0])

        if (isBreak && Store.kbExpand) {
            val hit = matchShortcut()
            if (hit != null) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(hit.trigger.length, 0)
                ic.commitText(hit.phrase + s, 1)
                ic.endBatchEdit()
                buffer.setLength(0)
                feedback()
                refreshSugg()
                afterType()
                return
            }
        }

        if (isBreak && buffer.isNotEmpty() && Store.kbLearn) {
            UserDict.seen(buffer.toString(), arabic)
        }

        if (isBreak && Store.kbCorrect && buffer.isNotEmpty()) {
            val typed = buffer.toString()
            // a spelling he writes himself is his, not a mistake
            val fixed = if (Store.kbLearn && UserDict.isOwn(typed, arabic)) null
            else UserDict.correct(typed, arabic) ?: Dict.correct(typed, arabic)
            if (fixed != null) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(typed.length, 0)
                ic.commitText(fixed + s, 1)
                ic.endBatchEdit()
                undoTyped = typed
                undoFixed = fixed
                buffer.setLength(0)
                feedback()
                refreshSugg()
                afterType()
                return
            }
        }

        ic.commitText(s, 1)

        if (isBreak) buffer.setLength(0)
        else {
            buffer.append(s)
            if (buffer.length > 32) buffer.delete(0, buffer.length - 32)
            if (Store.kbExpandInstant && Store.kbExpand) {
                val hit = matchShortcut()
                if (hit != null) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(hit.trigger.length, 0)
                    ic.commitText(hit.phrase, 1)
                    ic.endBatchEdit()
                    buffer.setLength(0)
                }
            }
        }

        feedback()
        refreshSugg()
        afterType()
    }

    private fun afterType() {
        if (shift == 1 && !arabic && page == Pages.LETTERS) {
            shift = 0
            kv?.shift = 0
            kv?.rebuild()
        }
    }

    override fun onDelete() {
        val ic = currentInputConnection ?: return

        // one backspace right after an auto-correction puts the typed word back
        val t = undoTyped
        val f = undoFixed
        if (t != null && f != null) {
            undoTyped = null
            undoFixed = null
            if (Store.kbLearn) UserDict.keepAsIs(t, arabic)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(f.length + 1, 0)
            ic.commitText(t, 1)
            ic.endBatchEdit()
            buffer.setLength(0)
            buffer.append(t)
            feedback()
            refreshSugg()
            return
        }

        // getSelectedText is a round trip to the other app, so it only runs on the
        // first press — holding backspace must never pay for it
        if (!repeatingDel) {
            val sel = ic.getSelectedText(0)
            if (sel != null && sel.isNotEmpty()) {
                ic.commitText("", 1)
                buffer.setLength(0)
                feedback()
                refreshSugg()
                return
            }
        }

        ic.deleteSurroundingText(1, 0)
        if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
        feedback()
        refreshSugg()
    }

    /** Deletes back to the start of the previous word, for a long backspace hold. */
    override fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        undoTyped = null
        undoFixed = null
        val before = ic.getTextBeforeCursor(48, 0) ?: ""
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1] == ' ') i--
        while (i > 0 && before[i - 1] != ' ' && before[i - 1] != '\n') i--
        val n = (before.length - i).coerceAtLeast(1)
        ic.deleteSurroundingText(n, 0)
        buffer.setLength(0)
        refreshSugg()
    }

    override fun onRepeatState(active: Boolean) {
        repeatingDel = active
        if (!active) refreshSugg()
    }

    override fun onEnter() {
        feedback()
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val action = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnter = (ei?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0

        if (!noEnter && action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        buffer.setLength(0)
        refreshSugg()
    }

    override fun onShift() {
        feedback()
        val now = System.currentTimeMillis()
        shift = if (now - lastShiftAt < 400) 2 else if (shift > 0) 0 else 1
        lastShiftAt = now
        kv?.shift = shift
        kv?.rebuild()
    }

    override fun onLang() {
        feedback()
        arabic = !arabic
        shift = 0
        buffer.setLength(0)
        kv?.let {
            it.arabic = arabic
            it.shift = 0
            it.suggText = ""
            it.rebuild()
        }
    }

    override fun onPage(p: Int) {
        feedback()
        page = p
        kv?.page = p
        kv?.rebuild()
    }

    override fun onSuggestionTap() {
        val hit = pendingShortcut ?: return
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(hit.trigger.length, 0)
        ic.commitText(hit.phrase, 1)
        ic.endBatchEdit()
        buffer.setLength(0)
        refreshSugg()
    }

    override fun onPredictionTap(index: Int) {
        val v = kv ?: return
        if (v.statusOnly) return
        if (pendingShortcut != null) { onSuggestionTap(); return }
        val word = v.suggs.getOrNull(index) ?: return
        if (word.isEmpty()) return
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (buffer.isNotEmpty()) ic.deleteSurroundingText(buffer.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        if (Store.kbLearn) UserDict.seen(word, arabic)
        buffer.setLength(0)
        undoTyped = null
        undoFixed = null
        feedback()
        refreshSugg()
    }

    // ---------------- voice typing ----------------

    override fun onMic() {
        Log.i(Voice.TAG, "mic key pressed")
        val vo = voice ?: Voice(this).also { it.sink = this; voice = it }

        if (vo.isListening) {
            vo.stop()
            kv?.listening = false
            kv?.level = 0f
            showStrip("وقّفت")
            ui.postDelayed({ refreshSugg() }, 900)
            return
        }

        if (!vo.hasPermission()) {
            Log.w(Voice.TAG, "RECORD_AUDIO not granted")
            showStrip("وافق على صلاحية المايك وارجع دوس")
            vo.askPermission()
            return
        }
        // no background service on this phone — use the system's voice screen,
        // which the Google app answers even where it registers no service
        if (!vo.hasRealEngine()) {
            if (vo.screenAvailable()) {
                showStrip("فتحت شاشة الصوت…")
                vo.openScreen(arabic)
            } else {
                showStrip("محتاج تطبيق Google — افتح إعدادات الكيبورد")
            }
            return
        }

        // say something the instant the key is pressed, so a slow or failing
        // engine never looks like a dead button
        showStrip("جاري تشغيل المايك…")
        voiceBase = ""
        voicePartial = 0
        kv?.listening = true
        vo.start(arabic)
    }

    /** Live text while the sentence is still being spoken; it gets replaced. */
    override fun onPartial(text: String) {
        ui.post {
            val ic = currentInputConnection ?: return@post
            ic.beginBatchEdit()
            if (voicePartial > 0) ic.deleteSurroundingText(voicePartial, 0)
            ic.commitText(text, 1)
            ic.endBatchEdit()
            voicePartial = text.length
            showStrip(text)
        }
    }

    /** A finished sentence. The session stays open for the next one. */
    override fun onSegment(text: String) {
        ui.post {
            val ic = currentInputConnection ?: return@post
            ic.beginBatchEdit()
            if (voicePartial > 0) ic.deleteSurroundingText(voicePartial, 0)
            if (text.isNotEmpty()) ic.commitText("$text ", 1)
            ic.endBatchEdit()
            voicePartial = 0
            buffer.setLength(0)
        }
    }

    /** Keeps the live text as written, so the next sentence starts after it. */
    override fun onSegmentEnd() {
        ui.post {
            if (voicePartial > 0) {
                currentInputConnection?.commitText(" ", 1)
                voicePartial = 0
                buffer.setLength(0)
            }
        }
    }

    override fun onFinal() {
        ui.post {
            voicePartial = 0
            buffer.setLength(0)
            kv?.listening = false
            kv?.level = 0f
        }
        // let the "stopped" line sit for a moment before the strip goes back
        ui.postDelayed({ if (voice?.isListening != true) refreshSugg() }, 900)
    }

    override fun onLevel(rms: Float) {
        kv?.level = rms
    }

    override fun onState(listening: Boolean, message: String) {
        ui.post {
            kv?.listening = listening
            if (message.isNotEmpty()) showStrip(message)
            if (!listening) {
                voicePartial = 0
                kv?.level = 0f
                ui.postDelayed({ if (voice?.isListening != true) refreshSugg() }, 1600)
            }
        }
    }

    /** Puts a message across the strip. Not a suggestion — it cannot be tapped. */
    private fun showStrip(text: String) {
        val v = kv ?: return
        v.statusOnly = true
        v.suggText = text
        v.suggs = listOf("", text, "")
        v.invalidate()
    }

    // ---------------- shortcuts ----------------

    private fun matchShortcut(): Shortcut? {
        if (buffer.isEmpty()) return null
        val body = buffer.toString()
        for (s in Store.ordered) {
            if (!body.endsWith(s.trigger)) continue
            val start = body.length - s.trigger.length
            if (start > 0 && !isBoundary(body[start - 1])) continue
            return s
        }
        return null
    }

    private fun refreshSugg() {
        val v = kv ?: return
        if (voice?.isListening == true) return
        v.statusOnly = false

        val hit = if (Store.kbExpand) matchShortcut() else null
        pendingShortcut = hit

        // a matching shortcut owns the whole strip — it is the stronger signal
        if (hit != null) {
            val t = "${hit.trigger}  \u2190  ${hit.phrase}"
            if (t != v.suggText) {
                v.suggText = t
                v.suggs = listOf("\u201C${hit.trigger}\u201D", hit.phrase, "")
                v.invalidate()
            }
            return
        }

        val word = buffer.toString()
        val zones = if (Store.kbPredict && word.isNotEmpty()) {
            val mine = if (Store.kbLearn) UserDict.predict(word, arabic, 2) else emptyList()
            val rest = Dict.predict(word, arabic, 3)
            (mine + rest).distinct().take(3)
        } else emptyList()

        if (v.suggText.isNotEmpty() || v.suggs != zones) {
            v.suggText = ""
            v.suggs = zones
            v.invalidate()
        }
    }

    private fun isWordBreak(c: Char): Boolean =
        c == ' ' || c == '\n' || c == '\t' || c == '.' || c == ',' || c == '!' ||
            c == '?' || c == '؟' || c == '،' || c == ':' || c == ';' || c == '-'

    private fun isBoundary(c: Char): Boolean = !c.isLetterOrDigit() && c != '_'

    // ---------------- feedback ----------------

    private fun feedback() {
        if (Store.kbSound) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 0.35f)
            } catch (_: Exception) {
            }
        }
        if (Store.kbVibrate) {
            try {
                kv?.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            } catch (_: Exception) {
            }
        }
    }
}
