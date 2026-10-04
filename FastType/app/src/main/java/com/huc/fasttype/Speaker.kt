package com.huc.fasttype

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

object Speaker {

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var initFailed = false

    @Volatile
    var arabicOk = true
        private set

    private var pending: ((Boolean) -> Unit)? = null

    private var focusReq: AudioFocusRequest? = null
    private var focusCtx: Context? = null

    // ---------- init ----------

    fun ensure(ctx: Context, onDone: ((Boolean) -> Unit)? = null) {
        if (ready) {
            onDone?.invoke(true)
            return
        }

        if (initFailed) {
            try {
                tts?.shutdown()
            } catch (_: Exception) {
            }
            tts = null
            initFailed = false
        }

        if (onDone != null) pending = onDone
        if (tts != null) return

        val app = ctx.applicationContext
        tts = TextToSpeech(app) { status ->
            val cb = pending
            pending = null

            if (status == TextToSpeech.SUCCESS) {
                arabicOk = try {
                    val r = tts?.setLanguage(Locale("ar"))
                    val ok = r != TextToSpeech.LANG_MISSING_DATA &&
                        r != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!ok) {
                        try {
                            tts?.language = Locale.getDefault()
                        } catch (_: Exception) {
                        }
                    }
                    ok
                } catch (_: Exception) {
                    false
                }

                try {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onError(id: String?) { main.post { finished(id) } }
                        override fun onDone(id: String?) { main.post { finished(id) } }
                    })
                } catch (_: Exception) {
                }

