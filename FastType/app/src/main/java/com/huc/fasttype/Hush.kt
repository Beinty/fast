package com.huc.fasttype

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Keeps a notification chime out of a voice note.
 *
 * The chime goes out of the speaker and straight back into the microphone, so
 * the only way to keep it out of the recording is to stop it being played while
 * the microphone is open. Do Not Disturb would do that, but it would also hold
 * back the message and silence the phone for calls — far too much for a few
 * seconds. So nothing is blocked: two volume levels, notifications and the
 * system's own sounds, go to zero and come back at the number they were on.
 *
 * Everything below exists because of one rule: **the sound must come back**.
 * A missed callback, a killed process, a recording the system forgets to report
 * the end of — none of them may leave the phone silent. So the levels are
 * written to disk the moment they are taken, the microphone is polled rather
 * than trusted, and anything that starts the app puts the sound back first.
 */
object Hush {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var quiet = false
        private set

    /** Set while our own keyboard is listening, so voice typing is left alone. */
    @Volatile
    var ours = false

    /** What to show in the settings screen. */
    @Volatile
    var note: String = ""
        private set

    private const val POLL_MS = 600L
    private var app: Context? = null

    fun allowed(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.isNotificationPolicyAccessGranted
    } catch (_: Throwable) {
        false
    }

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---- the microphone, asked rather than trusted --------------------------

    private val MIC_SOURCES = intArrayOf(0, 1, 5, 6, 7, 9)   // DEFAULT..UNPROCESSED

    private fun micBusy(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return false
            val list: List<AudioRecordingConfiguration> = am.activeRecordingConfigurations
            for (c in list) {
                val s = c.clientAudioSource
                for (m in MIC_SOURCES) if (m == s) return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * While the sound is down, the microphone is checked twice a second rather
     * than waited on. The end of someone else's recording is not always reported,
     * and a chime that never comes back is worse than one that was never muted.
     */
    private val poll = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            if (!quiet) return
            if (!micBusy(ctx)) { off(ctx, "رجع الصوت"); return }
            main.postDelayed(this, POLL_MS)
        }
    }

    private val giveUp = Runnable {
        app?.let { off(it, "رجع الصوت — انتهت المهلة") }
    }

    // ---- taking the sound down and putting it back --------------------------

    fun on(ctx: Context) {
        if (quiet || ours || !Store.recHush) return
        if (!allowed(ctx)) { note = "محتاج الإذن"; return }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        app = ctx.applicationContext

        val n = try { am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) } catch (_: Throwable) { 0 }
        val s = try { am.getStreamVolume(AudioManager.STREAM_SYSTEM) } catch (_: Throwable) { 0 }
        // already silent: there is nothing to do, and saving a zero as the level
        // to come back to is how a phone gets stuck quiet
        if (n <= 0 && s <= 0) { note = "الجهاز أصلاً ساكت"; return }

        try {
            Store.setHushSaved(ctx, n, s)
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
        } catch (_: SecurityException) {
            Store.clearHushSaved(ctx)
            note = "محتاج الإذن"
            return
        } catch (_: Throwable) {
            Store.clearHushSaved(ctx)
            return
        }

        quiet = true
        note = "ساكت — التسجيل شغّال"
        main.removeCallbacks(poll)
        main.removeCallbacks(giveUp)
        main.postDelayed(poll, POLL_MS)
        main.postDelayed(giveUp, Store.recHushMinutes * 60_000L)
    }

    fun off(ctx: Context, why: String = "رجع الصوت") {
        main.removeCallbacks(poll)
        main.removeCallbacks(giveUp)
        val n = Store.hushNotif
        val s = Store.hushSystem
        quiet = false
        if (n < 0 && s < 0) return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (n >= 0) am?.setStreamVolume(AudioManager.STREAM_NOTIFICATION, n, 0)
            if (s >= 0) am?.setStreamVolume(AudioManager.STREAM_SYSTEM, s, 0)
        } catch (_: Throwable) {
        }
        Store.clearHushSaved(ctx)
        note = why
    }

    /**
     * Called by everything that starts: the service, the keyboard, the settings
     * screen. If a level was written down and never put back — the process died
     * mid-recording, say — this is where the phone gets its sound back.
     */
    fun recover(ctx: Context) {
        if (Store.hushNotif < 0 && Store.hushSystem < 0) return
        if (quiet && micBusy(ctx)) return
        off(ctx, "رجع الصوت")
    }
}
