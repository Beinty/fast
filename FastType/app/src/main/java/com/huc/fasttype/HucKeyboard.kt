package com.huc.fasttype

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * The HUC keyboard. Shortcut expansion happens right here, so it needs no
 * accessibility service and cannot be throttled by the system.
 */
class HucKeyboard : InputMethodService(), KeyboardView.Listener {

    private var kv: KeyboardView? = null

    private var arabic = true
    private var shift = 0
    private var page = Pages.LETTERS
    private var lastShiftAt = 0L

    /** Characters typed since the last word break — the shortcut candidate. */
    private val buffer = StringBuilder(32)

    private var pendingShortcut: Shortcut? = null

    override fun onCreate() {
        super.onCreate()
        Store.load(this)
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
        return v
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
            it.rebuild()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        buffer.setLength(0)
        pendingShortcut = null
    }

    // ---------------- listener ----------------

    override fun onChar(s: String) {
        feedback()
        val ic = currentInputConnection ?: return

        val isBreak = s.length == 1 && isWordBreak(s[0])

        if (isBreak && Store.kbExpand) {
            val hit = matchShortcut()
            if (hit != null) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(hit.trigger.length, 0)
                ic.commitText(hit.phrase + s, 1)
                ic.endBatchEdit()
                buffer.setLength(0)
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
        feedback()
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (sel != null && sel.isNotEmpty()) ic.commitText("", 1)
        else ic.deleteSurroundingText(1, 0)
        if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
        refreshSugg()
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
        val hit = if (Store.kbExpand) matchShortcut() else null
        pendingShortcut = hit
        val t = if (hit != null) "${hit.trigger}  ←  ${hit.phrase}" else ""
        if (t != v.suggText) {
            v.suggText = t
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
                val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                v?.vibrate(VibrationEffect.createOneShot(12, 40))
            } catch (_: Exception) {
            }
        }
    }
}
