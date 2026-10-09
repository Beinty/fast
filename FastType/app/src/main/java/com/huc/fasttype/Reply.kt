package com.huc.fasttype

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Calendar

/**
 * Answers incoming messages by itself.
 *
 * Android gives a keyboard no sight of anything outside the field it is typing in,
 * so this cannot live in the keyboard. It is a notification listener instead: the
 * message arrives as a notification, the reply goes back out through the Reply
 * action the messaging app already attached to it. The messaging app is never
 * opened and its own API is never touched.
 *
 * Everything that follows is about *not* sending. A reply goes out as him, with no
 * chance to read it first, so each gate below exists because of a specific way that
 * goes wrong — see the comment on each one.
 */
class Reply : NotificationListenerService() {

    companion object {
        /** The apps worth listening to, and what to call them in the log. */
        private val APPS = mapOf(
            "com.whatsapp" to "واتساب",
            "com.whatsapp.w4b" to "واتساب بزنس",
            "org.telegram.messenger" to "تلكرام",
            "org.telegram.messenger.web" to "تلكرام",
            "com.google.android.apps.messaging" to "الرسائل",
            "com.samsung.android.messaging" to "الرسائل"
        )

        fun appName(pkg: String): String = APPS[pkg] ?: pkg

        /** Turns kept per conversation, counting both sides. */
        private const val HISTORY_MAX = 8

        /** Set while the service is bound, so the settings screen can say so. */
        @Volatile
        var running: Boolean = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Conversations already answered, cleared when he opens the chat.
     *
     * Without this, two phones running this feature answer each other until one of
     * them is banned.
     */
    private val answered = HashSet<String>()

    /** Last message seen per conversation; messaging apps repost the same one. */
    private val lastSeen = HashMap<String, String>()

    /**
     * Recent turns per conversation, oldest first.
     *
     * A notification carries one message and no history, so without this the
     * model is answering "شگد؟" having never seen what it refers to. Held in
     * memory only — it dies with the service, which is the right lifetime for
     * other people's messages.
     */
    private val history = HashMap<String, ArrayList<Ai.Turn>>()

    private fun remember(key: String, fromMe: Boolean, text: String) {
        val list = history.getOrPut(key) { ArrayList() }
        list.add(Ai.Turn(fromMe, text))
        // enough for the thread to make sense, short enough to stay cheap
        while (list.size > HISTORY_MAX) list.removeAt(0)
    }

    override fun onListenerConnected() {
        running = true
        Store.load(this)
    }

    override fun onListenerDisconnected() {
        running = false
    }

    /**
     * He opened the chat, so the notification went away. That is the only reliable
     * signal we get that he is present, and it reopens the conversation for one
     * more automatic reply later.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.let { convKey(it) } ?: return
        answered.remove(key)
        lastSeen.remove(key)
        history.remove(key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        try {
            handle(n)
        } catch (_: Throwable) {
            // a throw here kills the listener for every app, so nothing escapes
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (!Store.arOn) return

        val pkg = sbn.packageName ?: return
        if (!appEnabled(pkg)) return

        val n = sbn.notification ?: return

        // a progress bar, a "backing up" banner, a silent group header: not a message
        if (sbn.isOngoing) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val ex = n.extras ?: return
        val who = (ex.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().trim()
        var body = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString().trim()
        if (who.isEmpty() || body.isEmpty()) return

        // "3 new messages" and friends carry no content to answer
        if (body.matches(Regex("^\\d+\\s.*رسائل.*$")) ||
            body.matches(Regex("^\\d+ new messages?$", RegexOption.IGNORE_CASE))
        ) return

        val group = isGroup(ex)
        // One wrong reply in a group of fifty is seen by fifty people, so a group
        // is answered only when he has explicitly turned that on.
        if (group && !Store.arGroups) return

        // In a group the body is "Sender: text"; the name is not part of the message
        if (group) {
            val i = body.indexOf(": ")
            if (i in 1..40) body = body.substring(i + 2).trim()
        }
        if (body.isEmpty()) return

        val key = convKey(sbn)

        // the same notification reposted while he has not opened it
        if (lastSeen[key] == body) return
        lastSeen[key] = body

        if (Store.arOnce && answered.contains(key)) return
        if (!allowed(who)) return
        if (hasStopWord(body)) {
            log(pkg, who, body, "كلمة موقوفة — ما انرسل رد", false)
            return
        }
        if (!inHours()) return

        // Nothing left to stop it, so find the way back out before spending a call
        val action = n.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.resultKey != null } == true
        } ?: return

        if (Store.arOnce) answered.add(key)
        remember(key, false, body)

        // No network means no model, so the only honest options are a line he
        // wrote himself or silence. Checked up front to avoid a doomed call.
        if (!online()) {
            val canned = Store.arOfflineMsg.trim()
            if (!Store.arOffline || canned.isEmpty()) {
                answered.remove(key)
                log(pkg, who, body, "ماكو نت — ما انرسل رد", false)
                return
            }
            deliver(key, action, pkg, who, body, canned, offline = true)
            return
        }

        val system = prompt(who, group)
        val turns = ArrayList(history[key] ?: emptyList())
        Ai.ask(system, turns, Store.arSearch) { r ->
            val text = r.text
            if (text.isNullOrBlank()) {
                // a failed call must not silently consume the one allowed reply
                answered.remove(key)
                val canned = Store.arOfflineMsg.trim()
                if (Store.arOffline && canned.isNotEmpty()) {
                    if (Store.arOnce) answered.add(key)
                    deliver(key, action, pkg, who, body, canned, offline = true)
                } else {
                    log(pkg, who, body, r.error ?: "ما طلع رد", false)
                }
                return@ask
            }
            deliver(key, action, pkg, who, body, text, offline = false)
        }
    }

    /**
     * Waits out the delay, then sends.
     *
     * An instant answer reads as a machine and removes his chance to get there
     * first, so the wait is deliberate — but it is his setting, and zero is
     * allowed.
     */
    private fun deliver(
        key: String,
        action: Notification.Action,
        pkg: String,
        who: String,
        inq: String,
        text: String,
        offline: Boolean
    ) {
        main.postDelayed({
            val sent = send(action, text)
            if (!sent) answered.remove(key)
            if (sent) remember(key, true, text)
            val shown = if (offline && sent) "$text  (رد جاهز — ماكو نت)" else text
            log(pkg, who, inq, if (sent) shown else "ما انرسل — الإشعار راح", sent)
        }, Store.arDelay.toLong() * 1000L)
    }

    /** Whether anything is actually reachable right now. */
    private fun online(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Throwable) {
        // if the check itself fails, let the call decide rather than block it
        true
    }

    // ---------- gates ----------

    private fun appEnabled(pkg: String): Boolean = when {
        pkg.startsWith("com.whatsapp") -> Store.arWhats
        pkg.startsWith("org.telegram") -> Store.arTg
        pkg == "com.google.android.apps.messaging" ||
            pkg == "com.samsung.android.messaging" -> Store.arSms
        else -> false
    }

    private fun isGroup(ex: Bundle): Boolean {
        if (Build.VERSION.SDK_INT >= 28 &&
            ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        ) return true
        return ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null
    }

    /** 0 answers everyone, 1 only the named, 2 everyone except the named. */
    private fun allowed(who: String): Boolean {
        val names = Store.arList.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return when (Store.arMode) {
            1 -> names.any { who.contains(it, true) }
            2 -> names.none { who.contains(it, true) }
            else -> true
        }
    }

    /**
     * The impersonation guard. Someone writing as a friend and asking for a
     * transfer gets no automatic answer in his name, ever.
     */
    private fun hasStopWord(body: String): Boolean {
        val words = Store.arStop.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return words.any { body.contains(it, true) }
    }

    private fun inHours(): Boolean {
        if (!Store.arHours) return true
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val from = Store.arFrom
        val to = Store.arTo
        return if (from <= to) h in from until to else (h >= from || h < to)
    }

    // ---------- the message to the model ----------

    private fun prompt(who: String, group: Boolean): String {
        val tone = when (Store.arStyle) {
            1 -> "اكتب باللهجة العراقية العامية."
            2 -> "اكتب بالعربية الفصحى."
            else -> "رد بنفس لغة ولهجة الرسالة الواصلة — إذا جتك عراقي رد عراقي، إذا إنكليزي رد إنكليزي."
        }
        val sb = StringBuilder()
        sb.append("إنت ترد على رسالة نيابةً عن صاحب الهاتف. اكتب الرد فقط، بدون مقدمات وبدون علامات اقتباس وبدون ذكر اسمك.\n")
        sb.append(tone).append("\n")
        sb.append("خلّي الرد قصير — جملة أو جملتين.\n")
        sb.append("المرسل اسمه: ").append(who).append("\n")
        sb.append("الرسائل الي قبل هي سياق المحادثة. رد على آخر رسالة بس.\n")
        // the failure that matters: a confident wrong answer sent as him
        sb.append("لا تخترع معلومة ولا تأكد شي ما تعرفه. إذا ما عندك الجواب، كول إنه راح يتأكد ويرد.\n")
        if (group) sb.append("هذي رسالة بكروب، فخلّي ردك عام ومختصر.\n")
        // His own instructions come last so they win over anything above.
        val own = Store.arPersona.trim()
        if (own.isNotEmpty()) sb.append("تعليمات صاحب الهاتف: ").append(own).append("\n")
        sb.append("إذا الرسالة تحتاج قرار أو معلومة ما تعرفها، كول إنه راح يرد بنفسه بعد شوية.")
        return sb.toString()
    }

    // ---------- out ----------

    private fun send(action: Notification.Action, text: String): Boolean {
        return try {
            val inputs = action.remoteInputs ?: return false
            val bundle = Bundle()
            for (ri in inputs) bundle.putCharSequence(ri.resultKey, text)
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, bundle)
            if (Build.VERSION.SDK_INT >= 28) {
                RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            }
            action.actionIntent.send(this, 0, intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun convKey(sbn: StatusBarNotification): String =
        sbn.packageName + "|" + (sbn.tag ?: "") + "|" + sbn.id

    private fun log(pkg: String, who: String, inq: String, out: String, ok: Boolean) {
        try {
            Store.addReplyLog(this, pkg, who, inq, out, ok)
        } catch (_: Throwable) {}
    }
}