                ready = true
                cb?.invoke(true)
            } else {
                initFailed = true
                ready = false
                cb?.invoke(false)
            }
        }
    }

    // ---------- public speak paths ----------

    /**
     * Counts every request to speak, so one that has been called off cannot start.
     *
     * The engine may still be waking up when the phone rings. The announcement was
     * handed to a callback that ran whenever the engine was finally ready — and if
     * the caller had hung up by then, it read the number out to an ended call.
     * Stopping now moves this on, and a start whose number no longer matches is
     * dropped where it stands.
     */
    @Volatile private var gen = 0

    private val main = Handler(Looper.getMainLooper())

    /**
     * One announcement, from the first word to the last repeat.
     *
     * Nothing is queued ahead. Each piece is spoken, and only when the engine
     * reports it finished does the next one start — because a repeat put on a
     * timer will cut the first one off the moment a name runs longer than the
     * guess, which is exactly what a full name does.
     */
    private class Run(
        val parts: List<Phon.Part>,
        val times: Int,
        val stream: Int,
        val mine: Int,
        val gapMs: Long,
        val wanted: (() -> Boolean)?
    ) {
        var round = 0
        var at = 0
    }

    @Volatile private var run: Run? = null

    /**
     * Pieces that share a voice are said as one sentence.
     *
     * Changing the voice between two queued utterances is what broke the
     * announcement in half; joining them means there is usually only one
     * utterance and no voice change at all.
     */
    private fun joined(parts: List<Phon.Part>): List<Phon.Part> {
        val out = ArrayList<Phon.Part>(parts.size)
        for (p in parts) {
            if (p.text.isBlank()) continue
            val last = out.lastOrNull()
            if (last != null && last.arabic == p.arabic) {
                out[out.size - 1] = Phon.Part(last.text + " " + p.text, p.arabic)
            } else {
                out.add(p)
            }
        }
        return out
    }

    /** Call announcement: ducks the ringtone, speaks, then restores it. */
    fun announce(ctx: Context, text: String, times: Int) {
        if (text.isBlank()) return
        announceParts(ctx, listOf(Phon.Part(text, true)), times)
    }

    /**
     * The same announcement, but each piece read by the voice that can read it.
     *
     * A name saved in Latin letters is not Arabic text, and handing it to an
     * Arabic voice is what made the announcement unintelligible. The pieces that
     * are Arabic go to the Arabic voice; the ones that are not go to an English
     * one, and if the engine has no English voice they are spelled into Arabic
     * rather than dropped.
     *
     * [wanted] is asked before every repeat: a call that has ended does not get
     * its name read out again.
     */
    fun announceParts(
        ctx: Context,
        parts: List<Phon.Part>,
        times: Int,
        gapMs: Long = 700L,
        wanted: (() -> Boolean)? = null
    ) {
        if (parts.isEmpty()) return
        val mine = ++gen
        val go = {
            if (mine == gen) {
                takeDuck(ctx)
                begin(parts, times, AudioManager.STREAM_MUSIC, mine, gapMs, wanted)
            }
        }
        if (ready) go() else ensure(ctx) { ok -> if (ok) go() }
    }

    private fun begin(
        parts: List<Phon.Part>, times: Int, stream: Int,
        mine: Int, gapMs: Long, wanted: (() -> Boolean)?
    ) {
        val engine = tts ?: return
        if (mine != gen) return
        val say = if (hasEnglish()) joined(parts)
        else listOf(Phon.Part(Phon.flatten(parts), true))
        if (say.isEmpty()) return

        try {
            engine.setSpeechRate(Store.callerRate)
            engine.setPitch(Store.callerPitch)
            engine.stop()
        } catch (_: Exception) {
        }
        run = Run(say, times.coerceIn(1, 5), stream, mine, gapMs, wanted)
        step(true)
    }

    private fun step(first: Boolean) {
        val r = run ?: return
        val engine = tts
        if (engine == null || r.mine != gen) { run = null; releaseDuck(); return }

        if (r.at >= r.parts.size) {
            r.round++
            r.at = 0
            if (r.round >= r.times || r.wanted?.invoke() == false) {
                run = null
                releaseDuck()
                return
            }
            main.postDelayed({ if (r.mine == gen && run === r) step(false) }, r.gapMs)
            return
        }

        val p = r.parts[r.at]
        r.at++
        useVoice(p.arabic)
        val id = "huc|" + r.mine + "|" + r.round + "|" + r.at
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, r.stream)
        try {
            engine.speak(
                p.text,
                if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                params, id
            )
        } catch (_: Exception) {
            run = null
            releaseDuck()
        }
    }

    /** The engine says a piece is done; the next one may start. */
    private fun finished(id: String?) {
        val r = run ?: return
        if (id == null || !id.startsWith("huc|")) return
        val bits = id.split("|")
        if (bits.size < 2 || bits[1].toIntOrNull() != r.mine) return
        if (r.mine != gen) { run = null; releaseDuck(); return }
        step(false)
    }

    /** Test path: reports what happened so the UI can show it. */
    fun test(ctx: Context, parts: List<Phon.Part>, report: (String) -> Unit) {
        val run = {
            autoPickVoice(ctx)
            announceParts(ctx, parts, 1)
            if (arabicOk) report("جاري النطق — إذا ما سمعت شي، ارفع صوت الوسائط")
            else report("محرك النطق ما يدعم العربية — نزّل العربية من: الإعدادات ← إمكانية الوصول ← تحويل النص إلى كلام")
        }
        if (ready) run()
        else ensure(ctx) { ok ->
            if (ok) run()
            else report("ما لكيت محرك نطق بالجهاز — نصّب Speech Services by Google")
        }
    }

    /**
     * Picks a voice the first time, so a woman's voice is what he hears without
     * having to go and find it. Only ever runs once; after that his own choice —
     * or the engine's default — stands.
     */
    fun autoPickVoice(ctx: Context) {
        if (Store.callerVoiceAuto) return
        val run = {
            if (!Store.callerVoiceAuto) {
                bestVoice("ar")?.let { Store.setCallerVoice(ctx, it.name) }
                bestVoice("en")?.let { Store.setCallerVoiceEn(ctx, it.name) }
                Store.setCallerVoiceAuto(ctx, true)
            }
        }
        if (ready) run() else ensure(ctx) { ok -> if (ok) run() }
    }

    fun applyProfile() {
        val e = tts ?: return
        try {
            e.setSpeechRate(Store.callerRate)
            e.setPitch(Store.callerPitch)
        } catch (_: Exception) {
        }
        useVoice(true)
    }

    /**
     * Which voice reads the next utterance.
     *
     * Android does not say whether a voice is a woman's — there is no such field
     * on Voice — so the name is all there is to go on. Engines that label it are
     * taken at their word; for the ones that do not, the identifiers known to be
     * women's voices are tried in order, and whatever he picks himself in the
     * settings always wins over both.
     */
    private val FEMALE_HINTS = arrayOf("female", "-f-", "#female", "woman", "fem")
    private val AR_FEMALE = arrayOf(
        "ar-xa-x-arz-local", "ar-xa-x-arz-network",
        "ar-xa-x-arb-local", "ar-xa-x-arb-network"
    )
    private val EN_FEMALE = arrayOf(
        "en-us-x-tpf-local", "en-us-x-tpf-network",
        "en-us-x-sfg-local", "en-us-x-sfg-network",
        "en-gb-x-gba-local", "en-gb-x-gba-network"
    )

    private fun named(name: String?): android.speech.tts.Voice? {
        if (name.isNullOrBlank()) return null
        return try {
            tts?.voices?.firstOrNull { it.name.equals(name, true) }
        } catch (_: Exception) {
            null
        }
    }

    private fun looksFemale(n: String): Boolean {
        val low = n.lowercase()
        for (h in FEMALE_HINTS) if (low.contains(h)) return true
        return false
    }

    /** Best guess at a woman's voice for a language, or the best voice there is. */
    fun bestVoice(lang: String): android.speech.tts.Voice? {
        val all = try {
            (tts?.voices ?: emptySet()).filter {
                it.locale?.language.equals(lang, true) && !it.isNetworkConnectionRequired
            }.ifEmpty {
                (tts?.voices ?: emptySet()).filter { it.locale?.language.equals(lang, true) }
            }
        } catch (_: Exception) {
            return null
        }
        if (all.isEmpty()) return null
        all.firstOrNull { looksFemale(it.name) }?.let { return it }
        val known = if (lang == "ar") AR_FEMALE else EN_FEMALE
        for (k in known) all.firstOrNull { it.name.equals(k, true) }?.let { return it }
        return all.maxByOrNull { it.quality }
    }

    private fun useVoice(arabic: Boolean) {
        val e = tts ?: return
        val chosen = if (arabic) named(Store.callerVoice) ?: bestVoice("ar")
        else named(Store.callerVoiceEn) ?: bestVoice("en")
        try {
            if (chosen != null) e.voice = chosen
            else e.language = if (arabic) Locale("ar") else Locale.US
        } catch (_: Exception) {
        }
    }

    /** True when the engine has anything at all that can read Latin letters. */
    fun hasEnglish(): Boolean = bestVoice("en") != null

    /** Arabic voices offered by the current engine, best-effort. */
    fun arabicVoices(ctx: Context, cb: (List<String>) -> Unit) {
        val run = {
            val list = try {
                (tts?.voices ?: emptySet()).filter {
                    it.locale?.language.equals("ar", true)
                }.map { it.name }.distinct().sorted()
            } catch (_: Exception) {
                emptyList()
            }
            cb(list)
        }
        if (ready) run() else ensure(ctx) { ok -> if (ok) run() else cb(emptyList()) }
    }

    /** Short sample on the media stream, for previewing a voice choice. */
    fun preview(ctx: Context, parts: List<Phon.Part>) {
        announceParts(ctx, parts, 1)
    }

    // ---------- ducking ----------

    private fun takeDuck(ctx: Context) {
        try {
            val am = ctx.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as AudioManager
            focusCtx = ctx.applicationContext

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ).setAudioAttributes(attrs).build()
                focusReq = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseDuck() {
        try {
            val ctx = focusCtx ?: return
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusReq?.let { am.abandonAudioFocusRequest(it) }
                focusReq = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        // anything waiting on the engine is now stale
        gen++
        run = null
        main.removeCallbacksAndMessages(null)
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        releaseDuck()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        releaseDuck()
        tts = null
        ready = false
        initFailed = false
        pending = null
    }

    // ---------- contacts ----------

    fun contactName(ctx: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun spellNumber(number: String?): String {
        if (number.isNullOrBlank()) return ""
        return number.filter { it.isDigit() }.toCharArray().joinToString(" ")
    }

    /** The announcement for a number, already split for the right voices. */
    fun buildParts(ctx: Context, number: String?): List<Phon.Part> {
        val prefix = Store.callerPrefix.trim()
        val out = ArrayList<Phon.Part>(4)
        val name = contactName(ctx, number)

        if (name != null) {
            if (prefix.isNotEmpty()) out.add(Phon.Part(prefix, true))
            out.addAll(Phon.parts(name, Store.callerLatin))
            if (out.isNotEmpty()) return out
        }

        if (Store.callerSayNumber) {
            val digits = spellNumber(number)
            if (digits.isNotEmpty()) {
                out.clear()
                if (prefix.isNotEmpty()) out.add(Phon.Part(prefix, true))
                out.add(Phon.Part(digits, true))
                return out
            }
        }

        return listOf(Phon.Part("مكالمة واردة", true))
    }
}
