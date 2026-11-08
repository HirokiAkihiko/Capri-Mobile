package com.capri.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LM Studio as her brain: whatever model you loaded on your PC, over your own Wi-Fi, through its OpenAI-style local
 * server (same requests as the desktop app). Nothing leaves your network, nothing is downloaded to the phone. If the
 * loaded model is a vision model she can look at your screen with it. All of this blocks: call it off the main thread.
 */
class LmStudio(url: String, private val key: String = "") {
    data class Info(val id: String, val loaded: Boolean, val vision: Boolean)

    /** Other models to try, in order, when the one asked for is busy or missing (NVIDIA). */
    var alts: List<String> = emptyList()
    /** Called with the model that answered when it wasn't the first choice, so the next message goes straight to it. */
    var switched: ((String) -> Unit)? = null

    val base = normalize(url)

    /** The models LM Studio has, loaded first. Empty when it isn't answering. */
    fun models(): List<Info> {
        for (path in listOf("/api/v0/models", "/v1/models")) {
            val data = runCatching { JSONObject(get(path)).getJSONArray("data") }.getOrNull() ?: continue
            val out = ArrayList<Info>()
            for (i in 0 until data.length()) {
                val m = data.getJSONObject(i)
                val type = m.optString("type", "llm")
                if (type != "llm" && type != "vlm") continue  // embedding models can't talk
                out.add(Info(m.getString("id"), m.optString("state") == "loaded", type == "vlm"))
            }
            if (out.isNotEmpty()) return out.sortedBy { !it.loaded }
        }
        return emptyList()
    }

    /**
     * One reply, streamed to [piece] (return false to stop). [jpeg]: a picture for the last message, for vision models.
     * Throws when the server can't be reached or says no.
     */
    fun chat(model: String, roles: Array<String>, contents: Array<String>, jpeg: ByteArray?, maxTokens: Int,
             temperature: Float, piece: Llama.Piece): String {
        val chain = if (jpeg == null) (listOf(model) + alts).distinct() else listOf(model)
        var why: Exception? = null
        for (m in chain) {
            try {
                val out = chatWith(m, roles, contents, jpeg, maxTokens, temperature, piece)
                if (m != model) switched?.invoke(m)
                return out
            } catch (e: Exception) {
                why = e
                if (!retryable(e)) throw e
            }
        }
        throw why ?: RuntimeException("no answer")
    }

