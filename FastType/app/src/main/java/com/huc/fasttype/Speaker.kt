package com.huc.fasttype

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
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
                ready = true
                cb?.invoke(true)
            } else {
                initFailed = true
                ready = false
                cb?.invoke(false)
            }
        }
    }

    fun say(ctx: Context, text: String, times: Int, stream: Int) {
        if (text.isBlank()) return
        if (ready) speakNow(text, times, stream)
        else ensure(ctx) { ok -> if (ok) speakNow(text, times, stream) }
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

    private fun speakNow(text: String, times: Int, stream: Int) {
        val engine = tts ?: return
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream)
        try {
            engine.stop()
            for (i in 0 until times.coerceIn(1, 5)) {
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                engine.speak(text, mode, params, "huc_$i")
                engine.playSilentUtterance(450, TextToSpeech.QUEUE_ADD, "huc_gap_$i")
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
        initFailed = false
        pending = null
    }

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

        return "مكالمة من رقم غير معروف"
    }

    fun ringStream(): Int = AudioManager.STREAM_RING
}
