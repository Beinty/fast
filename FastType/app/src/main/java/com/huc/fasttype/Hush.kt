package com.huc.fasttype

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Keeps a notification chime out of a voice note.
 *
 * The chime goes out of the speaker and straight back into the microphone, so
 * the only way to keep it out of the recording is to stop it being played while
 * the microphone is open. Do Not Disturb would do that, but it would also hold
 * back the message itself and silence the phone for calls, which is a heavy
 * price for a few seconds.
 *
 * So nothing is blocked. Two volume levels — notifications and the system's own
 * sounds — are taken to zero for the length of the recording and put back at the
 * exact number they were on. The notification still arrives and still appears;
 * the ringer and anything playing as media are never touched.
 *
 * Android does not let an app lower those two without Do Not Disturb access, so
 * that is the permission it asks for — and never uses for anything else.
 */
object Hush {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var quiet = false
        private set

    /** The levels to put back, or -1 when nothing was changed. */
    private var hadNotif = -1
    private var hadSystem = -1

    /** Set while our own keyboard is listening, so voice typing is left alone. */
    @Volatile
    var ours = false

    fun allowed(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.isNotificationPolicyAccessGranted
    } catch (_: Throwable) {
        false
    }

    /** The system page where the one permission is granted. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private val release = Runnable { lastCtx?.let { off(it, "انتهت المهلة") } }
    private var lastCtx: Context? = null

    /** What to say happened, for the line of status in the settings screen. */
    @Volatile
    var note: String = ""
        private set

    fun on(ctx: Context) {
        if (quiet || !Store.recHush) return
        if (!allowed(ctx)) { note = "محتاج الإذن"; return }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            hadNotif = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            hadSystem = am.getStreamVolume(AudioManager.STREAM_SYSTEM)
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
            quiet = true
            note = "ساكت — التسجيل شغّال"
        } catch (_: SecurityException) {
            hadNotif = -1
            hadSystem = -1
            note = "محتاج الإذن"
            return
        } catch (_: Throwable) {
            hadNotif = -1
            hadSystem = -1
            return
        }
        // if the end of the recording is never reported, the phone must not be
        // left silent without him knowing
        lastCtx = ctx.applicationContext
        main.removeCallbacks(release)
        main.postDelayed(release, Store.recHushMinutes * 60_000L)
    }

    fun off(ctx: Context, why: String = "رجع الصوت") {
        main.removeCallbacks(release)
        if (!quiet) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            if (hadNotif >= 0) am?.setStreamVolume(AudioManager.STREAM_NOTIFICATION, hadNotif, 0)
            if (hadSystem >= 0) am?.setStreamVolume(AudioManager.STREAM_SYSTEM, hadSystem, 0)
        } catch (_: Throwable) {
        }
        hadNotif = -1
        hadSystem = -1
        quiet = false
        note = why
    }
}
