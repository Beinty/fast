package com.huc.fasttype

import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Translation, on the phone.
 *
 * ML Kit runs the model locally, so nothing typed here is ever sent anywhere; the
 * only time the network is touched is the first use of a language pair, when its
 * pack is fetched. Translators are expensive to build and cheap to keep, so one is
 * held per pair and closed only when the keyboard goes away.
 *
 * Every callback lands on the main thread, because the caller is a View.
 */
object Tr {

    class Lang(val code: String, val name: String)

    const val AUTO = "auto"

    /** The languages offered, in the order they appear in the picker. */
    val langs: List<Lang> = listOf(
        Lang(AUTO, "التعرّف التلقائي"),
        Lang("ar", "العربية"),
        Lang("en", "الإنجليزية"),
        Lang("tr", "التركية"),
        Lang("fa", "الفارسية"),
        Lang("fr", "الفرنسية"),
        Lang("de", "الألمانية"),
        Lang("es", "الإسبانية"),
        Lang("ru", "الروسية"),
        Lang("ur", "الأردية"),
        Lang("hi", "الهندية"),
        Lang("zh", "الصينية"),
        Lang("id", "الإندونيسية"),
        Lang("it", "الإيطالية"),
        Lang("nl", "الهولندية"),
        Lang("pt", "البرتغالية"),
        Lang("sv", "السويدية"),
        Lang("uk", "الأوكرانية")
    )

    fun nameOf(code: String): String =
        langs.firstOrNull { it.code == code }?.name ?: code

    private val main = Handler(Looper.getMainLooper())
    private val pool = HashMap<String, Translator>()

    /** Bumped on every request so a slow answer cannot overwrite a newer one. */
    private var seq = 0

    /** What to show the person while the first language pack comes down. */
    @Volatile var status: String = ""
        private set

    private fun mlCode(c: String): String? = try {
        TranslateLanguage.fromLanguageTag(c)
    } catch (_: Throwable) {
        null
    }

    private fun translator(from: String, to: String): Translator? {
        val f = mlCode(from) ?: return null
        val t = mlCode(to) ?: return null
        val key = "$f>$t"
        pool[key]?.let { return it }
        return try {
            val made = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(f)
                    .setTargetLanguage(t)
                    .build()
            )
            pool[key] = made
            made
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Translates [text] and hands the result back on the main thread. [done] is called
     * with null when nothing could be produced; [status] carries anything worth saying
     * in the meantime.
     */
    fun translate(text: String, srcPref: String, dst: String, done: (String?) -> Unit) {
        val mine = ++seq
        val body = text.trim()
        if (body.isEmpty()) {
            status = ""
            main.post { if (mine == seq) done("") }
            return
        }
        val from = if (srcPref == AUTO) guess(body, dst) else srcPref
        run(mine, body, from, dst, done)
    }

    /**
     * Which language he is typing in, read straight off the letters.
     *
     * A statistical identifier would be more general, but it means another model to
     * download and another round trip before a single word can be translated. The
     * script answers the only question that matters here — Arabic or not — with no
     * model and no wait, and anything it cannot place falls back to whichever side
     * of the pair he is not translating into.
     */
    private fun guess(text: String, dst: String): String {
        var arabic = 0
        var latin = 0
        var cyrillic = 0
        for (ch in text) {
            when {
                ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' -> arabic++
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
                ch in '\u0400'..'\u04FF' -> cyrillic++
            }
        }
        val best = when {
            arabic >= latin && arabic >= cyrillic && arabic > 0 -> "ar"
            latin >= cyrillic && latin > 0 -> "en"
            cyrillic > 0 -> "ru"
            else -> ""
        }
        if (best.isNotEmpty() && best != dst) return best
        return if (dst == "ar") "en" else "ar"
    }

    private fun run(mine: Int, body: String, from: String, to: String, done: (String?) -> Unit) {
        if (from == to) {
            status = ""
            main.post { if (mine == seq) done(body) }
            return
        }
        val tr = translator(from, to)
        if (tr == null) {
            status = "اللغة مو مدعومة"
            main.post { if (mine == seq) done(null) }
            return
        }
        // free on a pair already on the phone; the first time it fetches the pack
        val conds = DownloadConditions.Builder().build()
        status = "ينزّل ملف اللغة…"
        tr.downloadModelIfNeeded(conds)
            .addOnSuccessListener {
                status = ""
                if (mine != seq) return@addOnSuccessListener
                tr.translate(body)
                    .addOnSuccessListener { out ->
                        if (mine == seq) main.post { done(out) }
                    }
                    .addOnFailureListener {
                        if (mine == seq) main.post { done(null) }
                    }
            }
            .addOnFailureListener {
                status = "ما نزل ملف اللغة — تأكّد من النت"
                if (mine == seq) main.post { done(null) }
            }
    }

    /** Called when the keyboard window goes away; the pool is rebuilt on demand. */
    fun release() {
        for (t in pool.values) {
            try { t.close() } catch (_: Throwable) {}
        }
        pool.clear()
        status = ""
        seq++
    }

    /**
     * Builds the pair and starts fetching its pack before a single key is pressed,
     * so the first word he types is not the thing that waits for the download.
     */
    fun warm(from: String, to: String) {
        val f = if (from == AUTO) "en" else from
        val t = translator(f, to) ?: return
        try {
            status = "ينزّل ملف اللغة…"
            t.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { status = "" }
                .addOnFailureListener { status = "" }
        } catch (_: Throwable) {
            status = ""
        }
        // the other direction is one tap away, so have it ready too
        if (from == AUTO) translator("ar", to)
    }
}