    private fun chatWith(model: String, roles: Array<String>, contents: Array<String>, jpeg: ByteArray?, maxTokens: Int,
                         temperature: Float, piece: Llama.Piece): String {
        val messages = JSONArray()
        for (i in roles.indices) {
            val m = JSONObject().put("role", roles[i])
            if (jpeg != null && i == roles.lastIndex) {
                val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
                m.put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", contents[i].replace("{IMAGE}\n", "")))
                    .put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))))
            } else m.put("content", contents[i])
            messages.put(m)
        }
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", true)
            .put("temperature", temperature.toDouble()).put("max_tokens", maxTokens).put("top_p", 0.92)
            // thinking models would spend her whole reply budget thinking: switch it off
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        if (key.isEmpty()) body.put("min_p", 0.05).put("repeat_penalty", 1.05).put("presence_penalty", 0.3)
            .put("frequency_penalty", 0.3).put("reasoning_effort", "none")
        return try {
            send(body, piece)
        } catch (e: Rejected) {  // a cloud model that doesn't take the thinking switch: plain request instead
            if (key.isEmpty()) throw e
            body.remove("chat_template_kwargs")
            send(body, piece)
        }
    }

    private class Rejected(message: String) : RuntimeException(message)

    /** The server said no with [code]; the message is written for people, not programmers. */
    class Refused(val code: Int, message: String) : RuntimeException(message)

    private fun retryable(e: Exception) = when (e) {
        is Refused -> e.code == 403 || e.code == 404 || e.code == 408 || e.code == 429 || e.code >= 500
        is java.net.SocketTimeoutException -> true
        else -> false
    }

    private fun why(code: Int, body: String): String {
        val detail = runCatching {
            val j = JSONObject(body)
            j.optString("detail").ifEmpty { j.optJSONObject("error")?.optString("message").orEmpty() }.ifEmpty { j.optString("title") }
        }.getOrDefault("").take(140)
        val cloud = key.isNotEmpty()
        val what = when {
            code == 401 -> if (cloud) "NVIDIA says this key isn't valid. Copy the whole key again, or make a new one" else "the server wants a key"
            code == 403 -> if (cloud) "NVIDIA said no: your account may need verifying (email or phone), or this key can't use that model" else "the server said no (403)"
            code == 404 -> if (cloud) "that NVIDIA model isn't available right now" else "LM Studio has no model loaded"
            code == 429 -> if (cloud) "NVIDIA is busy, or your free credits for today ran out. Try again in a bit" else "too many requests, wait a moment"
            code >= 500 -> if (cloud) "NVIDIA's servers are having trouble ($code). Try again in a bit" else "LM Studio had an error ($code)"
            else -> "the server said $code"
        }
        return if (detail.isEmpty()) what else "$what ($detail)"
    }

    private fun send(body: JSONObject, piece: Llama.Piece): String {
        val c = URL("$base/v1/chat/completions").openConnection() as HttpURLConnection
        c.connectTimeout = if (key.isNotEmpty()) 15_000 else 5000
        c.readTimeout = if (key.isNotEmpty()) 60_000 else 120_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        if (key.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $key")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = try { c.responseCode } catch (e: java.net.UnknownHostException) {
                throw RuntimeException("no internet connection")
            }
            if (code !in 200..299) {
                val body = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code == 400 || code == 422) throw Rejected("server said $code: ${body.take(200)}")
                throw Refused(code, why(code, body))
            }
            val out = StringBuilder()
            var thinking = false
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    var bit = runCatching {
                        JSONObject(data).getJSONArray("choices").getJSONObject(0).getJSONObject("delta").optString("content")
                    }.getOrDefault("")
                    if ("<think>" in bit) thinking = true
                    if (thinking) {
                        if ("</think>" !in bit) continue
                        thinking = false
                        bit = bit.substringAfter("</think>")
                    }
                    if (bit.isEmpty()) continue
                    out.append(bit)
                    if (!piece.onPiece(bit)) break
                }
            }
            return out.toString()
        } finally {
            c.disconnect()
        }
    }

    private fun get(path: String): String {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 2500
        c.readTimeout = 5000
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val PORT = 1234

        /** Checks an NVIDIA key with a one-word hello. Returns (works, what to tell the user). Blocks. */
        fun checkNvidia(key: String): Pair<Boolean, String> {
            val client = LmStudio(ModelStore.NV_URL, key)
            client.alts = ModelStore.NV_TEXT
            var used = ModelStore.NV_MODEL
            client.switched = { used = it }
            return try {
                client.chat(ModelStore.NV_MODEL, arrayOf("user"), arrayOf("Say hi in one word."), null, 16, 0.2f) { true }
                true to (if (used == ModelStore.NV_MODEL) "Your key works. She's thinking with NVIDIA now."
                else "Your key works. NVIDIA's biggest model is busy, so she's using ${used.substringAfter('/')} for now.")
            } catch (e: Exception) {
                false to (e.message ?: "that didn't work")
            }
        }

        /** "192.168.1.5" -> "http://192.168.1.5:1234", whatever way you typed it. */
        fun normalize(url: String): String {
            var u = url.trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')
            if (u.isEmpty()) return ""
            if ("://" !in u) u = "http://$u"
            if (!u.startsWith("https") && !Regex(":\\d+$").containsMatchIn(u)) u = "$u:$PORT"
            return u
        }

        /** Looks for LM Studio on your Wi-Fi (every address next to the phone's, port 1234). Null if nothing answers. */
        fun find(): String? {
            val mine = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() }
            val pool = Executors.newFixedThreadPool(48)
            try {
                for (ip in mine) {
                    val prefix = ip.substringBeforeLast('.')
                    val jobs = (1..254).map { n ->
                        pool.submit(Callable {
                            val host = "$prefix.$n"
                            val open = runCatching {
                                Socket().use { it.connect(InetSocketAddress(host, PORT), 400) }
                            }.isSuccess
                            if (open && host != ip && LmStudio(host).models().isNotEmpty()) host else null
                        })
                    }
                    jobs.firstNotNullOfOrNull { runCatching { it.get(6, TimeUnit.SECONDS) }.getOrNull() }
                        ?.let { return normalize(it) }
                }
                return null
            } finally {
                pool.shutdownNow()
            }
        }
    }
}
