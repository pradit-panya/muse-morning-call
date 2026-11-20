package dev.muse.fakecall

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Direct Phone -> PC Irodori-TTS client over local Tailscale LAN.
 *
 * Eliminates ~2-3 s/sentence of cloud VM tunnel proxy roundtrip overhead:
 * Muse VM sends only ~50 bytes of UTF-8 text via `POST /say_text`, and the
 * phone fetches the WAV directly from `http://<PC_TAILSCALE_IP>:8793/gen` (<5 ms RTT).
 *
 * Security invariant: strictly allows ONLY Tailscale (`100.64.0.0/10`) and
 * private LAN/loopback addresses — never transmits text to public internet hosts.
 */
object LocalTtsClient {

    data class GenResult(
        val wavBytes: ByteArray?,
        val genMs: Long,
        val error: String? = null
    )

    private const val CONNECT_TIMEOUT_MS = 2500
    private const val READ_TIMEOUT_MS = 25_000

    /**
     * Synthesizes [text] via [baseUrl]/gen (`{"text":"...","out":"...","return_audio":true}`).
     */
    fun synthesize(baseUrl: String, text: String): GenResult {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val url = try {
            URL("$cleanBase/gen")
        } catch (e: Exception) {
            return GenResult(null, 0, "invalid_url")
        }
        if (url.protocol != "http" && url.protocol != "https") {
            return GenResult(null, 0, "unsupported_scheme")
        }
        if (!isAllowedLocalOrTailnetHost(url.host)) {
            Log.w("FakeCallDbg", "LocalTtsClient: blocked non-local/tailnet host=${url.host}")
            return GenResult(null, 0, "host_not_allowed")
        }

        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            val payload = "{\"text\":\"${CallEventBus.jsonEscape(text)}\"," +
                "\"out\":\"phone_${System.currentTimeMillis()}.wav\"," +
                "\"return_audio\":true}"
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)

            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Content-Length", payloadBytes.size.toString())
            }
            conn.outputStream.use { it.write(payloadBytes) }

            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - start
            if (code !in 200..299) {
                return GenResult(null, elapsed, "http_$code")
            }
            val body = conn.inputStream.use { ins ->
                val out = ByteArrayOutputStream(131072)
                val buf = ByteArray(16384)
                while (true) {
                    val n = ins.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            if (body.size < 44) {
                return GenResult(null, elapsed, "empty_audio")
            }
            val serverGenMs = conn.getHeaderField("X-Gen-Ms")?.toLongOrNull() ?: elapsed
            Log.d(
                "FakeCallDbg",
                "LocalTtsClient: OK len=${body.size} totalMs=$elapsed serverGenMs=$serverGenMs"
            )
            GenResult(body, serverGenMs, null)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            Log.w("FakeCallDbg", "LocalTtsClient failed (${e.javaClass.simpleName}: ${e.message})")
            GenResult(null, elapsed, "${e.javaClass.simpleName}:${e.message ?: ""}")
        } finally {
            try { conn?.disconnect() } catch (ignored: Exception) { }
        }
    }

    /**
     * Splits Japanese/English reply text into natural spoken sentences so sentence 1
     * starts playing immediately while sentences 2..N synthesize over local LAN.
     */
    fun splitSentences(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        val cur = StringBuilder()
        for (i in text.indices) {
            val c = text[i]
            if (c == '\r') continue
            if (c == '\n') {
                val s = cur.toString().trim()
                if (s.isNotEmpty()) result.add(s)
                cur.setLength(0)
                continue
            }
            cur.append(c)
            val isEndPunct = c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
            if (isEndPunct) {
                // Keep consecutive punctuation (e.g. "！？", "…！") or closing quotes together.
                val next = text.getOrNull(i + 1)
                val morePunct = next == '。' || next == '！' || next == '？' ||
                    next == '!' || next == '?' || next == '」' || next == '』' || next == ')' || next == '）'
                if (!morePunct) {
                    val s = cur.toString().trim()
                    if (s.isNotEmpty()) result.add(s)
                    cur.setLength(0)
                }
            }
        }
        val tail = cur.toString().trim()
        if (tail.isNotEmpty()) result.add(tail)

        // Merge ultra-short sentences (<=14 chars) with the next sentence (up to 28 chars)
        // because Irodori-TTS hallucinates far more on very short isolated inputs.
        if (result.size <= 1) return result
        val merged = ArrayList<String>(result.size)
        var idx = 0
        while (idx < result.size) {
            var chunk = result[idx]
            while (chunk.length <= 14 && idx + 1 < result.size &&
                chunk.length + result[idx + 1].length <= 28
            ) {
                idx++
                chunk = "$chunk ${result[idx]}"
            }
            merged.add(chunk)
            idx++
        }
        return merged
    }

    /** Fire-and-forget local Tailscale LAN log upload (zero LLM/cloud token cost). */
    fun postTurnLogAsync(baseUrl: String, jsonPayload: String) {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val url = try { URL("$cleanBase/log_turn") } catch (_: Exception) { return }
        if ((url.protocol != "http" && url.protocol != "https") || !isAllowedLocalOrTailnetHost(url.host)) return
        Thread({
            var conn: HttpURLConnection? = null
            try {
                val bytes = jsonPayload.toByteArray(Charsets.UTF_8)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 1500
                    readTimeout = 2000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Content-Length", bytes.size.toString())
                }
                conn.outputStream.use { it.write(bytes) }
                conn.responseCode
            } catch (_: Exception) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) { }
            }
        }, "fakecall-log").apply { isDaemon = true; start() }
    }

    private fun isAllowedLocalOrTailnetHost(host: String): Boolean {
        val addr = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return false
        }
        val b = addr.address
        if (b.size != 4) return addr.isLoopbackAddress
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return when {
            b0 == 100 && b1 in 64..127 -> true // Tailscale CGNAT 100.64.0.0/10
            b0 == 127 -> true                  // loopback
            b0 == 10 -> true                   // 10.0.0.0/8
            b0 == 192 && b1 == 168 -> true     // 192.168.0.0/16
            b0 == 172 && b1 in 16..31 -> true  // 172.16.0.0/12
            else -> false
        }
    }
}
