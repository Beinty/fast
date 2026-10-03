package com.huc.fasttype

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Build

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
    private var triedOnDevice = false
    private var lastArabic = false
    private var tagIndex = 0

    /**
     * Recognisers reject a dialect tag they do not carry — "ar-IQ" comes back as
     * ERROR_LANGUAGE_NOT_SUPPORTED on most phones. So each language is a list, from
     * the most specific down to letting the engine pick, and a rejection moves along.
     */
    private val arTags = arrayOf("ar", "ar-SA", "ar-EG", "")
    private val enTags = arrayOf("en-US", "en", "")

    private fun tags(arabic: Boolean) = if (arabic) arTags else enTags

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
        triedOnDevice = false
        tagIndex = 0
        lastArabic = arabic
        begin(arabic, false)
    }

    private fun begin(arabic: Boolean, onDevice: Boolean) {
        stop()
        if (!onDevice && !available()) {
            // no network recogniser registered; the on-device one may still exist
            if (Build.VERSION.SDK_INT >= 33 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
            ) {
                begin(arabic, true)
                return
            }
            sink?.onState(false, "ما لكيت محرك تعرّف صوت بالجهاز — نزّل تطبيق Google")
            return
        }
        val r = try {
            if (onDevice && Build.VERSION.SDK_INT >= 33)
                SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            else SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (e: Exception) {
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
                release()

                // the engine does not carry this dialect — step down the list
                if ((code == 12 || code == 13) && tagIndex < tags(lastArabic).size - 1) {
                    tagIndex++
                    begin(lastArabic, triedOnDevice)
                    return
                }

                // the network recogniser is the one that usually refuses inside a
                // keyboard; retry once on the device's own engine before giving up
                val retryable = code == SpeechRecognizer.ERROR_CLIENT ||
                    code == SpeechRecognizer.ERROR_NETWORK ||
                    code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    code == SpeechRecognizer.ERROR_SERVER
                if (!triedOnDevice && retryable && Build.VERSION.SDK_INT >= 33 &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
                ) {
                    triedOnDevice = true
                    begin(lastArabic, true)
                    return
                }
                sink?.onState(false, message(code))
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

        val list = tags(arabic)
        val tag = list[tagIndex.coerceIn(0, list.size - 1)]
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // an empty tag means: engine, use whatever you have
            if (tag.isNotEmpty()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
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
        SpeechRecognizer.ERROR_AUDIO -> "مشكلة بالمايك (٣)"
        SpeechRecognizer.ERROR_CLIENT -> "محرك الصوت رفض الطلب (٥)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "محتاج صلاحية المايك (٩)"
        SpeechRecognizer.ERROR_NETWORK -> "محتاج إنترنت (٢)"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "الإنترنت بطيء (١)"
        SpeechRecognizer.ERROR_NO_MATCH -> "ما سمعت شي (٧)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "المحرك مشغول (٨)"
        SpeechRecognizer.ERROR_SERVER -> "الخادم رفض (٤)"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ما سمعت صوت (٦)"
        10 -> "طلبات كثيرة، جرّب بعد شوي (١٠)"
        11 -> "انقطع الاتصال بالمحرك (١١)"
        12, 13 -> "محرك الصوت ما يدعم اللغة (١٢)"
        else -> "ما زبطت (خطأ $code)"
    }
}
