package com.huc.fasttype

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Voice typing. Wraps the system speech recogniser and reports what it hears back to
 * the keyboard: partial text while the person is still talking, then the final text.
 */
class Voice(private val ctx: Context) {

    interface Sink {
        /** Called repeatedly with the best guess so far. */
        fun onPartial(text: String)
        /** Called once with the finished text; [text] is empty when nothing was heard. */
        fun onFinal(text: String)
        /** Listening started, stopped, or failed — [message] is already user-facing. */
        fun onState(listening: Boolean, message: String)
    }

    var sink: Sink? = null

    private var rec: SpeechRecognizer? = null
    private var active = false

    val isListening: Boolean get() = active

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    /** Opens the permission screen; the person comes back and presses the mic again. */
    fun askPermission() {
        try {
            ctx.startActivity(
                Intent(ctx, VoicePermActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (_: Exception) {
        }
    }

    fun start(arabic: Boolean) {
        stop()
        if (!available()) {
            sink?.onState(false, "التعرّف على الصوت مو متوفر بالجهاز")
            return
        }
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (_: Exception) {
            sink?.onState(false, "ما كدرت أشغّل المايك")
            return
        }
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                active = true
                sink?.onState(true, "تفضّل… أسمعك")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {
                sink?.onState(true, "لحظة…")
            }

            override fun onError(code: Int) {
                active = false
                sink?.onState(false, message(code))
                release()
            }

            override fun onResults(results: Bundle?) {
                active = false
                val best = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                sink?.onFinal(best)
                release()
            }

            override fun onPartialResults(partial: Bundle?) {
                val best = partial
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (best.isNotEmpty()) sink?.onPartial(best)
            }

            override fun onEvent(type: Int, params: Bundle?) {}
        })

        val tag = if (arabic) "ar-IQ" else "en-US"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        try {
            r.startListening(i)
        } catch (_: Exception) {
            active = false
            sink?.onState(false, "ما كدرت أشغّل المايك")
            release()
        }
    }

    fun stop() {
        if (rec == null) return
        try {
            rec?.stopListening()
            rec?.cancel()
        } catch (_: Exception) {
        }
        release()
        active = false
    }

    private fun release() {
        try {
            rec?.destroy()
        } catch (_: Exception) {
        }
        rec = null
    }

    private fun message(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "مشكلة بالمايك"
        SpeechRecognizer.ERROR_CLIENT -> ""
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "محتاج صلاحية المايك"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "محتاج إنترنت للتعرّف على الصوت"
        SpeechRecognizer.ERROR_NO_MATCH -> "ما سمعت شي"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "المايك مشغول، جرّب بعد ثانية"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ما سمعت شي"
        else -> "ما زبطت، جرّب مرة ثانية"
    }
}
