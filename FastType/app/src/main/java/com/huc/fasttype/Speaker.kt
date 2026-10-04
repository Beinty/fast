package com.huc.fasttype

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
                        override fun onError(id: String?) {
                            if (id != null && id.startsWith("last")) releaseDuck()
                        }

                        override fun onDone(id: String?) {
                            if (id != null && id.startsWith("last")) releaseDuck()
                        }
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

    /** Call announcement: ducks the ringtone, speaks, then restores it. */
    fun announce(ctx: Context, text: String, times: Int) {
        if (text.isBlank()) return
        val mine = ++gen
        val run = {
            if (mine == gen) {
                takeDuck(ctx)
                speakNow(text, times, AudioManager.STREAM_MUSIC, mine)
            }
        }
        if (ready) run() else ensure(ctx) { ok -> if (ok) run() }
    }

    /**
     * The same announcement, but each piece read by the voice that can read it.
     *
     * A name saved in Latin letters is not Arabic text, and handing it to an
     * Arabic voice is what made the announcement unintelligible. The pieces that
     * are Arabic go to the Arabic voice; the ones that are not go to an English
     * one, and if the engine has no English voice they are spelled into Arabic
     * rather than dropped.
     */
    fun announceParts(ctx: Context, parts: List<Phon.Part>, times: Int) {
        if (parts.isEmpty()) return
        val mine = ++gen
        val run = {
            if (mine == gen) {
                takeDuck(ctx)
                speakParts(parts, times, AudioManager.STREAM_MUSIC, mine)
            }
        }
        if (ready) run() else ensure(ctx) { ok -> if (ok) run() }
    }

    /** Test path: reports what happened so the UI can show it. */
    fun test(ctx: Context, parts: List<Phon.Part>, report: (String) -> Unit) {
        val run = {
            autoPickVoice(ctx)
            speakParts(parts, 1, AudioManager.STREAM_MUSIC, gen)
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
        if (ready) speakParts(parts, 1, AudioManager.STREAM_MUSIC, gen)
        else ensure(ctx) { ok -> if (ok) speakParts(parts, 1, AudioManager.STREAM_MUSIC, gen) }
    }

    private fun speakNow(text: String, times: Int, stream: Int, mine: Int = gen) {
        val engine = tts ?: return
        if (mine != gen) return
        applyProfile()
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream)
        val n = times.coerceIn(1, 5)
        try {
            engine.stop()
            for (i in 0 until n) {
                // the repeats are queued, so a call that ends mid-sentence must take
                // the rest of the queue with it
                if (mine != gen) { engine.stop(); return }
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val id = if (i == n - 1) "last_$i" else "huc_$i"
                engine.speak(text, mode, params, id)
                if (i < n - 1) engine.playSilentUtterance(500, TextToSpeech.QUEUE_ADD, "gap_$i")
            }
        } catch (_: Exception) {
        }
    }

    private fun speakParts(list: List<Phon.Part>, times: Int, stream: Int, mine: Int) {
        val engine = tts ?: return
        if (mine != gen) return
        // no English voice on this engine means the Latin pieces have to be
        // spelled into Arabic, which is still better than letters read aloud
        val useEn = hasEnglish()
        val say = if (useEn) list else listOf(Phon.Part(Phon.flatten(list), true))

        try {
            engine.setSpeechRate(Store.callerRate)
            engine.setPitch(Store.callerPitch)
        } catch (_: Exception) {
        }

        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream)
        val n = times.coerceIn(1, 5)
        try {
            engine.stop()
            var first = true
            for (r in 0 until n) {
                if (mine != gen) { engine.stop(); return }
                for ((i, p) in say.withIndex()) {
                    if (mine != gen) { engine.stop(); return }
                    if (p.text.isBlank()) continue
                    useVoice(p.arabic)
                    val mode = if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    first = false
                    val isLast = r == n - 1 && i == say.size - 1
                    engine.speak(
                        p.text, mode, params,
                        if (isLast) "last_$r$i" else "huc_$r$i"
                    )
                }
                if (r < n - 1) engine.playSilentUtterance(600, TextToSpeech.QUEUE_ADD, "gap_$r")
            }
        } catch (_: Exception) {
        }
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
