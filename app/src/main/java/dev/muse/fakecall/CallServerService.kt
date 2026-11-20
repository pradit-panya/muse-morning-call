package dev.muse.fakecall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Tailscale HTTP receive-server (port 8791) for Muse remote calls & dialogue.
 *
 * Access control on EVERY request:
 *  (a) source IP in Tailscale range 100.64.0.0/10
 *  (b) header X-Token equals Store.serverToken()
 *
 * Endpoints (v1.0.0 — backwards compatible with v0.9.2):
 *   GET  /ping        -> 200 "ok v1.0.0"
 *   GET  /status      -> JSON current call state, phase, settings, seqs
 *   GET  /events      -> Long Polling: ?since=<seq>&timeout=<ms> (default 25000 ms)
 *                        Wakes immediately on answer / speech / hangup (<50 ms)
 *   GET  /utterance   -> Immediate JSON (or Long Polling when ?since=<id> is given)
 *   POST /call        -> Starts incoming call. Body = optional audio bytes, OR
 *                        header X-Text (URL-encoded) to synthesize directly via PC TTS.
 *                        Headers: X-Message, X-Morning, X-Caller, X-Audio-Ext, X-Dialog
 *   POST /say         -> Raw audio body delivered directly in RAM to PcmAudioPlayer
 *                        (fixes v0.9.2 say_audio.wav overwrite race)
 *   POST /say_text    -> JSON {"text":"...","split":true,"tts_url":"..."} or text/plain.
 *                        Phone fetches WAV directly from PC TTS over local Tailscale LAN,
 *                        eliminating ~2-3 s/sentence of cloud VM tunnel proxy overhead!
 *   POST /hangup      -> Officially terminates ringing or active call (replaces screen-tap hack)
 */
