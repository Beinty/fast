package com.huc.fasttype

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Voice typing. Wraps the system speech recogniser and reports what it hears back to
 * the keyboard: partial text while the person is still talking, then the final text.
 *
 * Two things make this fragile inside a keyboard, and both are handled here. Tearing
 * the recogniser down from inside one of its own callbacks leaves the binding in a bad
 * state and the next attempt comes back as ERROR_SERVER_DISCONNECTED, so every
 * teardown is posted instead. And recognisers reject a dialect tag they do not carry,
 * so each language is a list that steps down to letting the engine choose.
 */
class Voice(private val ctx: Context) {

    companion object {
        /** Everything this class does is logged here: `adb logcat -s HUCVOICE`. */
        const val TAG = "HUCVOICE"
    }


    interface Sink {
        /** Called repeatedly with the best guess so far. */
        fun onPartial(text: String)
        /** Called once with the finished text; empty when nothing was heard. */
        fun onFinal(text: String)
        /** Listening started, stopped, or failed — [message] is already user-facing. */
        fun onState(listening: Boolean, message: String)
    }

    var sink: Sink? = null

    private val ui = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var active = false

    private var arabic = false
    private var tagIndex = 0
    private var onDevice = false
    private var disconnects = 0

    /** Recognition services on this phone, best first. Built once. */
    private var services: List<ComponentName>? = null
    private var serviceIndex = 0

    private val arTags = arrayOf("ar", "ar-SA", "ar-EG", "")
    private val enTags = arrayOf("en-US", "en", "")
    private fun tags() = if (arabic) arTags else enTags

    val isListening: Boolean get() = active

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun available(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(ctx) || engines().isNotEmpty()

    /**
     * Every recognition service installed, best first.
     *
     * The device default is not to be trusted. On this phone it points at
     * com.google.android.tts, which is the speech *synthesis* package: its
     * recognition service binds and drops the connection straight away, which the
     * framework reports as ERROR_SERVER_DISCONNECTED. So the Google app is preferred,
     * anything else comes next, and the TTS package is kept as a last resort.
     */
    private fun engines(): List<ComponentName> {
        services?.let { return it }
        val found = ArrayList<Pair<Int, ComponentName>>()
        try {
            val pm = ctx.packageManager
            val q = pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            for (ri in q) {
                val si = ri.serviceInfo ?: continue
                val cn = ComponentName(si.packageName, si.name)
                val score = when {
                    si.packageName == "com.google.android.googlequicksearchbox" -> 0
                    si.packageName.startsWith("com.google.android.as") -> 1
                    si.packageName == "com.google.android.tts" -> 90
                    else -> 50
                }
                found.add(score to cn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not list recognition services", e)
        }
        found.sortBy { it.first }
        val list = found.map { it.second }
        Log.i(TAG, "engines found: " + list.joinToString { it.packageName })
        services = list
        return list
    }

    fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

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

    fun start(useArabic: Boolean) {
        Log.i(TAG, "start arabic=$useArabic perm=${hasPermission()} " +
            "default=${SpeechRecognizer.isRecognitionAvailable(ctx)} " +
            "onDev=${onDeviceAvailable()} sdk=${Build.VERSION.SDK_INT} " +
            "engines=${engines().size}")
        arabic = useArabic
        tagIndex = 0
        onDevice = false
        disconnects = 0
        serviceIndex = 0
        teardown()
        ui.postDelayed({ begin() }, 60)
    }

    fun stop() {
        active = false
        teardown()
    }

    /** Drops the recogniser on the main thread, never inside one of its callbacks. */
    private fun teardown() {
        val r = rec ?: return
        rec = null
        ui.post {
            try {
                r.cancel()
                r.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /** Waits for the old binding to let go before trying again. */
    private fun retryAfter(ms: Long) {
        teardown()
        ui.postDelayed({ begin() }, ms)
    }

    private fun begin() {
        if (!onDevice && !available()) {
            if (onDeviceAvailable()) {
                onDevice = true
            } else {
                sink?.onState(false, "ما لكيت محرك تعرّف صوت بالجهاز")
                return
            }
        }

        val engineList = engines()
        val picked = engineList.getOrNull(serviceIndex)
        val r = try {
            when {
                onDevice && Build.VERSION.SDK_INT >= 33 ->
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                // naming the service skips the device's broken default
                picked != null -> SpeechRecognizer.createSpeechRecognizer(ctx, picked)
                else -> SpeechRecognizer.createSpeechRecognizer(ctx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "create failed onDevice=$onDevice svc=$picked", e)
            sink?.onState(false, "ما كدرت أشغّل المايك")
            return
        }
        Log.i(TAG, "using " + (if (onDevice) "on-device" else picked?.packageName ?: "default"))
        rec = r

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                Log.i(TAG, "ready — engine is listening")
                active = true
                disconnects = 0
                sink?.onState(true, "تفضّل… أسمعك")
            }

            override fun onBeginningOfSpeech() { Log.i(TAG, "speech began") }
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.i(TAG, "speech ended")
                sink?.onState(true, "لحظة…")
            }

            override fun onError(code: Int) {
                Log.w(TAG, "error $code (${message(code)}) onDevice=$onDevice tag=$tagIndex")
                active = false
                ui.post { handleError(code) }
            }

            override fun onResults(results: Bundle?) {
                active = false
                val best = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                Log.i(TAG, "final: '" + best + "'")
                sink?.onFinal(best)
                teardown()
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

        val list = tags()
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
            if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        Log.i(TAG, "startListening tag='" + tag + "' onDevice=" + onDevice)
        try {
            r.startListening(i)
        } catch (e: Exception) {
            Log.e(TAG, "startListening threw", e)
            active = false
            sink?.onState(false, "ما كدرت أشغّل المايك")
            teardown()
        }
    }

    /** Runs on the main thread, never inside a recogniser callback. */
    private fun handleError(code: Int) {
        // the engine does not carry this dialect — step down the list
        if ((code == 12 || code == 13) && tagIndex < tags().size - 1) {
            tagIndex++
            retryAfter(250)
            return
        }

        // the service dropped the binding; give it a moment and reconnect
        if (code == 11 && disconnects < 2) {
            disconnects++
            retryAfter(500)
            return
        }

        // this engine does not work here — try the next one installed
        if (!onDevice && serviceIndex < engines().size - 1) {
            serviceIndex++
            tagIndex = 0
            disconnects = 0
            Log.i(TAG, "switching to engine #" + serviceIndex)
            retryAfter(350)
            return
        }

        // the networked engine refuses inside a keyboard on some phones — the
        // device's own engine usually does not
        val switchable = code == SpeechRecognizer.ERROR_CLIENT ||
            code == SpeechRecognizer.ERROR_NETWORK ||
            code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            code == SpeechRecognizer.ERROR_SERVER ||
            code == 11
        if (!onDevice && switchable && onDeviceAvailable()) {
            onDevice = true
            tagIndex = 0
            disconnects = 0
            retryAfter(300)
            return
        }

        Log.w(TAG, "giving up on error $code")
        teardown()
        sink?.onState(false, message(code))
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
        11 -> "ماكو محرك تعرّف صوت شغّال بالجهاز — نزّل تطبيق Google (١١)"
        12, 13 -> "محرك الصوت ما يدعم اللغة (١٢)"
        else -> "ما زبطت (خطأ $code)"
    }
}
