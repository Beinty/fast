package com.huc.fasttype

import android.content.Context
import android.content.Intent
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

    /** Translate mode: what he is typing into the box, and what we put in the field. */
    private var transOn = false
    private val transBuf = StringBuilder()
    private var transJob: Runnable? = null
    /** Exactly what we last put in the app's field, so it can be replaced cleanly. */
    private var transOut = ""
    private var transLastSent = ""

    /** Recent pictures shown on the clipboard page, newest first. */
    private var picList: List<Pics.Shot> = emptyList()
    private var picWatcher: android.database.ContentObserver? = null

    /** The last finished word, and how much of it has been rubbed out since. */
    private var lastDone = ""
    private var eraseCount = 0

    /** A word he deleted whole, waiting to see what he types in its place. */
    private var repairFrom: String? = null
    private var repairAt = 0L

    /**
     * Is [good] the same word typed again, or a different word altogether?
     *
     * Only the first means anything: he backspaced over a slip and wrote it properly.
     * Deleting a word and writing something else entirely is just editing, and
     * filing that as a correction would teach the keyboard nonsense.
     */
    private fun looksLikeRepair(bad: String, good: String): Boolean {
        if (bad == good) return false
        if (bad.length < 3 || good.length < 3) return false
        if (Math.abs(bad.length - good.length) > 2) return false
        val left = good.toMutableList()
        var common = 0
        for (c in bad) {
            val i = left.indexOf(c)
            if (i >= 0) { left.removeAt(i); common++ }
        }
        return common >= Math.max(2, Math.min(bad.length, good.length) - 1)
    }

    /** For each letter in [buffer], the keys the finger was between. */
    private val nearBuf = ArrayList<String>(32)

    /**
     * Suggestions are computed after the letter is on screen, never before.
     *
     * Building them means a dictionary search and a repaint, and doing that inside
     * the keypress is what put a gap between the finger and the letter. A few
     * milliseconds' wait also means a burst of fast typing works the dictionary once
     * instead of once per key.
     */
    private val suggJob = Runnable { refreshSugg() }

    /**
     * The correction for the word being typed, worked out before it is asked for.
     *
     * Looking for a repair means a few hundred searches of a two-hundred-thousand
     * word list. Doing that when space is pressed puts all of it between his finger
     * and the screen. Doing it while his hand is still on the way to the space bar
     * puts it nowhere: by the time the key goes down the answer is already sitting
     * there, and all that is left is one delete and one commit.
     */
    private var specWord = ""
    private var specFix: String? = null
    private var specConf = 0f
    private var specDone = false

    /**
     * Where the searching happens.
     *
     * One background thread, not a pool: the work is a queue of guesses about one
     * word, and the only one worth having is the newest. Everything heavy — the
     * repair search and the dictionary scan behind the strip — runs here, and the
     * drawing thread is left to draw.
     */
    private val worker by lazy {
        android.os.HandlerThread("huc-think", android.os.Process.THREAD_PRIORITY_BACKGROUND)
            .also { it.start() }
    }
    private val bg by lazy { Handler(worker.looper) }

    /** Bumped on every keystroke; an answer about an older word is dropped. */
    private var think = 0

    private val specJob = Runnable { speculate() }

    private fun speculate() {
        val w = buffer.toString()
        if (specDone && w == specWord) return
        if (!Store.kbCorrect || w.length < 4) {
            specWord = w; specDone = true; specFix = null; specConf = 0f
            return
        }
        // the touch trail and the words before are read here, on the thread that
        // owns them, and handed over as a snapshot
        val near = ArrayList(nearBuf)
        val p1 = lastWord
        val p2 = prevWord
        val ar = arabic
        val mine = ++think
        bg.post {
            val fix = findFix(w, near, p1, p2, ar)
            val conf = if (fix == null) 0f else lastFixConf
            ui.post {
                if (mine != think) return@post
                specWord = w
                specDone = true
                specFix = fix
                specConf = conf
            }
        }
    }

    /**
     * The one place that decides what a mistyped word should have been.
     *
     * The word before it is part of the question. Two candidates can sit the same
     * distance from what he typed and only one of them is a word he ever writes
     * there, and that is the one he meant.
     */
    /** How sure the last call to [findFix] was, from 0 to 1. */
    @Volatile private var lastFixConf = 0f

    private fun findFix(
        typed: String, near: List<String>, prev: String, prev2: String, ar: Boolean
    ): String? {
        // a repair he taught us himself is not a guess
        val own = UserDict.fixFor(typed, ar)
        if (own != null) { lastFixConf = 1f; return own }
        if (Store.kbLearn && UserDict.isOwn(typed, ar)) { lastFixConf = 0f; return null }
        // A real word is never a mistake. His own words are allowed to pull a
        // misspelling towards them, but not a word that already stands on its own:
        // he writes "بينتي" every day, and that must not turn "بيتي" into it.
        if (Dict.known(typed, ar)) { lastFixConf = 0f; return null }
        UserDict.correct(typed, ar)?.let { lastFixConf = 0.95f; return it }
        // What the sentence says about each candidate. Two things say it: what he
        // himself writes after this word, and the shipped table of what generally
        // follows what. The second was already in the app, half a megabyte of it,
        // and it was only ever consulted to guess the next word — never to settle
        // which of two equally misspelled candidates was meant. Measured, that one
        // omission was the whole of the context score: nought in a hundred before,
        // forty-four in a hundred after.
        val general = Dict.nextAfter(prev2, prev, ar, 6)
        val rate: ((String) -> Float)? =
            if (Store.kbLearn || general.isNotEmpty()) { cand ->
                var w = if (Store.kbLearn) UserDict.contextWeight(prev2, prev, cand, ar)
                else 1f
                if (general.isNotEmpty()) {
                    val k = Dict.fold(cand, ar)
                    var at = -1
                    for (i in general.indices) {
                        if (Dict.fold(general[i], ar) == k) { at = i; break }
                    }
                    if (at in 0..2) w *= 0.45f else if (at >= 0) w *= 0.65f
                }
                w
            } else null
        val out = Dict.correctNear(typed, near, ar, rate)
        lastFixConf = if (out == null) 0f else Dict.lastConfidence
        return out
    }

    /** The straight path, for the moment space is pressed and nothing is ready. */
    private fun findFixNow(typed: String): String? =
        findFix(typed, nearBuf, lastWord, prevWord, arabic)

    private fun scheduleSugg() {
        ui.removeCallbacks(suggJob)
        ui.postDelayed(suggJob, 24)
        ui.removeCallbacks(specJob)
        // later than the strip: while he is still typing there is nothing to guess at,
        // and the moment he pauses is the moment before he reaches for space
        ui.postDelayed(specJob, 110)
    }

    /** Forgets the word being typed, and the touch trail that goes with it. */
    private fun resetWord() {
        buffer.setLength(0)
        nearBuf.clear()
        specDone = false
        specWord = ""
        specFix = null
    }
    private var voice: Voice? = null
    private var voiceBase = ""
    private var voicePartial = 0
    /** True while dictated text is still marked unfinished in the editor. */
    private var composing = false
    /** Words of the current sentence already handed over as ordinary text. */
    private var voiceLocked = ""
    /** Word the auto-correction just replaced, so one backspace puts it back. */
    private var undoTyped: String? = null
    private var undoFixed: String? = null
    private var repeatingDel = false
    /** The last finished word, so the strip can offer what usually follows it. */
    private var lastWord = ""

    /** The word before [lastWord] — two of history is what sharpens a guess. */
    private var prevWord = ""

    /**
     * A repair the engine believes in but not enough to make on its own. It goes
     * to the front of the strip instead of into his sentence.
     */
    private var offered: String? = null
    private var offeredFor = ""
    private var offeredEnd = ""

    /**
     * Below this a correction is offered rather than applied.
     *
     * Set where it pays to stop. Taking five thousand real slips and sorting every
     * repair by confidence, the question at each step down is how many more right
     * repairs that step buys and how many more wrong ones it costs. From 0.95 down
     * to 0.75 each step buys two right for one wrong. Below 0.72 it inverts: the
     * next repair admitted is likelier to be wrong than right. So that is the line.
     *
     * A repair below it is not lost — it stands first in the strip, and one tap
     * both fixes the word and teaches it for good. A wrong auto-replacement costs
     * a backspace and the irritation of having been overruled; a repair left in
     * the strip costs a tap. They are not the same price, so the line is not at
     * even odds.
     */
    private val SURE = 0.72f
    private var lastSpaceAt = 0L
    /** For each strip zone: true when it is a new word, false when it completes one. */
    private var suggKinds: List<Boolean> = emptyList()
    private var suggKey = ""

    /** How many guesses the scrolling strip may hold. */
    private val MAX_SUGG = 12

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
        Clip.load(this)
        watchClipboard()
    }

    private var clipWatcher: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

    /**
     * Android only lets the app with focus read the clipboard, and the active input
     * method counts while it is on screen — so this catches what is copied during use.
     */
    private fun watchClipboard() {
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return
            val l = android.content.ClipboardManager.OnPrimaryClipChangedListener {
                if (Store.kbClip) Clip.capture(this)
                kv?.invalidate()
            }
            cm.addPrimaryClipChangedListener(l)
            clipWatcher = l
        } catch (_: Exception) {
        }
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // day turned into night, or the other way round — repaint in the twin
        kv?.applySettings()
        kv?.rebuild()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        watchGallery()
        syncPics()
        clearWindowBackground()
        Dict.warm(this)
        commitDictated()
        // the moment the keyboard is on screen it may read the clipboard again, so
        // anything copied while it was away is picked up now
        if (Store.kbClip) {
            Clip.capture(this)
            Clip.expire()
        }
    }

    /** Types whatever the system voice screen heard while we were off screen. */
    private fun commitDictated() {
        val text = VoiceResult.take() ?: return
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: run { VoiceResult.pending = text; return }
        ic.beginBatchEdit()
        ic.commitText("$text ", 1)
        ic.endBatchEdit()
        resetWord()
        refreshSugg()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        voice?.stop()
        kv?.listening = false
        if (transOn) onTransClose()
        UserDict.save()
    }

    override fun onDestroy() {
        try { worker.quitSafely() } catch (_: Throwable) {}
        try {
            clipWatcher?.let {
                (getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager)
                    ?.removePrimaryClipChangedListener(it)
            }
        } catch (_: Exception) {
        }
        clipWatcher = null
        picWatcher?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Throwable) {}
        }
        picWatcher = null
        Pics.clear()
        voice?.stop()
        voice = null
        Tr.release()
        UserDict.save()
        super.onDestroy()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Store.load(this)
        // the keyboard opens all day; if a recording left the sound down, this
        // is the soonest anything of ours can put it back
        Hush.recover(this)
        resetWord()
        lastWord = ""
        prevWord = ""
        offered = null
        pendingShortcut = null
        shift = 0
        page = Pages.LETTERS
        if (transOn) {
            transOn = false
            transOut = ""
            transLastSent = ""
            transBuf.setLength(0)
        }
        kv?.let {
            it.setTranslate(false)
            it.setToolsOpen(false)
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

    /**
     * The field went empty under us.
     *
     * Most chat apps clear the box themselves when the send button is tapped, with no
     * key event we could see — so the keyboard went on offering what usually follows
     * the last word he sent, over an empty message box. A cursor sitting at position
     * zero means there is no sentence left to continue.
     */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        if (transOn) return
        if (newSelStart == 0 && newSelEnd == 0) {
            resetWord()
            lastWord = ""
            prevWord = ""
            offered = null
            lastDone = ""
            undoTyped = null
            undoFixed = null
            scheduleSugg()
        }
    }

    override fun onFinishInput() {
        if (composing) {
            composing = false
            try {
                currentInputConnection?.finishComposingText()
            } catch (_: Exception) {
            }
        }
        super.onFinishInput()
        resetWord()
        pendingShortcut = null
    }

    // ---------------- listener ----------------

    override fun onChar(raw: String) {
        // A field that only takes numbers — a phone box, a code, an amount — will not
        // understand ٠١٢. The keys keep showing Arabic digits; only what reaches the
        // field changes, and only there.
        val s = if (arabic) westernIfNumberField(raw) else raw

        if (transOn) {
            transBuf.append(s)
            // a space ends a word, and that is the moment a translation is worth having
            afterTransEdit(s == " " || s == "\n")
            return
        }
        val ic = currentInputConnection ?: return
        undoTyped = null
        undoFixed = null
        releaseComposing(ic)

        // two quick taps on space end the sentence instead of leaving a double gap
        if (s == " " && Store.kbDoubleSpace) {
            val now = System.currentTimeMillis()
            val quick = now - lastSpaceAt < 700L
            lastSpaceAt = now
            if (quick && buffer.isEmpty() && endsWithLetterThenSpace(ic)) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                lastSpaceAt = 0L
                lastWord = ""
                prevWord = ""
                offered = null
                feedback()
                scheduleSugg()
                afterType()
                return
            }
        } else if (s != " ") {
            lastSpaceAt = 0L
        }

        // A vowel mark belongs to the letter in front of it, not to the word the
        // corrector is watching: leaving it out of the buffer keeps "كَتب" the word
        // "كتب" as far as spelling is concerned, which is what it is.
        if (s.length == 1 && isMark(s[0])) {
            ic.commitText(s, 1)
            feedback()
            afterType()
            return
        }

        val isBreak = s.length == 1 && isWordBreak(s[0])

        if (isBreak && Store.kbExpand) {
            val hit = matchShortcut()
            if (hit != null) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(hit.trigger.length, 0)
                ic.commitText(hit.phrase + s, 1)
                ic.endBatchEdit()
                resetWord()
                feedback()
                scheduleSugg()
                afterType()
                return
            }
        }

        if (isBreak && buffer.isNotEmpty()) {
            val typed = buffer.toString()

            var fixed: String? = null
            if (Store.kbCorrect) {
                // nearly always already known, so this costs nothing at all here
                val ready = specDone && specWord == typed
                val cand = if (ready) specFix else findFixNow(typed)
                val conf = if (ready) specConf else lastFixConf
                // Sure enough to spend his words on, or only sure enough to offer.
                // Below the line the word he typed stands and the guess goes to the
                // strip, where one tap takes it and ignoring it costs nothing.
                if (cand != null && conf >= SURE) fixed = cand
                else if (cand != null) {
                    offered = cand
                    offeredFor = typed
                    offeredEnd = s
                } else offered = null
            }

            // The word that joins his vocabulary is the right one, never the slip.
            // Counting the slip was what poisoned this: typed wrong twice, it became
            // "his own spelling" and the keyboard stopped fixing it for good.
            val learnt = fixed ?: typed
            if (Store.kbLearn) {
                // A word we were about to correct and only held back on is not a
                // word of his. Counting it here is how a slip the engine already
                // had an answer for climbed into his vocabulary and put itself
                // beyond correction.
                if (offered == null || fixed != null) UserDict.seen(learnt, arabic)
                if (lastWord.isNotEmpty()) UserDict.seenPair(lastWord, learnt, arabic)
                if (prevWord.isNotEmpty() && lastWord.isNotEmpty()) {
                    UserDict.seenTri(prevWord, lastWord, learnt, arabic)
                }

                // he rubbed out a word a moment ago and has just retyped it — that
                // second attempt is him telling us what the first one should have been
                val rf = repairFrom
                if (rf != null) {
                    repairFrom = null
                    if (System.currentTimeMillis() - repairAt < 25_000L &&
                        looksLikeRepair(rf, learnt)
                    ) {
                        UserDict.learnFix(rf, learnt, arabic)
                    }
                }
            }
            prevWord = lastWord
            lastWord = learnt
            lastDone = learnt
            eraseCount = 0

            if (fixed != null) {
                offered = null
                ic.beginBatchEdit()
                ic.deleteSurroundingText(typed.length, 0)
                ic.commitText(fixed + s, 1)
                ic.endBatchEdit()
                undoTyped = typed
                undoFixed = fixed
                // worth remembering straight away; one backspace takes it back out
                // Remembering a repair is part of correcting, not part of
                // learning his vocabulary. Tying it to the vocabulary switch meant
                // that with that switch off nothing was ever remembered and the
                // engine re-decided from scratch every single time.
                UserDict.learnFix(typed, fixed, arabic)
                resetWord()
                feedback()
                scheduleSugg()
                afterType()
                return
            }
        }

        ic.commitText(s, 1)

        if (isBreak) resetWord()
        else {
            offered = null
            buffer.append(s)
            nearBuf.add(kv?.lastNear ?: "")
            eraseCount = 0
            if (buffer.length > 32) {
                buffer.delete(0, buffer.length - 32)
                while (nearBuf.size > buffer.length) nearBuf.removeAt(0)
            }
            if (Store.kbExpandInstant && Store.kbExpand) {
                val hit = matchShortcut()
                if (hit != null) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(hit.trigger.length, 0)
                    ic.commitText(hit.phrase, 1)
                    ic.endBatchEdit()
                    resetWord()
                }
            }
        }

        feedback()
        scheduleSugg()
        afterType()
    }

    /** True when the text reads "…letter space", the only case worth replacing. */
    private fun endsWithLetterThenSpace(
        ic: android.view.inputmethod.InputConnection
    ): Boolean {
        val before = ic.getTextBeforeCursor(2, 0) ?: return false
        if (before.length < 2) return false
        return before[1] == ' ' && before[0].isLetterOrDigit()
    }

    private fun afterType() {
        if (shift == 1 && !arabic && page == Pages.LETTERS) {
            shift = 0
            kv?.shift = 0
            kv?.relabel()
        }
    }

    /**
     * The finger landed on one letter and lifted on another, so swap them.
     *
     * Everything the word carries — its letters and the record of which keys the
     * finger was between — has to be swapped with it, or the corrector would be
     * reasoning about a word that is no longer there.
     */
    override fun onReplaceChar(s: String) {
        if (transOn) {
            if (transBuf.isNotEmpty()) transBuf.setLength(transBuf.length - 1)
            transBuf.append(s)
            afterTransEdit(false)
            return
        }
        val ic = currentInputConnection ?: return
        undoTyped = null
        undoFixed = null
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(s, 1)
        ic.endBatchEdit()
        if (buffer.isNotEmpty()) {
            buffer.setLength(buffer.length - 1)
            if (nearBuf.isNotEmpty()) nearBuf.removeAt(nearBuf.size - 1)
            buffer.append(s)
            nearBuf.add(kv?.lastNear ?: "")
        }
        scheduleSugg()
    }

    private fun westernIfNumberField(s: String): String {
        if (s.length != 1) return s
        val c = s[0]
        if (c < '\u0660' || c > '\u0669') return s
        val cls = currentInputEditorInfo?.inputType?.and(android.text.InputType.TYPE_MASK_CLASS)
        if (cls != android.text.InputType.TYPE_CLASS_NUMBER &&
            cls != android.text.InputType.TYPE_CLASS_PHONE
        ) return s
        return ('0' + (c.code - 0x0660)).toString()
    }

    override fun onDelete() {
        if (transOn) {
            if (transBuf.isNotEmpty()) transBuf.setLength(transBuf.length - 1)
            afterTransEdit(false)
            return
        }
        val ic = currentInputConnection ?: return
        releaseComposing(ic)

        // One backspace right after an auto-correction puts the typed word back.
        // It does not end the matter: a backspace lands for all sorts of reasons,
        // so the repair is only dropped. Putting the same word back a second time
        // is what tells us the spelling was meant.
        val t = undoTyped
        val f = undoFixed
        if (t != null && f != null) {
            undoTyped = null
            undoFixed = null
            UserDict.keepAsIs(t, arabic)
            repairFrom = null
            lastDone = t
            eraseCount = 0
            ic.beginBatchEdit()
            ic.deleteSurroundingText(f.length + 1, 0)
            ic.commitText(t, 1)
            ic.endBatchEdit()
            resetWord()
            buffer.append(t)
            feedback()
            scheduleSugg()
            return
        }

        // getSelectedText is a round trip to the other app, so it only runs on the
        // first press — holding backspace must never pay for it
        if (!repeatingDel) {
            val sel = ic.getSelectedText(0)
            if (sel != null && sel.isNotEmpty()) {
                lastWord = ""
                prevWord = ""
                offered = null
                ic.commitText("", 1)
                resetWord()
                feedback()
                scheduleSugg()
                return
            }
        }

        ic.deleteSurroundingText(1, 0)
        // Deleting throws away the word the next-word guesses were based on. Leaving
        // them up meant the strip still offered words over an empty message box.
        lastWord = ""
        prevWord = ""
        offered = null
        if (buffer.isNotEmpty()) {
            buffer.setLength(buffer.length - 1)
            if (nearBuf.isNotEmpty()) nearBuf.removeAt(nearBuf.size - 1)
        } else if (lastDone.isNotEmpty()) {
            // past the start of the word being typed, so these presses are eating
            // the word before it — the separator first, then its letters
            eraseCount++
            if (eraseCount >= lastDone.length + 1) {
                repairFrom = lastDone
                repairAt = System.currentTimeMillis()
                lastDone = ""
                eraseCount = 0
            }
        }
        feedback()
        scheduleSugg()
    }

    /** Deletes back to the start of the previous word, for a long backspace hold. */
    /** A tap pastes the last thing copied, with no panel in the way. */
    override fun onClipTap() {
        val pic = Pics.ready
        if (pic != null) {
            feedback()
            val ok = Pics.send(this, currentInputConnection, currentInputEditorInfo, pic)
            if (!ok) {
                kv?.statusOnly = true
                kv?.suggText = "هذا التطبيق ما يستقبل صور من الكيبورد"
                kv?.suggs = listOf("هذا التطبيق ما يستقبل صور من الكيبورد")
                kv?.stripChanged()
                ui.postDelayed({ refreshSugg() }, 1600)
            } else {
                kv?.stripChanged()
            }
            return
        }
        val ic = currentInputConnection ?: return
        Clip.capture(this)
        val t = Clip.latest()
        if (t.isNullOrEmpty()) {
            showStrip("الحافظة فارغة")
            ui.postDelayed({ refreshSugg() }, 1100)
            return
        }
        releaseComposing(ic)
        ic.commitText(t, 1)
        Clip.used()
        resetWord()
        lastWord = ""
        prevWord = ""
        offered = null
        feedback()
        refreshSugg()
    }

    /** A long press opens the list of everything copied lately. */
    override fun onClipHold() {
        Clip.capture(this)
        Clip.expire()
        page = Pages.CLIP
        kv?.page = page
        kv?.rebuild()
        buildShelf()
    }

    override fun onClipPick(index: Int) {
        val t = Clip.all.getOrNull(index)?.text ?: return
        val ic = currentInputConnection
        page = Pages.LETTERS
        kv?.page = page
        kv?.rebuild()
        if (ic != null) {
            releaseComposing(ic)
            ic.commitText(t, 1)
        }
        Clip.used()
        resetWord()
        lastWord = ""
        prevWord = ""
        offered = null
        feedback()
        refreshSugg()
    }

    override fun onPicPick(index: Int) {
        val shot = picList.getOrNull(index) ?: return
        feedback()
        val ok = Pics.send(this, currentInputConnection, currentInputEditorInfo, shot.uri)
        page = Pages.LETTERS
        kv?.page = Pages.LETTERS
        kv?.rebuild()
        if (!ok) {
            kv?.statusOnly = true
            kv?.suggText = "هذا التطبيق ما يستقبل صور من الكيبورد"
            kv?.suggs = listOf("هذا التطبيق ما يستقبل صور من الكيبورد")
            kv?.stripChanged()
            ui.postDelayed({ refreshSugg() }, 1600)
        }
    }

    private fun watchGallery() {
        if (picWatcher != null || !Store.kbPics || !Pics.allowed(this)) return
        try {
            val obs = object : android.database.ContentObserver(ui) {
                override fun onChange(selfChange: Boolean) {
                    // a screenshot taken while the keyboard is up should appear on the
                    // key without him having to close and open it again
                    ui.postDelayed({ syncPics() }, 350)
                }
            }
            contentResolver.registerContentObserver(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs
            )
            picWatcher = obs
        } catch (_: Throwable) {
        }
    }

    /**
     * Re-reads what the gallery has, off the main thread.
     *
     * This used to run where it was called from — which is the moment the keyboard is
     * asked to appear. Asking MediaStore what it holds and then decoding eight
     * screenshots is a third of a second of work, and all of it landed between his
     * tap on the message box and the keyboard showing up. Nothing here is urgent
     * enough to be in front of that.
     */
    private fun syncPics() {
        if (!Store.kbPics || !Pics.allowed(this)) {
            if (Pics.ready != null) { Pics.clear(); kv?.stripChanged() }
            picList = emptyList()
            kv?.picShelf = emptyList()
            return
        }
        val px = (resources.displayMetrics.density * 96f).toInt()
        val app = applicationContext
        Thread {
            val changed = Pics.refresh(app, px) || Pics.fromClipboard(app, px)
            val list = Pics.recent(app, 8)
            ui.post {
                picList = list
                if (changed) kv?.stripChanged()
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Thumbnails cost real time to decode, so they wait until the page is opened. */
    private fun buildShelf() {
        val list = picList
        if (list.isEmpty()) { kv?.picShelf = emptyList(); return }
        val px = (resources.displayMetrics.density * 96f).toInt()
        val app = applicationContext
        Thread {
            val thumbs = list.mapNotNull { Pics.thumb(app, it.uri, px) }
            ui.post {
                kv?.picShelf = thumbs
                if (page == Pages.CLIP) kv?.invalidate()
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    override fun onClipClose() {
        page = Pages.LETTERS
        kv?.page = page
        kv?.rebuild()
        refreshSugg()
    }

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
        resetWord()
        lastWord = ""
        prevWord = ""
        offered = null
        refreshSugg()
    }

    override fun onRepeatState(active: Boolean) {
        repeatingDel = active
        if (!active) refreshSugg()
    }

    override fun onEnter() {
        feedback()
        // in translate mode the key means "I'm done" — the translation stays behind
        if (transOn) { onTransClose(); return }
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
        resetWord()
        lastWord = ""
        prevWord = ""
        offered = null
        lastDone = ""
        refreshSugg()
    }

    override fun onShift() {
        feedback()
        val now = System.currentTimeMillis()
        shift = if (now - lastShiftAt < 400) 2 else if (shift > 0) 0 else 1
        lastShiftAt = now
        kv?.shift = shift
        kv?.relabel()
    }

    override fun onLang() {
        feedback()
        arabic = !arabic
        shift = 0
        resetWord()
        kv?.let {
            it.arabic = arabic
            it.shift = 0
            it.suggText = ""
            it.rebuild()
        }
    }

    override fun onPage(p: Int) {
        feedback()
        val v = kv
        page = p
        v?.page = p
        // the faces page shows the tools instead of suggestions, and holds them
        // open, because tapping the same icon again is the way back
        if (p == Pages.EMOJI) v?.setToolsOpen(true)
        else if (v?.toolsAreOpen() == true) v.setToolsOpen(false)
        v?.rebuild()
    }

    override fun onSuggestionTap() {
        val hit = pendingShortcut ?: return
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(hit.trigger.length, 0)
        ic.commitText(hit.phrase, 1)
        ic.endBatchEdit()
        resetWord()
        refreshSugg()
    }

    override fun onPredictionTap(index: Int) {
        val v = kv ?: return
        if (v.statusOnly) return
        if (pendingShortcut != null) { onSuggestionTap(); return }
        val word = v.suggs.getOrNull(index) ?: return
        if (word.isEmpty()) return
        val ic = currentInputConnection ?: return

        // the offered repair sits at the front and replaces a word already written,
        // along with whatever ended it
        val off = offered
        if (off != null && index == 0 && word == off && buffer.isEmpty()) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(offeredFor.length + offeredEnd.length, 0)
            ic.commitText(off + offeredEnd, 1)
            ic.endBatchEdit()
            if (Store.kbLearn) {
                UserDict.learnFix(offeredFor, off, arabic)
                UserDict.seen(off, arabic)
            }
            lastWord = off
            lastDone = off
            offered = null
            resetWord()
            undoTyped = null
            undoFixed = null
            feedback()
            refreshSugg()
            return
        }

        val whole = suggKinds.getOrNull(index) ?: false
        ic.beginBatchEdit()
        // a completion replaces what is half-typed; a next word just goes after it
        if (!whole && buffer.isNotEmpty()) ic.deleteSurroundingText(buffer.length, 0)
        else if (whole && buffer.isNotEmpty()) ic.commitText(" ", 1)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        if (Store.kbLearn) {
            UserDict.seen(word, arabic)
            val prev = if (whole && buffer.isNotEmpty()) buffer.toString() else lastWord
            if (prev.isNotEmpty()) UserDict.seenPair(prev, word, arabic)
        }
        // a whole line leaves its last word behind as the context for the next one
        lastWord = if (word.indexOf(' ') >= 0) word.substringAfterLast(' ') else word
        resetWord()
        undoTyped = null
        undoFixed = null
        feedback()
        refreshSugg()
    }

    // ---------------- voice typing ----------------

    // ---- the strip's icon bar, and translation -----------------------------

    override fun onTool(which: Int) {
        feedback()
        when (which) {
            // the faces page is a toggle: the same icon takes him there and back
            0 -> onPage(if (page == Pages.EMOJI) Pages.LETTERS else Pages.EMOJI)
            1 -> onMic()
            2 -> openTranslate()
            3 -> onClipTap()
            4 -> {
                try {
                    val i = Intent(this, MainActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun openTranslate() {
        val ic = currentInputConnection
        if (ic != null) releaseComposing(ic)
        transOn = true
        transOut = ""
        transLastSent = ""
        transBuf.setLength(0)
        kv?.setTranslate(true)
        kv?.setTransText("", "")
        // fetch the language pack now rather than on the first letter he types
        Tr.warm(Store.kbTrSrc, Store.kbTrDst)
    }

    override fun onTransClose() {
        transOn = false
        transOut = ""
        transLastSent = ""
        transBuf.setLength(0)
        transJob?.let { ui.removeCallbacks(it) }
        transJob = null
        kv?.setTranslate(false)
        refreshSugg()
    }

    override fun onTransSwap() {
        val src = Store.kbTrSrc
        val dst = Store.kbTrDst
        Store.setTrLang(this, false, dst)
        Store.setTrLang(this, true, if (src == Tr.AUTO) "ar" else src)
        kv?.invalidate()
        transLastSent = ""
        afterTransEdit(true)
    }

    override fun onTransLang(dst: Boolean) {
        feedback()
        kv?.openLangs(dst)
    }

    override fun onLangPick(code: String) {
        feedback()
        page = Pages.LETTERS
        kv?.page = Pages.LETTERS
        kv?.rebuild()
        if (code.isNotEmpty()) {
            transLastSent = ""
            afterTransEdit(true)
        }
    }

    /** Redraws the box and queues a translation; a finished word jumps the queue. */
    private fun afterTransEdit(now: Boolean) {
        feedback()
        kv?.setTransText(transBuf.toString(), Tr.status)
        transJob?.let { ui.removeCallbacks(it) }
        val job = Runnable { runTranslate() }
        transJob = job
        ui.postDelayed(job, if (now) 0L else 260L)
    }

    private fun runTranslate() {
        val text = transBuf.toString().trim()
        if (text.isEmpty()) {
            replaceOutput("")
            transLastSent = ""
            kv?.setTransText(transBuf.toString(), "")
            return
        }
        // nothing changed since the last request, so there is nothing to ask for
        if (text == transLastSent) return
        transLastSent = text
        Tr.translate(text, Store.kbTrSrc, Store.kbTrDst) { out ->
            if (transOn) {
                kv?.setTransText(transBuf.toString(), Tr.status)
                if (out != null) replaceOutput(out)
            }
        }
        kv?.setTransText(transBuf.toString(), Tr.status)
    }

    /**
     * Swaps what we wrote last time for [out].
     *
     * Composing text looked like the tidy way to do this, but apps are free to reset
     * the composing region whenever they like, and when one did, the next update
     * landed on top of his own words instead of ours — which is why text kept
     * disappearing. Reading back what is actually there before deleting it means we
     * only ever remove our own output.
     */
    private fun replaceOutput(out: String) {
        val ic = currentInputConnection ?: return
        if (out == transOut) return
        ic.beginBatchEdit()
        if (transOut.isNotEmpty()) {
            val before = try {
                ic.getTextBeforeCursor(transOut.length, 0)?.toString() ?: ""
            } catch (_: Throwable) {
                ""
            }
            if (before == transOut) ic.deleteSurroundingText(transOut.length, 0)
        }
        if (out.isNotEmpty()) ic.commitText(out, 1)
        ic.endBatchEdit()
        transOut = out
    }

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
        composing = false
        voiceLocked = ""
        kv?.listening = true
        vo.start(arabic)
    }

    /**
     * Live text while the sentence is still being spoken.
     *
     * The recogniser re-reads the whole utterance on every update, and its new guess
     * can be *shorter* than the last one — it takes words back. So only the word
     * still being spoken is left open to change: everything up to the last space is
     * handed to the editor as ordinary text the moment it is complete, and from then
     * on nothing can take it away.
     */
    override fun onPartial(text: String) {
        ui.post {
            val ic = currentInputConnection ?: return@post
            lockWords(ic, text)
            showStrip(text)
        }
    }

    private fun lockWords(ic: android.view.inputmethod.InputConnection, text: String) {
        if (text.isEmpty()) return

        // the new guess is shorter than what is already written: the engine dropped
        // words it had given us. Those stay — only the open tail goes.
        if (voiceLocked.isNotEmpty() && voiceLocked.trimEnd().startsWith(text)) {
            if (composing) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
                composing = false
            }
            return
        }

        // the engine contradicted what is already written — append rather than fight
        if (!text.startsWith(voiceLocked)) {
            ic.beginBatchEdit()
            if (composing) ic.finishComposingText()
            if (voiceLocked.isNotEmpty()) ic.commitText(" ", 1)
            voiceLocked = ""
            ic.setComposingText(text, 1)
            ic.endBatchEdit()
            composing = true
            return
        }

        val rest = text.substring(voiceLocked.length)
        val cut = rest.lastIndexOf(' ')
        ic.beginBatchEdit()
        if (cut >= 0) {
            // finished words become real text and are safe from here on
            ic.setComposingText(rest.substring(0, cut + 1), 1)
            ic.finishComposingText()
            voiceLocked = text.substring(0, voiceLocked.length + cut + 1)
            val tail = rest.substring(cut + 1)
            if (tail.isNotEmpty()) ic.setComposingText(tail, 1)
            composing = tail.isNotEmpty()
        } else {
            ic.setComposingText(rest, 1)
            composing = true
        }
        ic.endBatchEdit()
    }

    /** A finished sentence. The session stays open for the next one. */
    override fun onSegment(text: String) {
        ui.post {
            val ic = currentInputConnection ?: return@post
            if (text.isNotEmpty()) lockWords(ic, text)
            settleVoice(ic)
        }
    }

    /** Keeps the live text as written, so the next sentence starts after it. */
    override fun onSegmentEnd() {
        ui.post { currentInputConnection?.let { settleVoice(it) } }
    }

    /** Hands dictated text over to the editor before a key touches it. */
    private fun releaseComposing(ic: android.view.inputmethod.InputConnection) {
        voiceLocked = ""
        if (!composing) return
        composing = false
        try {
            ic.finishComposingText()
        } catch (_: Exception) {
        }
    }

    /** Closes off the sentence: nothing of it stays open to change. */
    private fun settleVoice(ic: android.view.inputmethod.InputConnection) {
        if (!composing && voiceLocked.isEmpty()) return
        ic.beginBatchEdit()
        if (composing) ic.finishComposingText()
        ic.commitText(" ", 1)
        ic.endBatchEdit()
        composing = false
        voiceLocked = ""
        resetWord()
    }

    override fun onFinal() {
        ui.post {
            currentInputConnection?.let { settleVoice(it) }
            voicePartial = 0
            resetWord()
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
        // our own voice typing is a recording too, and silencing the phone for it
        // would be silencing it for nothing
        Hush.ours = listening
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
        suggKey = ""
        v.statusOnly = true
        v.suggText = text
        v.suggs = listOf("", text, "")
        v.invalidate()
    }

    // ---------------- shortcuts ----------------

    private fun matchShortcut(): Shortcut? {
        val n = buffer.length
        if (n == 0) return null
        // only triggers ending in the letter just typed can possibly have completed
        val cands = Store.byLast[buffer[n - 1]] ?: return null
        val body = buffer.toString()
        for (s in cands) {
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
            suggKey = ""

            val t = "${hit.trigger}  \u2190  ${hit.phrase}"
            if (t != v.suggText) {
                v.suggText = t
                v.suggNew = listOf(false, false)
                v.suggs = listOf(hit.phrase, "\u201C${hit.trigger}\u201D")
                v.stripChanged()
            }
            return
        }

        val word = buffer.toString()

        // the strip is asked to refresh far more often than its answer changes,
        // so an identical question is answered from the last result
        val key = word + "\u0001" + lastWord + "\u0001" + prevWord +
            "\u0001" + (offered ?: "") + if (arabic) "|ar" else "|en"
        if (key == suggKey) return
        suggKey = key

        val zones = ArrayList<String>(MAX_SUGG)
        val kinds = ArrayList<Boolean>(MAX_SUGG)  // true = a whole new word

        // a repair the engine was not sure enough to make goes first, where one
        // tap takes it and ignoring it costs nothing
        val off = offered
        if (off != null && word.isEmpty()) {
            zones.add(off)
            kinds.add(true)
        }

        if (Store.kbPredict) {
            // what usually follows the finished word comes first — it is the stronger
            // guess once a word is done
            // The sentence so far, and whatever of the next word is already on the
            // screen. While a word is half typed those two used to be asked apart —
            // and so the strongest thing the keyboard knew went unused at exactly
            // the moment it was most use.
            if (word.isNotEmpty() && lastWord.isNotEmpty()) {
                val mine = if (Store.kbLearn)
                    UserDict.nextWith(prevWord, lastWord, word, arabic, MAX_SUGG)
                else emptyList()
                for (w in mine +
                    Dict.nextAfterPrefix(prevWord, lastWord, word, arabic, MAX_SUGG)) {
                    if (zones.size >= MAX_SUGG) break
                    if (w != word && !zones.contains(w)) { zones.add(w); kinds.add(false) }
                }
            }

            // once nothing is being typed, what follows the finished sentence
            if (word.isEmpty() && lastWord.isNotEmpty()) {
                val mine = if (Store.kbLearn)
                    UserDict.nextTri(prevWord, lastWord, arabic, 3) +
                        UserDict.next(lastWord, arabic, 4)
                else emptyList()
                for (w in mine + Dict.nextAfter(prevWord, lastWord, arabic, 6)) {
                    if (zones.size >= MAX_SUGG) break
                    if (!zones.contains(w)) { zones.add(w); kinds.add(true) }
                }
            }

            // then every completion of the word being typed
            if (word.isNotEmpty()) {
                // a line he writes often, offered whole rather than a word at a time
                if (Store.kbLearn && zones.size < MAX_SUGG) {
                    UserDict.phrase(word, arabic)?.let {
                        if (!zones.contains(it)) { zones.add(it); kinds.add(false) }
                    }
                }
                val mine =
                    if (Store.kbLearn) UserDict.predict(word, arabic, 4) else emptyList()
                for (w in mine + Dict.predict(word, arabic, MAX_SUGG)) {
                    if (zones.size >= MAX_SUGG) break
                    if (w != word && !zones.contains(w)) { zones.add(w); kinds.add(false) }
                }
            }

            // Last, and only to fill a zone still empty: what tends to follow the
            // word he has just finished typing but not yet spaced. Measured, this
            // is worth nothing once the two sources above have had their pick — it
            // used to come first, and it was taking their places.
            if (word.isNotEmpty() && zones.size < MAX_SUGG &&
                (Dict.known(word, arabic) || UserDict.isOwn(word, arabic))
            ) {
                val mine = if (Store.kbLearn)
                    UserDict.nextTri(lastWord, word, arabic, 3) +
                        UserDict.next(word, arabic, 4)
                else emptyList()
                for (w in mine + Dict.nextAfter(lastWord, word, arabic, 6)) {
                    if (zones.size >= MAX_SUGG) break
                    if (!zones.contains(w)) { zones.add(w); kinds.add(true) }
                }
            }
        }

        suggKinds = kinds
        if (v.suggText.isNotEmpty() || v.suggs != zones) {
            v.suggText = ""
            v.suggNew = kinds
            v.suggs = zones
            v.stripChanged()
        }
    }

    /** A combining Arabic vowel mark, which sits on a letter rather than beside it. */
    private fun isMark(c: Char): Boolean =
        (c in '\u064B'..'\u0652') || c == '\u0670' || c == '\u0653' ||
            c == '\u0654' || c == '\u0655'

    private fun isWordBreak(c: Char): Boolean =
        c == ' ' || c == '\n' || c == '\t' || c == '.' || c == ',' || c == '!' ||
            c == '?' || c == '؟' || c == '،' || c == ':' || c == ';' || c == '-'

    private fun isBoundary(c: Char): Boolean = !c.isLetterOrDigit() && c != '_'

    // ---------------- feedback ----------------

    /** Looked up once; fetching a system service on every keystroke is not free. */
    private var audio: AudioManager? = null

    private fun feedback() {
        if (Store.kbSound) {
            try {
                val am = audio
                    ?: (getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { audio = it }
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