class CallServerService : Service() {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (serverSocket == null) startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) { }
        serverSocket = null
        try { acceptThread?.interrupt() } catch (e: Exception) { }
        acceptThread = null
        super.onDestroy()
    }

    // ---------- foreground notification ----------

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            if (nm.getNotificationChannel(SERVER_CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    SERVER_CHANNEL_ID, "Muse着信待機", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Muse からのリモート着信の待機状態"
                    setSound(null, null)
                }
                nm.createNotificationChannel(ch)
            }
        }
        val notif = Notification.Builder(this, SERVER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Muse Fake Call")
            .setContentText("Museからの着信を待機中")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SERVER_NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(SERVER_NOTIF_ID, notif)
        }
    }

    // ---------- HTTP server ----------

    private fun startServer() {
        if (acceptThread?.isAlive == true) return
        acceptThread = thread(name = "fakecall-server", isDaemon = true) {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket(PORT)
                serverSocket = ss
                running = true
                while (!ss.isClosed) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    thread(name = "fakecall-conn", isDaemon = true) {
                        handleConnection(client)
                    }
                }
            } catch (e: Exception) {
                // Never crash the service process.
            } finally {
                running = false
                try { ss?.close() } catch (e: Exception) { }
                if (serverSocket === ss) serverSocket = null
            }
        }
    }

    private fun handleConnection(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.use { sock ->
                val input = BufferedInputStream(sock.getInputStream())
                val output = sock.getOutputStream()

                if (!isTailnetAddress(sock.inetAddress)) {
                    respond(output, 403, "Forbidden", "text/plain", "forbidden".toByteArray())
                    return
                }

                val head = readHeaderBlock(input) ?: run {
                    respond(output, 400, "Bad Request", "text/plain", "bad request".toByteArray())
                    return
                }
                val lines = head.split("\r\n")
                val requestParts = lines[0].split(" ")
                if (requestParts.size < 2) {
                    respond(output, 400, "Bad Request", "text/plain", "bad request".toByteArray())
                    return
                }
                val method = requestParts[0].uppercase()
                val rawTarget = requestParts[1]
                val path = rawTarget.substringBefore('?')
                val query = parseQuery(rawTarget.substringAfter('?', ""))
                val headers = HashMap<String, String>()
                for (i in 1 until lines.size) {
                    val idx = lines[i].indexOf(':')
                    if (idx > 0) {
                        headers[lines[i].substring(0, idx).trim().lowercase()] =
                            lines[i].substring(idx + 1).trim()
                    }
                }

                val providedToken = (headers["x-token"] ?: "").toByteArray(Charsets.UTF_8)
                val expectedToken = Store.serverToken(this).toByteArray(Charsets.UTF_8)
                if (!MessageDigest.isEqual(providedToken, expectedToken)) {
                    respond(output, 403, "Forbidden", "text/plain", "forbidden".toByteArray())
                    return
                }

                when {
                    method == "GET" && path == "/ping" -> {
                        respond(output, 200, "OK", "text/plain", "ok v$APP_VERSION".toByteArray())
                    }
                    method == "GET" && path == "/status" -> {
                        handleStatus(output)
                    }
                    method == "GET" && path == "/events" -> {
                        handleEvents(sock, output, query)
                    }
                    method == "GET" && path == "/utterance" -> {
                        handleUtterance(sock, output, query)
                    }
                    method == "POST" && path == "/call" -> {
                        handleCall(input, output, headers)
                    }
                    method == "POST" && path == "/say" -> {
                        handleSay(input, output, headers)
                    }
                    method == "POST" && path == "/say_text" -> {
                        handleSayText(input, output, headers)
                    }
                    method == "POST" && path == "/accept" -> {
                        handleAccept(input, output, headers)
                    }
                    method == "POST" && path == "/hangup" -> {
                        handleHangup(input, output, headers)
                    }
                    method == "POST" && path == "/schedule" -> {
                        handleSchedule(input, output, headers)
                    }
                    path in KNOWN_PATHS -> {
                        respond(output, 405, "Method Not Allowed", "text/plain",
                            "method not allowed".toByteArray())
                    }
                    else -> {
                        respond(output, 404, "Not Found", "text/plain",
                            "not found".toByteArray())
                    }
                }
            }
        } catch (e: Exception) {
            try { client.close() } catch (ignored: Exception) { }
        }
    }

    private fun handleStatus(output: OutputStream) {
        val utt = DialogStore.latestUtterance(this)
        val json = "{\"ok\":true" +
            ",\"version\":\"$APP_VERSION\"" +
            ",\"state\":\"${CallEventBus.callState.name}\"" +
            ",\"dialog_phase\":\"${CallEventBus.dialogPhase.name}\"" +
            ",\"dialog_active\":${DialogStore.dialogActive(this)}" +
            ",\"session\":${CallEventBus.activeSession}" +
            ",\"morning\":${CallEventBus.activeMorning}" +
            ",\"muted\":${CallEventBus.activeMuted}" +
            ",\"speaker\":${CallEventBus.activeSpeaker}" +
            ",\"pending_tts\":${CallRuntime.pendingTtsCount}" +
            ",\"latest_event_seq\":${CallEventBus.latestSeq()}" +
            ",\"latest_utterance_id\":${utt?.id ?: 0L}" +
            ",\"schedule_on\":${Store.scheduleOn(this)}" +
            ",\"schedule_time\":\"${String.format("%02d:%02d", Store.scheduleHour(this), Store.scheduleMinute(this))}\"" +
            ",\"schedule_daily\":${Store.scheduleDaily(this)}" +
            ",\"tts_url\":\"${CallEventBus.jsonEscape(Store.ttsServerUrl(this))}\"" +
            "}"
        respond(output, 200, "OK", "application/json", json.toByteArray(Charsets.UTF_8))
    }

    private fun handleEvents(sock: Socket, output: OutputStream, query: Map<String, String>) {
        val since = query["since"]?.toLongOrNull() ?: CallEventBus.latestSeq()
        val timeoutMs = (query["timeout"]?.toLongOrNull() ?: 25_000L).coerceIn(0L, 55_000L)
        sock.soTimeout = (timeoutMs + 10_000L).toInt()
        val events = CallEventBus.waitForEvents(since, timeoutMs)
        val json = CallEventBus.eventsToJson(events)
        respond(output, 200, "OK", "application/json", json.toByteArray(Charsets.UTF_8))
    }

    private fun handleUtterance(sock: Socket, output: OutputStream, query: Map<String, String>) {
        val sinceParam = query["since"]?.toLongOrNull()
        val (utt, active) = if (sinceParam != null) {
            val timeoutMs = (query["timeout"]?.toLongOrNull() ?: 25_000L).coerceIn(0L, 55_000L)
            sock.soTimeout = (timeoutMs + 10_000L).toInt()
            DialogStore.waitForUpdate(this, sinceParam, timeoutMs)
        } else {
            Pair(DialogStore.latestUtterance(this), DialogStore.dialogActive(this))
        }
        val state = CallEventBus.callState.name
        val json = if (utt == null) {
            "{\"id\":0,\"dialog_active\":$active,\"state\":\"$state\"}"
        } else {
            "{\"id\":${utt.id},\"text\":\"${CallEventBus.jsonEscape(utt.text)}\"," +
                "\"ts\":${utt.ts},\"dialog_active\":$active,\"state\":\"$state\"}"
        }
        respond(output, 200, "OK", "application/json", json.toByteArray(Charsets.UTF_8))
    }

    private fun handleCall(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val body = readBodyOrRespond(input, output, headers, allowMissingLength = true) ?: return

        val message = headers["x-message"]
            ?.let { try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { null } }
            ?.takeIf { it.isNotBlank() }
            ?: Store.message(this)
        val caller = headers["x-caller"]?.takeIf { it.isNotBlank() } ?: Store.caller(this)
        val morning = headers["x-morning"]?.equals("true", ignoreCase = true) == true
        // Morning calls always enable dialogue unless explicitly disabled with X-Dialog: 0.
        val dialogHeader = headers["x-dialog"]?.trim()
        val dialog = when {
            dialogHeader == "0" -> false
            dialogHeader == "1" -> true
            morning -> true
            else -> false
        }
        val ext = sanitizeAudioExt(headers)
        val xText = headers["x-text"]
            ?.let { try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { null } }
            ?.takeIf { it.isNotBlank() }
        val ttsUrl = headers["x-tts-url"]?.takeIf { it.isNotBlank() } ?: Store.ttsServerUrl(this)

        var audioBytes: ByteArray? = if (body.isNotEmpty()) body else null
        var audioExt = ext
        var ttsGenMs = 0L

        if (audioBytes == null && xText != null) {
            val res = LocalTtsClient.synthesize(ttsUrl, xText)
            if (res.wavBytes != null) {
                audioBytes = res.wavBytes
                audioExt = "wav"
                ttsGenMs = res.genMs
            }
        }

        var audioPath: String? = null
        if (audioBytes != null && audioBytes.isNotEmpty()) {
            try {
                filesDir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("remote_audio_")) f.delete()
                }
                val dest = File(filesDir, "remote_audio_${System.currentTimeMillis()}.$audioExt")
                dest.writeBytes(audioBytes)
                if (dest.length() > 0) audioPath = dest.absolutePath
            } catch (e: Exception) {
                audioPath = null
            }
        }

        val session = CallStarter.startCall(
            this, message, caller, morning, attempt = 1, audioPath = audioPath,
            dialog = dialog
        )
        val json = "{\"ok\":true,\"session\":$session,\"dialog\":$dialog,\"tts_gen_ms\":$ttsGenMs}"
        respond(output, 200, "OK", "application/json", json.toByteArray(Charsets.UTF_8))
    }

    /**
     * Direct in-RAM audio delivery (no file overwrite race when sentences arrive back-to-back).
     */
    private fun handleSay(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val body = readBodyOrRespond(input, output, headers) ?: return
        val ext = sanitizeAudioExt(headers)
        val delivered = if (body.isNotEmpty()) {
            CallRuntime.deliverAudioBytes(body, ext)
        } else {
            false
        }
        respond(output, 200, "OK", "application/json",
            "{\"ok\":true,\"delivered\":$delivered}".toByteArray())
    }

    /**
     * Phone-direct PC TTS synthesis over local Tailscale LAN (bypasses VM tunnel WAV relay!).
     * Accepts JSON `{"text":"...","split":true,"tts_url":"..."}` or `text/plain` body.
     */
    private fun handleSayText(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val body = readBodyOrRespond(input, output, headers) ?: return
        val raw = body.toString(Charsets.UTF_8).trim()
        if (raw.isEmpty()) {
            respond(output, 400, "Bad Request", "application/json",
                "{\"ok\":false,\"error\":\"empty_text\"}".toByteArray())
            return
        }

        var text = raw
        var split = true
        var ttsUrl: String? = headers["x-tts-url"]
        val contentType = headers["content-type"]?.lowercase() ?: ""
        if (contentType.contains("json") || raw.startsWith("{")) {
            try {
                val obj = JSONObject(raw)
                text = obj.optString("text", "").trim()
                split = obj.optBoolean("split", true)
                val u = obj.optString("tts_url", "").trim()
                if (u.isNotEmpty()) ttsUrl = u
            } catch (e: Exception) {
                respond(output, 400, "Bad Request", "application/json",
                    "{\"ok\":false,\"error\":\"invalid_json\"}".toByteArray())
                return
            }
        }
        if (text.isEmpty()) {
            respond(output, 400, "Bad Request", "application/json",
                "{\"ok\":false,\"error\":\"empty_text\"}".toByteArray())
            return
        }

        val res = CallRuntime.deliverSayText(this, text, split = split, ttsUrlOverride = ttsUrl)
        val code = if (res.ok) 200 else 503
        val reason = if (res.ok) "OK" else "Service Unavailable"
        val errField = res.error?.let { ",\"error\":\"${CallEventBus.jsonEscape(it)}\"" } ?: ""
        val json = "{\"ok\":${res.ok},\"delivered\":${res.delivered}," +
            "\"sentences\":${res.sentences},\"first_gen_ms\":${res.firstGenMs}$errField}"
        respond(output, code, reason, "application/json", json.toByteArray(Charsets.UTF_8))
    }

    /** Accepts a user-initiated outgoing call (`DIALING` -> `ACTIVE`), optionally speaking X-Text or audio body. */
    private fun handleAccept(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val body = readBodyOrRespond(input, output, headers, allowMissingLength = true) ?: return
        val accepted = CallRuntime.requestAccept()
        if (!accepted) {
            respond(output, 200, "OK", "application/json",
                "{\"ok\":true,\"accepted\":false}".toByteArray())
            return
        }
        var delivered = false
        var genMs = 0L
        if (body.isNotEmpty()) {
            delivered = CallRuntime.deliverAudioBytes(body, sanitizeAudioExt(headers))
        } else {
            val xText = headers["x-text"]
                ?.let { try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { null } }
                ?.takeIf { it.isNotBlank() }
            if (xText != null) {
                val res = CallRuntime.deliverSayText(this, xText, split = true, ttsUrlOverride = headers["x-tts-url"])
                delivered = res.delivered
                genMs = res.firstGenMs
            }
        }
        respond(output, 200, "OK", "application/json",
            "{\"ok\":true,\"accepted\":true,\"delivered\":$delivered,\"first_gen_ms\":$genMs}".toByteArray())
    }

    /** Official remote hangup endpoint (replaces screen-tap workaround). */
    private fun handleHangup(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        // Consume any optional body cleanly so client doesn't see TCP reset.
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length in 1..MAX_BODY_BYTES) {
            readExactly(input, length.toInt())
        }
        val reason = headers["x-reason"]?.takeIf { it.isNotBlank() } ?: "remote_hangup"
        val wasActive = CallRuntime.requestHangup(this, reason)
        respond(output, 200, "OK", "application/json",
            "{\"ok\":true,\"was_active\":$wasActive}".toByteArray())
    }

    /** Remote morning call schedule control: `{"enabled":true,"hour":7,"minute":30,"daily":true}` or `{"enabled":false}`. */
    private fun handleSchedule(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val body = readBodyOrRespond(input, output, headers) ?: return
        val raw = body.toString(Charsets.UTF_8).trim()
        try {
            val obj = JSONObject(raw)
            val enabled = obj.optBoolean("enabled", true)
            if (!enabled) {
                Store.clearSchedule(this)
                Scheduler.cancelLocal(this)
                respond(output, 200, "OK", "application/json",
                    "{\"ok\":true,\"enabled\":false}".toByteArray())
                return
            }
            val hour = obj.optInt("hour", Store.scheduleHour(this)).coerceIn(0, 23)
            val minute = obj.optInt("minute", Store.scheduleMinute(this)).coerceIn(0, 59)
            val daily = obj.optBoolean("daily", true)
            Store.saveSchedule(this, hour, minute, daily)
            val triggerAt = Scheduler.nextTriggerMillis(hour, minute)
            Scheduler.scheduleLocal(this, triggerAt)
            val json = "{\"ok\":true,\"enabled\":true,\"hour\":$hour,\"minute\":$minute,\"daily\":$daily,\"trigger_at\":$triggerAt}"
            respond(output, 200, "OK", "application/json", json.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            respond(output, 400, "Bad Request", "application/json",
                "{\"ok\":false,\"error\":\"invalid_json\"}".toByteArray())
        }
    }

    // ---------- HTTP helpers ----------

    private fun readBodyOrRespond(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        allowMissingLength: Boolean = false
    ): ByteArray? {
        val length = headers["content-length"]?.toLongOrNull()
        if (length == null) {
            if (allowMissingLength) return ByteArray(0)
            respond(output, 411, "Length Required", "text/plain",
                "content-length required".toByteArray())
            return null
        }
        if (length < 0 || length > MAX_BODY_BYTES) {
            respond(output, 413, "Payload Too Large", "text/plain",
                "too large".toByteArray())
            return null
        }
        val body = readExactly(input, length.toInt())
        if (body == null) {
            respond(output, 400, "Bad Request", "text/plain",
                "short body".toByteArray())
        }
        return body
    }

    private fun sanitizeAudioExt(headers: Map<String, String>): String =
        (headers["x-audio-ext"] ?: "wav").lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
            ?: "wav"

    private fun parseQuery(qs: String): Map<String, String> {
        if (qs.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in qs.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = try { URLDecoder.decode(pair.substring(0, idx), "UTF-8") } catch (e: Exception) { pair.substring(0, idx) }
                val v = try { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") } catch (e: Exception) { pair.substring(idx + 1) }
                map[k] = v
            }
        }
        return map
    }

    private fun readHeaderBlock(input: InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        var matched = 0
        val pattern = intArrayOf('\r'.code, '\n'.code, '\r'.code, '\n'.code)
        while (buf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) return if (buf.size() > 0) buf.toString("ISO-8859-1") else null
            buf.write(b)
            matched = if (b == pattern[matched]) matched + 1 else if (b == pattern[0]) 1 else 0
            if (matched == 4) return buf.toString("ISO-8859-1")
        }
        return null
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = input.read(buf, off, count - off)
            if (n == -1) return null
            off += n
        }
        return buf
    }

    private fun respond(
        output: OutputStream, code: Int, reason: String,
        contentType: String, body: ByteArray
    ) {
        try {
            val head = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        } catch (e: Exception) { }
    }

    private fun isTailnetAddress(addr: InetAddress?): Boolean {
        val b = addr?.address ?: return false
        if (b.size != 4) return false
        return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xFF) in 64..127
    }

    companion object {
        const val APP_VERSION = "1.0.2"
        const val PORT = 8791
        private const val SERVER_CHANNEL_ID = "call_server"
        private const val SERVER_NOTIF_ID = 1002
        private const val MAX_BODY_BYTES = 20L * 1024 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val SOCKET_TIMEOUT_MS = 30_000
        private val KNOWN_PATHS = setOf(
            "/ping", "/status", "/events", "/utterance",
            "/call", "/say", "/say_text", "/accept", "/hangup", "/schedule"
        )

        @Volatile var running: Boolean = false
            private set

        fun start(ctx: Context) {
            val intent = Intent(ctx, CallServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CallServerService::class.java))
        }
    }
}
