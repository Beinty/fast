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

    /** Test path: reports what happened so the UI can show it. */
    fun test(ctx: Context, text: String, report: (String) -> Unit) {
        val run = {
            speakNow(text, 1, AudioManager.STREAM_MUSIC)
            if (arabicOk) report("جاري النطق — إذا ما سمعت شي، ارفع صوت الوسائط")
            else report("محرك النطق ما يدعم العربية — نزّل العربية من: الإعدادات ← إمكانية الوصول ← تحويل النص إلى كلام")
        }
        if (ready) run()
        else ensure(ctx) { ok ->
            if (ok) run()
            else report("ما لكيت محرك نطق بالجهاز — نصّب Speech Services by Google")
        }
    }

    fun applyProfile() {
        val e = tts ?: return
        try {
            e.setSpeechRate(Store.callerRate)
            e.setPitch(Store.callerPitch)
        } catch (_: Exception) {
        }
        val vn = Store.callerVoice
        if (vn.isNotBlank()) {
            try {
                e.voices?.firstOrNull { it.name == vn }?.let { e.voice = it }
            } catch (_: Exception) {
            }
        }
    }

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
    fun preview(ctx: Context, text: String) {
        if (ready) speakNow(text, 1, AudioManager.STREAM_MUSIC)
        else ensure(ctx) { ok -> if (ok) speakNow(text, 1, AudioManager.STREAM_MUSIC) }
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

    fun buildAnnouncement(ctx: Context, number: String?): String {
        val prefix = Store.callerPrefix.trim()
        val name = contactName(ctx, number)

        if (name != null) {
            return if (prefix.isEmpty()) name else "$prefix $name"
        }

        if (Store.callerSayNumber) {
            val digits = spellNumber(number)
            if (digits.isNotEmpty()) {
                return if (prefix.isEmpty()) digits else "$prefix $digits"
            }
        }

        return "مكالمة واردة"
    }
}
