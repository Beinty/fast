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
 * The one place that talks to OpenAI.
 *
 * Nothing here touches a view, because every caller is already off the main thread
 * or wants to stay off it; [ask] runs on its own small pool and hands the answer
 * back on that same background thread. The caller decides where to post it.
 *
 * The key is not in this file and must never be: it arrives through BuildConfig,
 * which the build workflow fills from a GitHub secret. A key committed to a repo
 * is a key OpenAI revokes.
 */
object Ai {

    /**
     * The cheapest model that still writes decent Arabic. Replies here are one or
     * two sentences, so a bigger model buys nothing but latency and cost.
     */
    const val MODEL = "gpt-4o-mini"

    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"

    /** Long enough for a slow Iraqi connection, short enough not to hang a reply. */
    private const val CONNECT_MS = 10_000
    private const val READ_MS = 25_000

    private val pool = Executors.newFixedThreadPool(2)

    /** True when a key was baked into this build at all. */
    fun configured(): Boolean = BuildConfig.OPENAI_KEY.isNotBlank()

    class Result(val text: String?, val error: String?)

    /**
     * Sends [system] and [user] to the model and calls [done] with whatever came
     * back, on a background thread.
     *
     * Every failure path produces a Result with [Result.error] set rather than an
     * exception, because the caller is a notification listener and a crash there
     * takes the whole service down with it.
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
                body.put("model", MODEL)
                body.put("temperature", 0.7)
                // a reply longer than this is not a reply, it is an essay
                body.put("max_tokens", 160)
                val msgs = JSONArray()
                msgs.put(JSONObject().put("role", "system").put("content", system))
                msgs.put(JSONObject().put("role", "user").put("content", user))
                body.put("messages", msgs)

                conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_MS
                    readTimeout = READ_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer " + BuildConfig.OPENAI_KEY)
                }

                val out: OutputStream = conn.outputStream
                out.write(body.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                out.close()

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

                if (code !in 200..299) {
                    done(Result(null, readError(code, raw)))
                    return@execute
                }

                val text = JSONObject(raw)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()

                if (text.isNullOrEmpty()) done(Result(null, "رد فارغ"))
                else done(Result(clean(text), null))

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
            401 -> "المفتاح غلط أو منتهي"
            429 -> "تجاوزت الحد أو خلص الرصيد"
            in 500..599 -> "سيرفر OpenAI واقع"
            else -> if (msg.isNotEmpty()) msg else "خطأ $code"
        }
    }

    /**
     * Models like to wrap a reply in quotes or prefix it with the sender's name.
     * Both look wrong once the text lands in a chat bubble, so they come off here.
     */
    private fun clean(s: String): String {
        var t = s.trim()
        if (t.length > 1 && t.first() == '"' && t.last() == '"') t = t.substring(1, t.length - 1)
        if (t.length > 1 && t.first() == '«' && t.last() == '»') t = t.substring(1, t.length - 1)
        return t.trim()
    }
}
