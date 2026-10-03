package com.huc.fasttype

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent

/**
 * Where text dictated through the system's own voice screen waits.
 *
 * An input method cannot start an activity and read its result, so the mic key opens
 * [VoicePermActivity], which runs the recogniser, drops what it heard here, and
 * closes. The keyboard picks it up the moment it comes back on screen.
 */
object VoiceResult {
    @Volatile
    var pending: String? = null

    fun take(): String? {
        val t = pending
        pending = null
        return t
    }
}

/**
 * A window with nothing in it. It asks for the microphone if that has not been granted
 * yet, then hands off to the system's voice-input screen — the one the Google app puts
 * up — and parks the result for the keyboard.
 */
class VoicePermActivity : Activity() {

    companion object {
        /** Put the BCP-47 tag to dictate in under this key. */
        const val EXTRA_LANG = "huc_lang"
        private const val REQ_PERM = 1
        private const val REQ_SPEECH = 2
    }

    private var lang = "ar"

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        overridePendingTransition(0, 0)
        lang = intent?.getStringExtra(EXTRA_LANG) ?: "ar"

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM)
            return
        }
        listen()
    }

    override fun onRequestPermissionsResult(
        code: Int, perms: Array<out String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "احچي الحين")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            startActivityForResult(i, REQ_SPEECH)
        } catch (_: Exception) {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_SPEECH && res == RESULT_OK) {
            val best = data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (best.isNotEmpty()) VoiceResult.pending = best
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
