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
    var arabicOk = true
        private set

    private var pending: (() -> Unit)? = null

    fun ensure(ctx: Context, onReady: (() -> Unit)? = null) {
        if (ready) {
            onReady?.invoke()
            return
        }
        if (onReady != null) pending = onReady
        if (tts != null) return

        val app = ctx.applicationContext
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    val r = tts?.setLanguage(Locale("ar"))
                    arabicOk = r != TextToSpeech.LANG_MISSING_DATA &&
                        r != TextToSpeech.LANG_NOT_SUPPORTED
                } catch (_: Exception) {
                    arabicOk = false
                }
                ready = true
                val p = pending
                pending = null
                p?.invoke()
            } else {
                pending = null
            }
        }
    }

    fun say(ctx: Context, text: String, times: Int, stream: Int) {
        if (text.isBlank()) return
        if (ready) speakNow(text, times, stream)
        else ensure(ctx) { speakNow(text, times, stream) }
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
