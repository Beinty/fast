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
 * which the build fills from a GitHub secret.
 */
object Ai {

    /**
     * The cheapest current Flash model. Replies here are a sentence or two, so a
     * larger model buys nothing but latency.
     */
    const val MODEL = "gemini-3.5-flash-lite"

    private const val HOST = "https://generativelanguage.googleapis.com/v1beta/models/"

    private const val CONNECT_MS = 8_000
    private const val READ_MS = 20_000

    private val pool = Executors.newFixedThreadPool(2)

    /** One side of the conversation. [fromMe] is the phone's owner, not the sender. */
    class Turn(val fromMe: Boolean, val text: String)

    fun configured(): Boolean = BuildConfig.GEMINI_KEY.isNotBlank()

    class Result(val text: String?, val error: String?)

    /**
     * Sends the conversation to the model and calls [done] with the reply, on a
     * background thread.
     *
     * [turns] is oldest-first and ends with the message being answered; without
     * the ones before it the model answers "شگد؟" with something generic, because
     * that is all it was shown.
     *
     * A first failure is retried once. On a phone connection most failures are a
     * dropped handshake rather than a real refusal, and one retry turns most of
     * those into an answer.
     *
     * Every failure path produces a Result rather than an exception: the caller is
     * a notification listener, and a throw there takes the listener down for every
     * app on the phone.
     */
    fun ask(system: String, turns: List<Turn>, search: Boolean, done: (Result) -> Unit) {
        if (!configured()) {
            done(Result(null, "ماكو مفتاح — راجع الإعدادات"))
            return
        }
        if (turns.isEmpty()) {
            done(Result(null, "ماكو نص"))
            return
        }
        pool.execute {
            val first = call(system, turns, search)
            if (first.text != null || !worthRetry(first.error)) {
                done(first)
                return@execute
            }
            try { Thread.sleep(1200) } catch (_: Throwable) {}
            done(call(system, turns, search))
        }
    }

    /** A bad key or a quota wall will fail again; a dropped connection may not. */
    private fun worthRetry(error: String?): Boolean {
        val e = error ?: return false
        return e.contains("ما وصل") || e.contains("واقع")
    }

    private fun call(system: String, turns: List<Turn>, search: Boolean): Result {
        var conn: HttpURLConnection? = null
        return try {
            val contents = JSONArray()
            for (t in turns) {
                contents.put(
                    JSONObject()
                        .put("role", if (t.fromMe) "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", t.text)))
                )
            }

            val body = JSONObject()
            body.put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            body.put("contents", contents)
            body.put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.6)
                    // headroom: these models spend tokens thinking before they
                    // write, and a tight cap comes back as an empty answer
                    .put("maxOutputTokens", 512)
            )
            if (search) {
                body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }

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
            val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

            if (code !in 200..299) return Result(null, readError(code, raw))

            val root = JSONObject(raw)
            val cand = root.optJSONArray("candidates")?.optJSONObject(0)
            // a grounded answer can arrive split across several parts
            val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val piece = parts.optJSONObject(i)?.optString("text") ?: ""
                    if (piece.isNotEmpty()) sb.append(piece)
                }
            }
            val text = sb.toString().trim()

            if (text.isEmpty()) {
                val why = cand?.optString("finishReason") ?: ""
                val blocked = root.optJSONObject("promptFeedback")?.optString("blockReason") ?: ""
                Result(null, when {
                    blocked.isNotEmpty() -> "الرسالة انحجبت — $blocked"
                    why.isNotEmpty() -> "ما طلع رد — $why"
                    else -> "رد فارغ"
                })
            } else {
                Result(clean(text), null)
            }

        } catch (e: Throwable) {
            Result(null, "ما وصل للسيرفر — " + (e.message ?: "خطأ"))
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun readError(code: Int, raw: String): String {
        val msg = try {
            JSONObject(raw).optJSONObject("error")?.optString("message") ?: ""
        } catch (_: Throwable) {
            ""
        }
        return when (code) {
            400 -> if (msg.contains("API key", true)) "المفتاح غلط" else msg.ifEmpty { "طلب غلط" }
            401, 403 -> "المفتاح مرفوض أو ماكو صلاحية"
            429 -> "تجاوزت حد الاستخدام المجاني — انتظر شوية"
            in 500..599 -> "سيرفر Google واقع"
            else -> msg.ifEmpty { "خطأ $code" }
        }
    }

    /**
     * Models like to wrap a reply in quotes, open with the sender's name, or leave
     * a grounding footnote behind. All three look wrong in a chat bubble.
     */
    private fun clean(s: String): String {
        var t = s.trim()
        if (t.length > 1 && t.first() == '"' && t.last() == '"') t = t.substring(1, t.length - 1)
        if (t.length > 1 && t.first() == '«' && t.last() == '»') t = t.substring(1, t.length - 1)
        // "[1]" style citation markers that grounding adds
        t = t.replace(Regex("\\s*\\[\\d+(,\\s*\\d+)*]"), "")
        return t.trim()
    }
}
