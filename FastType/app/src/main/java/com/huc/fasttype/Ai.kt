package com.huc.fasttype

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The one place that talks to the model.
 *
 * Gemini rather than OpenAI: the free tier needs no card, which is what makes
 * this usable from here at all.
 *
 * Nothing here touches a view. [ask] runs on its own small pool and hands the
 * answer back on that same background thread; the caller decides where to post it.
 *
 * The key is not in this file and must never be. It arrives through BuildConfig,
 * which the build fills from a GitHub secret. A key committed to a repo is a key
 * that gets found and spent.
 */
object Ai {

    /**
     * The cheapest current Flash model. Replies here are a sentence or two, so a
     * larger model buys nothing but latency.
     */
    const val MODEL = "gemini-3.5-flash-lite"

    private const val HOST = "https://generativelanguage.googleapis.com/v1beta/models/"

    /** Long enough for a slow connection, short enough not to hang a reply. */
    private const val CONNECT_MS = 10_000
    private const val READ_MS = 25_000

    private val pool = Executors.newFixedThreadPool(2)

    /** True when a key was baked into this build at all. */
    fun configured(): Boolean = BuildConfig.GEMINI_KEY.isNotBlank()

    class Result(val text: String?, val error: String?)

    /**
     * Sends [system] and [user] to the model and calls [done] with whatever came
     * back, on a background thread.
     *
     * Every failure path produces a Result with [Result.error] set rather than an
     * exception: the caller is a notification listener, and a throw there takes
     * the listener down for every app on the phone.
     */
    fun ask(system: String, user: String, done: (Result) -> Unit) {
        if (!configured()) {
            done(Result(null, "ماكو مفتاح — راجع الإعدادات"))
            return
        }
        pool.execute {
            var conn: HttpURLConnection? = null
            try {
                val body = JSONObject()
                body.put(
                    "system_instruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
                )
                body.put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", user)))
                    )
                )
                body.put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.7)
                        // headroom: these models spend tokens thinking before they
                        // write, and a tight cap comes back as an empty answer
                        .put("maxOutputTokens", 512)
                )

                conn = (URL(HOST + MODEL + ":generateContent").openConnection()
                    as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_MS
                    readTimeout = READ_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_KEY)
                }

                val out: OutputStream = conn.outputStream
                out.write(body.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                out.close()

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    .use { it.readText() }

                if (code !in 200..299) {
                    done(Result(null, readError(code, raw)))
                    return@execute
                }

                val root = JSONObject(raw)
                val cand = root.optJSONArray("candidates")?.optJSONObject(0)
                val text = cand
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()

                if (text.isNullOrEmpty()) {
                    // why it produced nothing matters more than the fact; a safety
                    // block and a token cap look identical without it
                    val why = cand?.optString("finishReason") ?: ""
                    val blocked = root.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""
                    done(Result(null, when {
                        blocked.isNotEmpty() -> "الرسالة انحجبت — $blocked"
                        why.isNotEmpty() -> "ما طلع رد — $why"
                        else -> "رد فارغ"
                    }))
                } else {
                    done(Result(clean(text), null))
                }

            } catch (e: Throwable) {
                done(Result(null, "ما وصل للسيرفر — " + (e.message ?: "خطأ")))
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }
    }

    /** Turns the API's own error shape into something readable in the log. */
    private fun readError(code: Int, raw: String): String {
        val msg = try {
            JSONObject(raw).optJSONObject("error")?.optString("message") ?: ""
        } catch (_: Throwable) {
            ""
        }
        return when (code) {
            400 -> if (msg.contains("API key", true)) "المفتاح غلط" else (msg.ifEmpty { "طلب غلط" })
            401, 403 -> "المفتاح مرفوض أو ماكو صلاحية"
            429 -> "تجاوزت حد الاستخدام المجاني — انتظر شوية"
            in 500..599 -> "سيرفر Google واقع"
            else -> msg.ifEmpty { "خطأ $code" }
        }
    }

    /**
     * Models like to wrap a reply in quotes or open with the sender's name. Both
     * look wrong once the text lands in a chat bubble, so they come off here.
     */
    private fun clean(s: String): String {
        var t = s.trim()
        if (t.length > 1 && t.first() == '"' && t.last() == '"') t = t.substring(1, t.length - 1)
        if (t.length > 1 && t.first() == '«' && t.last() == '»') t = t.substring(1, t.length - 1)
        return t.trim()
    }
}
