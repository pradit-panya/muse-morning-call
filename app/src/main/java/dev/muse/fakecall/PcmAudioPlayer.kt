package dev.muse.fakecall

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-latency in-memory audio player for phone calls.
 *
 * - Plays PCM WAV clips directly through a reused [AudioTrack] (`MODE_STREAM`,
 *   `USAGE_VOICE_COMMUNICATION`), avoiding disk writes and MediaPlayer prepare
 *   overhead (~1 s -> ~0.1 s).
 * - Preloads the bundled backchannel filler WAVs (`assets/fillers/f1..f8.wav`)
 *   into RAM so filler start latency is <5 ms.
 * - Falls back to [MediaPlayer] (still on `USAGE_VOICE_COMMUNICATION`) for
 *   non-WAV formats (e.g. MP3/M4A custom audio).
 * - Thread-safe FIFO queue (capacity 16); fixes the old `say_audio.wav`
 *   overwrite race when multiple sentences arrive back-to-back.
 */
class PcmAudioPlayer(
    context: Context,
    private val onPlaybackStarted: (isFiller: Boolean, tag: String) -> Unit,
    private val onQueueDrained: () -> Unit
) {

    data class DecodedWav(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int
    )

    private sealed class AudioItem(
        val tag: String,
        val isFiller: Boolean,
        val onDone: (() -> Unit)?
    ) {
        class Pcm(
            val wav: DecodedWav,
            tag: String,
            isFiller: Boolean,
            onDone: (() -> Unit)?
        ) : AudioItem(tag, isFiller, onDone)

        class FileClip(
            val path: String,
            val deleteAfter: Boolean,
            tag: String,
            onDone: (() -> Unit)?
        ) : AudioItem(tag, false, onDone)

        class AssetClip(
            val assetName: String,
            tag: String,
            onDone: (() -> Unit)?
        ) : AudioItem(tag, false, onDone)
    }

    private val appCtx = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<AudioItem>(MAX_QUEUE)
    private val generation = AtomicInteger(0)

    @Volatile private var released = false
    @Volatile private var playingNow = false
    @Volatile private var playingFiller = false
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var currentTrackRate: Int = 0
    @Volatile private var currentTrackChannels: Int = 0
    @Volatile private var currentMediaPlayer: MediaPlayer? = null

    private val workerThread: Thread

    val isBusy: Boolean
        get() = playingNow || queue.isNotEmpty()

    val isPlayingFiller: Boolean
        get() = playingFiller

    val queueDepth: Int
        get() = queue.size + if (playingNow) 1 else 0

    init {
        preloadFillersAsync(appCtx)
        workerThread = Thread({ runLoop() }, "fakecall-audio").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
            start()
        }
    }

    /** Enqueues raw WAV bytes (or writes temp file if non-WAV format). */
    fun enqueueBytes(
        bytes: ByteArray,
        ext: String = "wav",
        tag: String = "say",
        onDone: (() -> Unit)? = null
    ): Boolean {
        if (released || bytes.isEmpty()) return false
        val parsed = if (ext.equals("wav", ignoreCase = true)) parseWav(bytes) else null
        if (parsed != null) {
            return enqueueItem(AudioItem.Pcm(parsed, tag, isFiller = false, onDone = onDone))
        }
        return try {
            val tmp = File(appCtx.cacheDir, "clip_${System.nanoTime()}.$ext")
            tmp.writeBytes(bytes)
            enqueueItem(AudioItem.FileClip(tmp.absolutePath, deleteAfter = true, tag = tag, onDone = onDone))
        } catch (e: Exception) {
            Log.e("FakeCallDbg", "PcmAudioPlayer.enqueueBytes fallback write failed", e)
            false
        }
    }

    fun enqueueFile(
        path: String,
        deleteAfter: Boolean = false,
        tag: String = "file",
        onDone: (() -> Unit)? = null
    ): Boolean {
        if (released) return false
        val f = File(path)
        if (!f.exists() || f.length() == 0L) return false
        return enqueueItem(AudioItem.FileClip(path, deleteAfter, tag, onDone))
    }

    fun enqueueAsset(
        assetName: String,
        tag: String = "asset",
        onDone: (() -> Unit)? = null
    ): Boolean {
        if (released) return false
        return enqueueItem(AudioItem.AssetClip(assetName, tag, onDone))
    }

    /** Plays a random RAM-cached filler clip (<5 ms start latency). */
    fun enqueueFiller(lastIndex: Int): Int {
        return enqueueFillerForKind(UtteranceClassifier.UtteranceKind.EMPATHY, lastIndex)
    }

    fun enqueueFillerForKind(kind: UtteranceClassifier.UtteranceKind, lastIndex: Int): Int {
        if (released) return lastIndex
        val pool = when (kind) {
            UtteranceClassifier.UtteranceKind.EMPATHY -> cachedEmpathyFillers
            UtteranceClassifier.UtteranceKind.QUESTION_LOOKUP -> cachedThinkFillers
            UtteranceClassifier.UtteranceKind.ACK -> cachedEmpathyFillers.take(1)
        }
        val effectivePool = if (pool.isNotEmpty()) pool else cachedFillers
        if (effectivePool.isNotEmpty()) {
            var idx = effectivePool.indices.random()
            if (effectivePool.size > 1 && idx == lastIndex) {
                idx = (idx + 1) % effectivePool.size
            }
            val ok = enqueueItem(
                AudioItem.Pcm(effectivePool[idx], tag = "filler_${kind.name}_${idx + 1}", isFiller = true, onDone = null)
            )
            return if (ok) idx else lastIndex
        }
        // Fallback if cache is still warming up on first call.
        var idx = SAFE_FILLER_ASSETS.indices.random()
        if (SAFE_FILLER_ASSETS.size > 1 && idx == lastIndex) {
            idx = (idx + 1) % SAFE_FILLER_ASSETS.size
        }
        enqueueItem(AudioItem.AssetClip(SAFE_FILLER_ASSETS[idx], tag = "filler_safe_$idx", onDone = null))
        return idx
    }

    fun enqueueWait2Filler(lastIndex: Int): Int {
        if (released) return lastIndex
        val pool = cachedWait2Fillers
        if (pool.isEmpty()) return lastIndex
        var idx = pool.indices.random()
        if (pool.size > 1 && idx == lastIndex) {
            idx = (idx + 1) % pool.size
        }
        val ok = enqueueItem(
            AudioItem.Pcm(pool[idx], tag = "filler_wait2_${idx + 1}", isFiller = true, onDone = null)
        )
        return if (ok) idx else lastIndex
    }

    /** Plays a pre-generated clean canned voice for program-driven command routing. */
    fun enqueueCanned(route: UtteranceClassifier.CommandRoute, onDone: (() -> Unit)? = null): String? {
        if (released) return null
        val list = when (route) {
            UtteranceClassifier.CommandRoute.COMMAND_WORK -> cachedCannedWork
            UtteranceClassifier.CommandRoute.COMMAND_LOOKUP -> cachedCannedLookup
            UtteranceClassifier.CommandRoute.SCHEDULE_ALARM -> cachedCannedScheduleOk
            UtteranceClassifier.CommandRoute.WAIT_PAUSE -> cachedCannedWaitOk
            UtteranceClassifier.CommandRoute.GREETING_MORNING -> cachedCannedMorning
            UtteranceClassifier.CommandRoute.GREETING_BYE,
            UtteranceClassifier.CommandRoute.HANGUP_NOW,
            UtteranceClassifier.CommandRoute.CONFIRM_YES -> cachedCannedBye
            UtteranceClassifier.CommandRoute.THANKS_ACK,
            UtteranceClassifier.CommandRoute.ASK_HANGUP -> cachedCannedAskHangup
            UtteranceClassifier.CommandRoute.ASK_MORE -> cachedCannedAskMore
            else -> cachedCannedUnsupported
        }
        if (list.isEmpty()) return null
        val (wav, label) = list.random()
        val ok = enqueueItem(AudioItem.Pcm(wav, tag = "canned_${route.name}", isFiller = false, onDone = onDone))
        return if (ok) label else null
    }

    /** Plays a pre-generated fallback when Muse does not respond to a morning greeting. */
    fun enqueueMorningNoScheduleFallback(): String? {
        if (released) return null
        val list = cachedCannedMorningNoSchedule
        if (list.isEmpty()) return null
        val (wav, label) = list.random()
        val ok = enqueueItem(
            AudioItem.Pcm(wav, tag = "canned_MORNING_NO_SCHEDULE", isFiller = false, onDone = null)
        )
        return if (ok) label else null
    }

    private fun enqueueItem(item: AudioItem): Boolean {
        while (!queue.offer(item)) {
            val dropped = queue.poll()
            if (dropped is AudioItem.FileClip && dropped.deleteAfter) {
                try { File(dropped.path).delete() } catch (e: Exception) { }
            }
            Log.w("FakeCallDbg", "PcmAudioPlayer: queue full, dropped oldest=${dropped?.tag}")
        }
        return true
    }

    /** Immediately halts current playback and clears all queued clips. */
    fun clearAndStop() {
        generation.incrementAndGet()
        val drained = ArrayList<AudioItem>()
        queue.drainTo(drained)
        for (item in drained) {
            if (item is AudioItem.FileClip && item.deleteAfter) {
                try { File(item.path).delete() } catch (e: Exception) { }
            }
        }
        stopTrackNow()
        stopMediaPlayerNow()
        playingNow = false
        playingFiller = false
    }

    fun release() {
        released = true
        clearAndStop()
        workerThread.interrupt()
        releaseTrack()
    }

    // ---------- worker loop ----------

    private fun runLoop() {
        while (!released) {
            val item = try {
                queue.take()
            } catch (e: InterruptedException) {
                if (released) break
                continue
            }
            val gen = generation.get()
            playingNow = true
            playingFiller = item.isFiller
            mainHandler.post { onPlaybackStarted(item.isFiller, item.tag) }

            try {
                when (item) {
                    is AudioItem.Pcm -> playPcmBlocking(item.wav, gen)
                    is AudioItem.AssetClip -> playAssetBlocking(item.assetName, gen)
                    is AudioItem.FileClip -> playFileBlocking(item, gen)
                }
            } catch (e: Exception) {
                Log.e("FakeCallDbg", "PcmAudioPlayer playback error (${item.tag})", e)
            } finally {
                if (item is AudioItem.FileClip && item.deleteAfter) {
                    try { File(item.path).delete() } catch (ignored: Exception) { }
                }
                playingFiller = false
                item.onDone?.let { cb -> mainHandler.post { cb() } }
                val queueEmpty = queue.isEmpty()
                if (queueEmpty) {
                    playingNow = false
                    if (!released && generation.get() == gen) {
                        mainHandler.post { onQueueDrained() }
                    }
                }
            }
        }
    }

    private fun playAssetBlocking(assetName: String, gen: Int) {
        val bytes = appCtx.assets.open(assetName).use { it.readBytes() }
        val wav = parseWav(bytes)
        if (wav != null) {
            playPcmBlocking(wav, gen)
        } else {
            val tmp = File(appCtx.cacheDir, "asset_${System.nanoTime()}.wav")
            try {
                tmp.writeBytes(bytes)
                playMediaPlayerBlocking(tmp.absolutePath, gen)
            } finally {
                try { tmp.delete() } catch (ignored: Exception) { }
            }
        }
    }

    private fun playFileBlocking(item: AudioItem.FileClip, gen: Int) {
        val f = File(item.path)
        if (!f.exists() || f.length() == 0L) return
        if (f.name.endsWith(".wav", ignoreCase = true) && f.length() <= 16 * 1024 * 1024) {
            val bytes = try { f.readBytes() } catch (e: Exception) { null }
            val wav = bytes?.let { parseWav(it) }
            if (wav != null) {
                playPcmBlocking(wav, gen)
                return
            }
        }
        playMediaPlayerBlocking(f.absolutePath, gen)
    }

    private fun playPcmBlocking(wav: DecodedWav, gen: Int) {
        if (generation.get() != gen || released) return
        val track = ensureTrack(wav.sampleRate, wav.channels) ?: return
        val bytesPerFrame = 2 * wav.channels
        val totalFrames = wav.pcm.size / bytesPerFrame
        if (totalFrames <= 0) return

        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.flush()
            }
            val startHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }

            var offset = 0
            val chunkSize = 4096 // ~42 ms at 48 kHz mono 16-bit
            while (offset < wav.pcm.size) {
                if (generation.get() != gen || released) {
                    stopTrackNow()
                    return
                }
                val len = minOf(chunkSize, wav.pcm.size - offset)
                val written = track.write(wav.pcm, offset, len, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }

            // Wait for hardware buffer drain only if nothing else is immediately queued,
            // so back-to-back sentences play gaplessly while the last sentence finishes
            // cleanly before the mic opens.
            if (queue.isEmpty() && generation.get() == gen && !released) {
                val targetHead = startHead + totalFrames
                val waitDeadline = System.currentTimeMillis() + 350L
                while (queue.isEmpty() && generation.get() == gen && !released && System.currentTimeMillis() < waitDeadline) {
                    val curHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (curHead >= targetHead) break
                    try { Thread.sleep(15) } catch (e: InterruptedException) { break }
                }
                if (queue.isEmpty()) {
                    try {
                        track.pause()
                        track.flush()
                    } catch (ignored: Exception) { }
                }
            }
        } catch (e: Exception) {
            Log.e("FakeCallDbg", "PcmAudioPlayer.playPcmBlocking failed", e)
            releaseTrack()
        }
    }

    private fun ensureTrack(sampleRate: Int, channels: Int): AudioTrack? {
        val existing = currentTrack
        if (existing != null &&
            existing.state == AudioTrack.STATE_INITIALIZED &&
            currentTrackRate == sampleRate &&
            currentTrackChannels == channels
        ) {
            return existing
        }
        releaseTrack()
        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
                .also {
                    currentTrack = it
                    currentTrackRate = sampleRate
                    currentTrackChannels = channels
                }
        } catch (e: Exception) {
            Log.e("FakeCallDbg", "PcmAudioPlayer.ensureTrack failed", e)
            null
        }
    }

    private fun stopTrackNow() {
        try { currentTrack?.pause() } catch (ignored: Exception) { }
        try { currentTrack?.flush() } catch (ignored: Exception) { }
    }

    private fun releaseTrack() {
        try { currentTrack?.stop() } catch (ignored: Exception) { }
        try { currentTrack?.release() } catch (ignored: Exception) { }
        currentTrack = null
        currentTrackRate = 0
        currentTrackChannels = 0
    }

    private fun playMediaPlayerBlocking(path: String, gen: Int) {
        if (generation.get() != gen || released) return
        val doneLock = Object()
        var finished = false
        val mp = MediaPlayer()
        currentMediaPlayer = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(path)
            mp.setOnCompletionListener {
                synchronized(doneLock) { finished = true; doneLock.notifyAll() }
            }
            mp.setOnErrorListener { _, _, _ ->
                synchronized(doneLock) { finished = true; doneLock.notifyAll() }
                true
            }
            mp.prepare()
            if (generation.get() != gen || released) return
            mp.start()
            synchronized(doneLock) {
                while (!finished && generation.get() == gen && !released) {
                    try { doneLock.wait(200) } catch (e: InterruptedException) { break }
                }
            }
        } finally {
            stopMediaPlayerNow()
        }
    }

    private fun stopMediaPlayerNow() {
        val mp = currentMediaPlayer ?: return
        currentMediaPlayer = null
        try { mp.stop() } catch (ignored: Exception) { }
        try { mp.release() } catch (ignored: Exception) { }
    }

    companion object {
        private const val MAX_QUEUE = 16
        /**
         * Ear-verified context-neutral backchannel fillers only:
         * - f2.wav: 「うん、うん」
         * - f5.wav: 「えっとね」
         * - f6.wav: 「ちょっと待ってね」
         * Excludes broken f1.wav and context-clashing f3/f4/f7/f8 (e.g. 「本当に」).
         */
        private val EMPATHY_FILLER_ASSETS = listOf(
            "fillers/f2.wav",
            "fillers/empathy_2.wav"
        )
        private val THINK_FILLER_ASSETS = listOf(
            "fillers/f5.wav",
            "fillers/f6.wav",
            "fillers/think_3.wav",
            "fillers/think_4.wav"
        )
        private val WAIT2_FILLER_ASSETS = listOf(
            "fillers/wait2_1.wav",
            "fillers/wait2_2.wav",
            "fillers/wait2_3.wav"
        )
        private val CANNED_WORK_ASSETS = listOf(
            "canned/cmd_work_1.wav" to "オッケー、進めてみるね！",
            "canned/cmd_work_2.wav" to "了解、やってみるね！"
        )
        private val CANNED_LOOKUP_ASSETS = listOf(
            "canned/cmd_lookup_1.wav" to "オッケー、調べてみるね！",
            "canned/cmd_lookup_2.wav" to "了解、確認してみるね！"
        )
        private val CANNED_UNSUPPORTED_ASSETS = listOf(
            "canned/unsupported_1.wav" to "ごめーん、まだその機能には対応してないんだ！",
            "canned/unsupported_2.wav" to "ごめんね、それはまだ対応してないんだ！"
        )
        private val CANNED_MORNING_ASSETS = listOf(
            "canned/morning_reply_1.wav" to "おはよう！今日もいい一日にしようね！"
        )
        private val CANNED_MORNING_NO_SCHEDULE_ASSETS = listOf(
            "canned/morning_no_schedule_1.wav" to "今日の予定は今のところ入ってないよ。"
        )
        private val CANNED_BYE_ASSETS = listOf(
            "canned/bye_1.wav" to "了解、また何かあったらかけてね！"
        )
        private val CANNED_ASK_HANGUP_ASSETS = listOf(
            "canned/ask_hangup_1.wav" to "もう切る？"
        )
        private val CANNED_ASK_MORE_ASSETS = listOf(
            "canned/ask_more_1.wav" to "まだあるかな？"
        )
        private val CANNED_SCHEDULE_OK_ASSETS = listOf(
            "canned/schedule_ok_1.wav" to "了解、その時間にセットしたよ！"
        )
        private val CANNED_WAIT_OK_ASSETS = listOf(
            "canned/wait_ok_1.wav" to "うん、ゆっくりで大丈夫だよ！"
        )
        private val SAFE_FILLER_ASSETS = listOf(
            "fillers/f2.wav",
            "fillers/f5.wav",
            "fillers/f6.wav"
        )

        @Volatile private var cachedFillers: List<DecodedWav> = emptyList()
        @Volatile private var cachedEmpathyFillers: List<DecodedWav> = emptyList()
        @Volatile private var cachedThinkFillers: List<DecodedWav> = emptyList()
        @Volatile private var cachedWait2Fillers: List<DecodedWav> = emptyList()
        @Volatile private var cachedCannedWork: List<Pair<DecodedWav, String>> = emptyList()
        @Volatile private var cachedCannedLookup: List<Pair<DecodedWav, String>> = emptyList()
        @Volatile private var cachedCannedUnsupported: List<Pair<DecodedWav, String>> = emptyList()
        @Volatile private var cachedCannedMorning: List<Pair<DecodedWav, String>> = emptyList()
        @Volatile private var cachedCannedMorningNoSchedule: List<Pair<DecodedWav, String>> = emptyList()
        @Volatile private var cachedCannedBye: List<Pair<DecodedWav, String>> = emptyList()
        @Volatile private var cachedCannedAskHangup: List<Pair<DecodedWav, String>> = emptyList()
        @Volatile private var cachedCannedAskMore: List<Pair<DecodedWav, String>> = emptyList()
        @Volatile private var cachedCannedScheduleOk: List<Pair<DecodedWav, String>> = emptyList()
        @Volatile private var cachedCannedWaitOk: List<Pair<DecodedWav, String>> = emptyList()
        @Volatile private var preloadStarted = false

        private fun loadAssetList(ctx: Context, assets: List<String>): List<DecodedWav> {
            val list = ArrayList<DecodedWav>(assets.size)
            for (asset in assets) {
                try {
                    val bytes = ctx.assets.open(asset).use { it.readBytes() }
                    parseWav(bytes)?.let { list.add(it) }
                } catch (e: Exception) {
                    Log.w("FakeCallDbg", "Failed to preload filler $asset", e)
                }
            }
            return list
        }

        private fun loadLabeledAssets(ctx: Context, assets: List<Pair<String, String>>): List<Pair<DecodedWav, String>> {
            val list = ArrayList<Pair<DecodedWav, String>>(assets.size)
            for ((asset, label) in assets) {
                try {
                    val bytes = ctx.assets.open(asset).use { it.readBytes() }
                    parseWav(bytes)?.let { list.add(it to label) }
                } catch (e: Exception) {
                    Log.w("FakeCallDbg", "Failed to preload canned $asset", e)
                }
            }
            return list
        }

        fun preloadFillersAsync(ctx: Context) {
            if (preloadStarted) return
            preloadStarted = true
            val appCtx = ctx.applicationContext
            Thread({
                val empathy = loadAssetList(appCtx, EMPATHY_FILLER_ASSETS)
                val think = loadAssetList(appCtx, THINK_FILLER_ASSETS)
                val wait2 = loadAssetList(appCtx, WAIT2_FILLER_ASSETS)
                cachedEmpathyFillers = empathy
                cachedThinkFillers = think
                cachedWait2Fillers = wait2
                cachedFillers = empathy + think
                cachedCannedWork = loadLabeledAssets(appCtx, CANNED_WORK_ASSETS)
                cachedCannedLookup = loadLabeledAssets(appCtx, CANNED_LOOKUP_ASSETS)
                cachedCannedUnsupported = loadLabeledAssets(appCtx, CANNED_UNSUPPORTED_ASSETS)
                cachedCannedMorning = loadLabeledAssets(appCtx, CANNED_MORNING_ASSETS)
                cachedCannedMorningNoSchedule = loadLabeledAssets(appCtx, CANNED_MORNING_NO_SCHEDULE_ASSETS)
                cachedCannedBye = loadLabeledAssets(appCtx, CANNED_BYE_ASSETS)
                cachedCannedAskHangup = loadLabeledAssets(appCtx, CANNED_ASK_HANGUP_ASSETS)
                cachedCannedAskMore = loadLabeledAssets(appCtx, CANNED_ASK_MORE_ASSETS)
                cachedCannedScheduleOk = loadLabeledAssets(appCtx, CANNED_SCHEDULE_OK_ASSETS)
                cachedCannedWaitOk = loadLabeledAssets(appCtx, CANNED_WAIT_OK_ASSETS)
                Log.d("FakeCallDbg", "PcmAudioPlayer: preloaded fillers & canned clips")
            }, "fakecall-preload").apply {
                isDaemon = true
                start()
            }
        }

        /**
         * Parses a standard uncompressed 16-bit PCM RIFF/WAVE byte array, properly
         * walking RIFF sub-chunks (`fmt `, `data`, skipping `LIST`/`INFO` etc.).
         */
        fun parseWav(bytes: ByteArray): DecodedWav? {
            if (bytes.size < 44) return null
            if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
                bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()
            ) return null
            if (bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte() ||
                bytes[10] != 'V'.code.toByte() || bytes[11] != 'E'.code.toByte()
            ) return null

            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var audioFormat = 0
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataOffset = -1
            var dataSize = 0

            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = bb.getInt(pos + 4)
                if (size < 0 || pos + 8L + size > bytes.size) {
                    // Handle truncated/streaming WAV where data chunk size is 0xFFFFFFFF or slightly over
                    if (id == "data" && pos + 8 < bytes.size) {
                        dataOffset = pos + 8
                        dataSize = bytes.size - dataOffset
                    }
                    break
                }
                if (id == "fmt " && size >= 16) {
                    audioFormat = bb.getShort(pos + 8).toInt() and 0xFFFF
                    channels = bb.getShort(pos + 10).toInt() and 0xFFFF
                    sampleRate = bb.getInt(pos + 12)
                    bitsPerSample = bb.getShort(pos + 22).toInt() and 0xFFFF
                } else if (id == "data") {
                    dataOffset = pos + 8
                    dataSize = size
                    break
                }
                pos += 8 + size + (size and 1) // RIFF chunks are word-aligned
            }

            if (audioFormat != 1 || bitsPerSample != 16 || channels !in 1..2 ||
                sampleRate !in 8000..96000 || dataOffset < 0 || dataSize <= 0
            ) {
                return null
            }
            val safeEnd = minOf(dataOffset + dataSize, bytes.size)
            val pcm = bytes.copyOfRange(dataOffset, safeEnd)
            return DecodedWav(pcm = pcm, sampleRate = sampleRate, channels = channels)
        }
    }
}
