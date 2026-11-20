package dev.muse.fakecall

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/**
 * Unified custom-UI incoming & in-call phone screen (v1.0.0).
 *
 * - Authentic dark-gradient phone call UI with animated pulsing rings, crisp
 *   vector-drawn phone/mic/speaker controls, and live tabular timer.
 * - Real phone hardware behavior via [CallAudioRouter]: `MODE_IN_COMMUNICATION`,
 *   earpiece/Bluetooth default with one-tap speaker toggle, and proximity-sensor
 *   screen-off when held to the ear.
 * - Low-latency zero-disk audio playback via [PcmAudioPlayer] (`AudioTrack`) +
 *   conditional delayed backchannel filler (`Store.fillerDelayMs`, default 1.2 s).
 * - Resilient hands-free STT loop with partial-result rescue, mute toggle, and
 *   remote hangup (`POST /hangup`).
 */
class CallActivity : Activity(), CallRuntime.ActiveCallHandler {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioRouter: CallAudioRouter
    private var audioPlayer: PcmAudioPlayer? = null
    private var tts: TextToSpeech? = null

    private var message: String = Store.DEFAULT_MESSAGE
    private var callerName: String = Store.DEFAULT_CALLER
    private var morning: Boolean = false
    private var attempt: Int = 1
    private var session: Long = 0L
    private var oneShotAudio: String? = null
    private var dialog: Boolean = true

    override val sessionId: Long get() = session

    private var ringing = false
    private var dialing = false
    private var outgoing = false
    private var ringServiceWasRunning = false
    private var inCall = false
    private var callEnded = false
    private var callStartMs = 0L
    private var ringbackTone: RingbackTone? = null

    private var timerView: TextView? = null
    private var statusPillView: TextView? = null
    private var transcriptPreviewView: TextView? = null
    private var conversationScrollView: ScrollView? = null
    private var conversationLogContainer: LinearLayout? = null
    private var speakingRingView: View? = null
    private var muteCircleView: ControlButtonHolder? = null
    private var speakerCircleView: ControlButtonHolder? = null
    private var relistenCircleView: ControlButtonHolder? = null
    private var hangupCircleView: ControlButtonHolder? = null
    private var ambientPlayer: AmbientSoundPlayer? = null
    private val activeAnimators = ArrayList<ValueAnimator>()

    // ---------- dialogue state ----------

    private enum class DialogState { OFF, PLAYING, LISTENING, WAITING, MUTED, IDLE }

    private var dialogState = DialogState.OFF
    private var dialogActivated = false
    private var isMuted = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var lastPartialText: String? = null
    private var micPermissionRequested = false
    private var lastFillerIndex = -1
    private var lastWait2Index = -1
    private var lastUtteranceKind = UtteranceClassifier.UtteranceKind.EMPATHY
    private var fillerPlayedForTurn = false
    private var pendingHangupConfirm = false
    private var lastMuseAudioBytes: ByteArray? = null
    private var lastMuseAudioExt: String = "wav"
    private var turnStartMs = 0L
    private var turnUserText = ""
    private var turnFillerOffsetMs = -1L
    private var turnTypingOffsetMs = -1L
    private var turnWait2OffsetMs = -1L
    private var turnFirstAudioOffsetMs = -1L
    private val turnMuseTexts = ArrayList<String>()
    private var idleStartMs = 0L

    private val timeoutRunnable = Runnable { onNoAnswer() }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!inCall) return
            val sec = (System.currentTimeMillis() - callStartMs) / 1000
            timerView?.text = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
            handler.postDelayed(this, 1000)
        }
    }

    private val retryListenRunnable = Runnable {
        if (inCall && dialog && !isMuted && dialogState == DialogState.LISTENING) {
            startListeningInternal()
        }
    }

    /** Acoustic guard after AudioTrack finishes so mic never catches tail echo. */
    private val postPlayListenRunnable = Runnable {
        if (inCall && dialog && !isMuted &&
            audioPlayer?.isBusy != true &&
            CallRuntime.pendingTtsCount == 0 &&
            (dialogState == DialogState.PLAYING || dialogState == DialogState.IDLE)
        ) {
            beginListeningFlow()
        }
    }

    /** Conditional backchannel filler: fires only if Muse's reply takes longer than fillerDelayMs. */
    private val fillerTimerRunnable = Runnable {
        if (!inCall || !dialog || dialogState != DialogState.WAITING) return@Runnable
        if (fillerPlayedForTurn || audioPlayer?.isBusy == true) return@Runnable
        fillerPlayedForTurn = true
        if (turnStartMs > 0 && turnFillerOffsetMs < 0) {
            turnFillerOffsetMs = System.currentTimeMillis() - turnStartMs
        }
        lastFillerIndex = audioPlayer?.enqueueFillerForKind(lastUtteranceKind, lastFillerIndex) ?: lastFillerIndex
    }

    /** Soft keyboard typing foley (only for QUESTION_LOOKUP) if waiting >2.6 s. */
    private val typingTimerRunnable = Runnable {
        if (!inCall || !dialog || dialogState != DialogState.WAITING) return@Runnable
        if (lastUtteranceKind != UtteranceClassifier.UtteranceKind.QUESTION_LOOKUP) return@Runnable
        if (turnStartMs > 0 && turnTypingOffsetMs < 0) {
            turnTypingOffsetMs = System.currentTimeMillis() - turnStartMs
        }
        ambientPlayer?.setTypingActive(true)
    }

    /** 2nd-stage lookup voice ("今調べ中だよー" etc.) if still waiting >5.6 s on a lookup. */
    private val wait2TimerRunnable = Runnable {
        if (!inCall || !dialog || dialogState != DialogState.WAITING) return@Runnable
        if (lastUtteranceKind != UtteranceClassifier.UtteranceKind.QUESTION_LOOKUP) return@Runnable
        if (audioPlayer?.isBusy == true) return@Runnable
        if (turnStartMs > 0 && turnWait2OffsetMs < 0) {
            turnWait2OffsetMs = System.currentTimeMillis() - turnStartMs
        }
        lastWait2Index = audioPlayer?.enqueueWait2Filler(lastWait2Index) ?: lastWait2Index
    }

    /** Prevents hanging forever in WAITING if Muse VM is offline (e.g. standalone morning call). */
    private val waitingWatchdogRunnable = Runnable {
        if (!inCall || !dialog || dialogState != DialogState.WAITING) return@Runnable
        if (audioPlayer?.isBusy == true || CallRuntime.pendingTtsCount > 0) return@Runnable
        Log.w("FakeCallDbg", "CallActivity: waiting watchdog expired -> resuming listening")
        if (!isMuted) {
            beginListeningFlow()
        }
    }

    /** If Muse does not reply with today's schedule within 8s after a morning greeting, say none. */
    private val morningScheduleFallbackRunnable = Runnable {
        if (!inCall || !dialog || callEnded || dialogState != DialogState.WAITING) return@Runnable
        if (audioPlayer?.isBusy == true || CallRuntime.pendingTtsCount > 0) return@Runnable
        handler.removeCallbacks(typingTimerRunnable)
        handler.removeCallbacks(waitingWatchdogRunnable)
        ambientPlayer?.setTypingActive(false)
        val label = audioPlayer?.enqueueMorningNoScheduleFallback()
        if (label != null) {
            appendConversationLog(callerName, label, isUser = false)
            setDialogState(DialogState.PLAYING)
        } else if (!isMuted) {
            beginListeningFlow()
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            lastPartialText = null
        }
        override fun onBeginningOfSpeech() {
            idleStartMs = System.currentTimeMillis()
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return
            lastPartialText = text
            transcriptPreviewView?.text = "「$text」"
        }

        override fun onError(error: Int) {
            val rescued = lastPartialText?.takeIf { it.isNotBlank() }
            lastPartialText = null
            if (rescued != null &&
                (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            ) {
                Log.d("FakeCallDbg", "CallActivity: rescued partial text on error=$error: $rescued")
                onRecognized(rescued)
                return
            }
            onListenError(error)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: lastPartialText?.takeIf { it.isNotBlank() }
            lastPartialText = null
            onRecognized(text)
        }
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isAlive = true
        audioRouter = CallAudioRouter(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        readExtras(intent)
        CallRuntime.registerHandler(this)

        Log.d(
            "FakeCallDbg",
            "CallActivity.onCreate: answered=${intent.getBooleanExtra(CallStarter.EXTRA_ANSWERED, false)}" +
                " session=$session dialog=$dialog morning=$morning oneShot=$oneShotAudio"
        )

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "dev.muse.fakecall:call"
        )?.apply { acquire(30 * 60 * 1000L) }

        if (outgoing) {
            enterDialing()
        } else if (intent.getBooleanExtra(CallStarter.EXTRA_ANSWERED, false)) {
            enterInCall()
        } else {
            showRingingUi()
            ringing = true
            ringServiceWasRunning = RingService.isRinging
            if (!RingService.isRinging) {
                RingService.startRinging(
                    this, message, callerName, morning, attempt, oneShotAudio, session, dialog
                )
            }
            handler.postDelayed(timeoutRunnable, NO_ANSWER_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (ringing && intent.getBooleanExtra(CallStarter.EXTRA_ANSWERED, false)) {
            readExtras(intent)
            onAnswer()
            return
        }
        if (ringing && ringServiceWasRunning && Store.activeRingSession(this) != session) {
            ringing = false
            handler.removeCallbacks(timeoutRunnable)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(CallStarter.EXTRA_ANSWERED, false) && ringing) {
            readExtras(intent)
            onAnswer()
        }
    }

    private fun readExtras(intent: Intent) {
        message = intent.getStringExtra(CallStarter.EXTRA_MESSAGE) ?: Store.message(this)
        callerName = intent.getStringExtra(CallStarter.EXTRA_CALLER) ?: Store.caller(this)
        morning = intent.getBooleanExtra(CallStarter.EXTRA_MORNING, false)
        attempt = intent.getIntExtra(CallStarter.EXTRA_ATTEMPT, 1)
        oneShotAudio = intent.getStringExtra(CallStarter.EXTRA_AUDIO_PATH)
        session = intent.getLongExtra(CallStarter.EXTRA_SESSION, 0L)
        if (session == 0L) session = System.currentTimeMillis()
        dialog = intent.getBooleanExtra(CallStarter.EXTRA_DIALOG, true)
        outgoing = intent.getBooleanExtra(CallStarter.EXTRA_OUTGOING, false)
    }

    // ---------- CallRuntime.ActiveCallHandler ----------

    override fun onRemoteAccept() {
        handler.post {
            if (!dialing || callEnded) return@post
            connectFromDialing(expectImmediateAudio = false)
        }
    }

    override fun onAudioBytesArrived(bytes: ByteArray, ext: String, textHint: String?) {
        handler.post {
            if (callEnded) return@post
            if (dialing) {
                // Auto-accept outgoing call the instant Muse speaks!
                connectFromDialing(expectImmediateAudio = true)
            }
            if (!inCall) return@post
            handler.removeCallbacks(fillerTimerRunnable)
            handler.removeCallbacks(typingTimerRunnable)
            handler.removeCallbacks(wait2TimerRunnable)
            handler.removeCallbacks(waitingWatchdogRunnable)
            handler.removeCallbacks(morningScheduleFallbackRunnable)
            handler.removeCallbacks(postPlayListenRunnable)
            ambientPlayer?.setTypingActive(false)

            if (dialogState == DialogState.LISTENING) {
                stopListeningInternal()
            }
            if (!dialog) dialog = true
            setDialogState(DialogState.PLAYING)
            if (!textHint.isNullOrBlank()) {
                appendConversationLog(callerName, textHint, isUser = false)
                synchronized(turnMuseTexts) { turnMuseTexts.add(textHint) }
            }
            if (turnStartMs > 0 && turnFirstAudioOffsetMs < 0) {
                turnFirstAudioOffsetMs = System.currentTimeMillis() - turnStartMs
            }
            lastMuseAudioBytes = bytes
            lastMuseAudioExt = ext
            audioPlayer?.enqueueBytes(bytes, ext = ext, tag = "say")
        }
    }

    override fun onRemoteHangup(reason: String) {
        handler.post {
            if (callEnded) return@post
            if (ringing) {
                ringing = false
                handler.removeCallbacks(timeoutRunnable)
                RingService.stopAll(this)
                finalizeCallEnd(reason)
                if (reason == "user_hangup") {
                    finish()
                } else {
                    showCallEndedState()
                }
            } else {
                endCall(reason)
            }
        }
    }

    override fun onPendingTtsCountChanged(remaining: Int) {
        handler.post {
            if (!inCall || callEnded) return@post
            if (remaining == 0 && audioPlayer?.isBusy != true &&
                (dialogState == DialogState.PLAYING || dialogState == DialogState.WAITING)
            ) {
                handler.removeCallbacks(postPlayListenRunnable)
                handler.postDelayed(postPlayListenRunnable, POST_PLAY_GUARD_MS)
            }
        }
    }

    // ---------- UI: Ringing screen ----------

    private fun showRingingUi() {
        stopAnimations()
        val root = phoneBackdropLayout()

        // Top incoming label
        root.addView(TextView(this).apply {
            text = if (morning) "モーニングコール（$attempt 回目）" else "音声通話の着信"
            textSize = 15f
            setTextColor(Color.argb(190, 200, 215, 240))
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(56) })

        // Caller name
        root.addView(TextView(this).apply {
            text = callerName
            textSize = 36f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // Pulsing concentric avatar container
        val avatarBox = FrameLayout(this)
        val ringOuter = View(this).apply {
            background = circleDrawable(Color.argb(45, 110, 145, 255))
        }
        val ringInner = View(this).apply {
            background = circleDrawable(Color.argb(75, 110, 145, 255))
        }
        val avatarCircle = buildCallerAvatarView()
        avatarBox.addView(ringOuter, FrameLayout.LayoutParams(dp(184), dp(184), Gravity.CENTER))
        avatarBox.addView(ringInner, FrameLayout.LayoutParams(dp(148), dp(148), Gravity.CENTER))
        avatarBox.addView(avatarCircle, FrameLayout.LayoutParams(dp(116), dp(116), Gravity.CENTER))

        root.addView(avatarBox, LinearLayout.LayoutParams(dp(200), dp(200)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(40)
        })
        startPulseRing(ringOuter, 0L)
        startPulseRing(ringInner, 500L)

        // Scrollable live conversation log so mispronounced phrases can be spotted immediately
        val logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        conversationLogContainer = logContainer
        val logScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(46, 0, 0, 0))
            }
            addView(logContainer, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        conversationScrollView = logScroll
        root.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            topMargin = dp(10)
            bottomMargin = dp(12)
            leftMargin = dp(20)
            rightMargin = dp(20)
        })

        // Bottom circular Decline / Answer buttons
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val declineBtn = buildActionCircle(
            iconKind = IconKind.HANGUP,
            label = "拒否",
            sizeDp = 74,
            bgColor = Color.rgb(229, 57, 53),
            iconColor = Color.WHITE
        ) { onDecline() }

        val answerBtn = buildActionCircle(
            iconKind = IconKind.ANSWER,
            label = "応答",
            sizeDp = 74,
            bgColor = Color.rgb(46, 175, 86),
            iconColor = Color.WHITE
        ) { onAnswer() }

        buttons.addView(declineBtn.container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(84) })
        buttons.addView(answerBtn.container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(64) })

        startBreathingPulse(answerBtn.circle)
        setContentView(root)
    }

    // ---------- UI: In-Call screen ----------

    private fun showInCallUi() {
        stopAnimations()
        val root = phoneBackdropLayout()

        // Compact avatar with speaking indicator ring
        val avatarBox = FrameLayout(this)
        val glowRing = View(this).apply {
            background = circleDrawable(Color.argb(90, 100, 181, 246))
            alpha = 0f
        }
        speakingRingView = glowRing
        val avatarCircle = buildCallerAvatarView()
        avatarBox.addView(glowRing, FrameLayout.LayoutParams(dp(108), dp(108), Gravity.CENTER))
        avatarBox.addView(avatarCircle, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER))

        root.addView(avatarBox, LinearLayout.LayoutParams(dp(116), dp(116)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(52)
        })

        // Caller name
        root.addView(TextView(this).apply {
            text = callerName
            textSize = 28f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        // Tabular timer
        timerView = TextView(this).apply {
            text = "00:00"
            textSize = 18f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.argb(210, 205, 218, 240))
            gravity = Gravity.CENTER
        }
        root.addView(timerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // Subtle status pill
        val pill = TextView(this).apply {
            text = if (dialog) "接続中…" else "通話中"
            textSize = 13f
            setTextColor(Color.rgb(179, 229, 252))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(38, 129, 212, 250))
            }
        }
        statusPillView = pill
        root.addView(pill, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(18)
        })

        // Subtle single-line transcript preview
        val preview = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.argb(150, 220, 230, 245))
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        transcriptPreviewView = preview
        root.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(14)
            leftMargin = dp(28)
            rightMargin = dp(28)
        })

        // Scrollable live conversation log so mispronounced phrases can be spotted immediately
        val logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        conversationLogContainer = logContainer
        val logScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(46, 0, 0, 0))
            }
            addView(logContainer, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        conversationScrollView = logScroll
        root.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            topMargin = dp(10)
            bottomMargin = dp(12)
            leftMargin = dp(20)
            rightMargin = dp(20)
        })

        // 3-button real phone control row: Mute / Speaker / Re-listen
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val muteHolder = buildActionCircle(
            iconKind = IconKind.MIC,
            label = "ミュート",
            sizeDp = 62,
            bgColor = Color.argb(46, 255, 255, 255),
            iconColor = Color.WHITE
        ) { toggleMute() }
        muteCircleView = muteHolder

        val speakerHolder = buildActionCircle(
            iconKind = IconKind.SPEAKER,
            label = "スピーカー",
            sizeDp = 62,
            bgColor = Color.argb(46, 255, 255, 255),
            iconColor = Color.WHITE
        ) { toggleSpeaker() }
        speakerCircleView = speakerHolder

        val relistenHolder = buildActionCircle(
            iconKind = IconKind.REFRESH,
            label = "聴き直し",
            sizeDp = 62,
            bgColor = Color.argb(46, 255, 255, 255),
            iconColor = Color.WHITE
        ) { forceRelisten() }
        relistenCircleView = relistenHolder

        controls.addView(muteHolder.container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(34) })
        controls.addView(speakerHolder.container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(34) })
        controls.addView(relistenHolder.container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(36) })

        // Centered red Hangup circle button
        val hangupHolder = buildActionCircle(
            iconKind = IconKind.HANGUP,
            label = "",
            sizeDp = 74,
            bgColor = Color.rgb(229, 57, 53),
            iconColor = Color.WHITE
        ) { if (callEnded) finish() else endCall("user_hangup") }
        hangupCircleView = hangupHolder

        root.addView(hangupHolder.container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(52)
        })

        setContentView(root)
        refreshControlStyles()
    }

    // ---------- transitions ----------

    private fun enterDialing() {
        dialing = true
        inCall = false
        callEnded = false
        dialog = true

        RingService.enterOngoing(this, callerName, session, morning = false, dialog = true)
        audioRouter.enterCall(startWithSpeaker = Store.defaultSpeakerOn(this))

        showInCallUi()
        timerView?.text = "発信中…"
        statusPillView?.text = "呼び出しています…"

        ringbackTone = RingbackTone().also { it.start() }

        CallEventBus.updateState(
            state = CallEventBus.CallState.DIALING,
            phase = CallEventBus.DialogPhase.OFF,
            session = session,
            morning = false,
            dialog = true,
            muted = false,
            speaker = audioRouter.isSpeakerOn
        )
        CallEventBus.emit(
            "CALL_OUTGOING",
            session,
            mapOf("caller" to callerName, "dialog" to true)
        )
        handler.postDelayed(timeoutRunnable, NO_ANSWER_MS)
    }

    private fun connectFromDialing(expectImmediateAudio: Boolean) {
        if (!dialing || callEnded) return
        dialing = false
        handler.removeCallbacks(timeoutRunnable)
        ringbackTone?.stop()
        ringbackTone = null

        inCall = true
        callStartMs = System.currentTimeMillis()
        idleStartMs = callStartMs

        audioPlayer = PcmAudioPlayer(
            context = this,
            onPlaybackStarted = { isFiller, _ ->
                if (!inCall || callEnded) return@PcmAudioPlayer
                if (!isFiller) setDialogState(DialogState.PLAYING)
                setSpeakingGlow(true)
            },
            onQueueDrained = {
                setSpeakingGlow(false)
                if (!inCall || callEnded) return@PcmAudioPlayer
                onAudioQueueDrained()
            }
        )
        ambientPlayer = AmbientSoundPlayer(this, morningOverride = morning).apply { start() }

        timerView?.text = "00:00"
        handler.post(timerRunnable)

        CallEventBus.updateState(
            state = CallEventBus.CallState.ACTIVE,
            phase = if (expectImmediateAudio) CallEventBus.DialogPhase.PLAYING else CallEventBus.DialogPhase.LISTENING,
            session = session,
            morning = false,
            dialog = true,
            muted = isMuted,
            speaker = audioRouter.isSpeakerOn
        )
        CallEventBus.emit(
            "CALL_ANSWERED",
            session,
            mapOf("morning" to false, "dialog" to true, "outgoing" to true)
        )

        DialogStore.setDialogActive(this, true)
        dialogActivated = true
        if (expectImmediateAudio) {
            setDialogState(DialogState.PLAYING)
        } else {
            beginListeningFlow()
        }
    }

    private fun onAnswer() {
        if (!ringing) return
        ringing = false
        handler.removeCallbacks(timeoutRunnable)
        enterInCall()
    }

    private fun enterInCall() {
        if (inCall) return
        inCall = true
        callEnded = false
        callStartMs = System.currentTimeMillis()
        idleStartMs = callStartMs

        // Transition RingService to silent ongoing-call CallStyle notification
        // (keeps process alive on HyperOS even when proximity turns screen off).
        RingService.enterOngoing(this, callerName, session, morning, dialog)

        // Enter phone-call audio routing (earpiece by default unless user configured speaker).
        audioRouter.enterCall(startWithSpeaker = Store.defaultSpeakerOn(this))

        // Initialize zero-disk AudioTrack player and ambient presence bed.
        audioPlayer = PcmAudioPlayer(
            context = this,
            onPlaybackStarted = { isFiller, _ ->
                if (!inCall || callEnded) return@PcmAudioPlayer
                if (!isFiller) {
                    setDialogState(DialogState.PLAYING)
                }
                setSpeakingGlow(true)
            },
            onQueueDrained = {
                setSpeakingGlow(false)
                if (!inCall || callEnded) return@PcmAudioPlayer
                onAudioQueueDrained()
            }
        )
        ambientPlayer = AmbientSoundPlayer(this, morningOverride = morning).apply { start() }

        showInCallUi()
        handler.post(timerRunnable)

        CallEventBus.updateState(
            state = CallEventBus.CallState.ACTIVE,
            phase = if (dialog) CallEventBus.DialogPhase.PLAYING else CallEventBus.DialogPhase.OFF,
            session = session,
            morning = morning,
            dialog = dialog,
            muted = false,
            speaker = audioRouter.isSpeakerOn
        )
        CallEventBus.emit(
            "CALL_ANSWERED",
            session,
            mapOf("morning" to morning, "dialog" to dialog)
        )

        if (dialog) {
            DialogStore.setDialogActive(this, true)
            dialogActivated = true
            setDialogState(DialogState.PLAYING)
        }

        playOpeningAudio()
    }

    private fun onDecline() {
        if (!ringing) return
        ringing = false
        handler.removeCallbacks(timeoutRunnable)
        RingService.stopAll(this)
        maybeRetry()
        finalizeCallEnd("declined")
        finish()
    }

    private fun onNoAnswer() {
        if (dialing) {
            dialing = false
            ringbackTone?.stop()
            ringbackTone = null
            Toast.makeText(this, "${callerName} に繋がりませんでした", Toast.LENGTH_SHORT).show()
            endCall("no_answer")
            return
        }
        if (!ringing) return
        ringing = false
        handler.removeCallbacks(timeoutRunnable)
        RingService.stopAll(this)
        maybeRetry()
        finalizeCallEnd("no_answer")
        finish()
    }

    private fun maybeRetry() {
        if (CallStarter.scheduleRetryOnce(this, session, message, callerName, morning, attempt)) {
            Toast.makeText(this, "5分後に再着信します", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- audio playback ----------

    /**
     * Opening audio priority:
     * 0. One-shot audio attached to remote call (or PC-TTS generated via X-Text)
     * 1. User-picked custom audio file
     * 2. Bundled Muse-voice clip (`assets/morning.wav`)
     * 3. Android Japanese TTS fallback
     */
    private fun playOpeningAudio() {
        val player = audioPlayer
        if (player != null) {
            oneShotAudio?.let { path ->
                val f = File(path)
                if (f.exists() && f.length() > 0 &&
                    player.enqueueFile(f.absolutePath, deleteAfter = true, tag = "opening_oneshot")
                ) {
                    oneShotAudio = null // owned by player now
                    return
                }
            }
            Store.customAudioPath(this)?.let { path ->
                val f = File(path)
                if (f.exists() && f.length() > 0 &&
                    player.enqueueFile(f.absolutePath, deleteAfter = false, tag = "opening_custom")
                ) {
                    return
                }
            }
            try {
                if (assets.list("")?.contains(BUNDLED_CLIP) == true &&
                    player.enqueueAsset(BUNDLED_CLIP, tag = "opening_bundled")
                ) {
                    return
                }
            } catch (e: Exception) { }
        }
        speakFallbackTts { onAudioQueueDrained() }
    }

    private fun speakFallbackTts(onDone: () -> Unit) {
        try {
            tts = TextToSpeech(this) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    handler.post { onDone() }
                    return@TextToSpeech
                }
                val engine = tts ?: run {
                    handler.post { onDone() }
                    return@TextToSpeech
                }
                engine.language = Locale.JAPANESE
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        handler.post { onDone() }
                    }
                    @Deprecated("deprecated in API 21")
                    override fun onError(utteranceId: String?) {
                        handler.post { onDone() }
                    }
                })
                engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "wake-message")
            }
        } catch (e: Exception) {
            onDone()
        }
    }

    private fun onAudioQueueDrained() {
        if (!inCall || callEnded || !dialog) return
        // Do NOT open mic if follow-up sentences are still synthesizing on PC TTS!
        if (CallRuntime.pendingTtsCount > 0) return
        // If we just finished a filler clip while still waiting for Muse's reply, stay WAITING.
        if (dialogState == DialogState.WAITING) return
        if (isMuted) {
            setDialogState(DialogState.MUTED)
            return
        }
        handler.removeCallbacks(postPlayListenRunnable)
        handler.postDelayed(postPlayListenRunnable, POST_PLAY_GUARD_MS)
        flushTurnLogIfReady()
    }

    private fun flushTurnLogIfReady() {
        val start = turnStartMs
        if (start <= 0L) return
        val totalMs = System.currentTimeMillis() - start
        val userTxt = turnUserText
        val museTxt = synchronized(turnMuseTexts) {
            val joined = turnMuseTexts.joinToString(" ")
            turnMuseTexts.clear()
            joined
        }
        turnStartMs = 0L
        val json = "{\"ts\":${start / 1000.0}" +
            ",\"session\":$session" +
            ",\"kind\":\"${lastUtteranceKind.name}\"" +
            ",\"user\":\"${CallEventBus.jsonEscape(userTxt)}\"" +
            ",\"muse\":\"${CallEventBus.jsonEscape(museTxt)}\"" +
            ",\"filler_ms\":$turnFillerOffsetMs" +
            ",\"typing_ms\":$turnTypingOffsetMs" +
            ",\"wait2_ms\":$turnWait2OffsetMs" +
            ",\"first_audio_ms\":$turnFirstAudioOffsetMs" +
            ",\"turn_total_ms\":$totalMs" +
            "}"
        LocalTtsClient.postTurnLogAsync(Store.ttsServerUrl(this), json)
    }

    // ---------- controls ----------

    private fun toggleMute() {
        isMuted = !isMuted
        CallEventBus.updateState(muted = isMuted)
        if (isMuted) {
            stopListeningInternal()
            if (dialogState == DialogState.LISTENING || dialogState == DialogState.IDLE) {
                setDialogState(DialogState.MUTED)
            }
        } else {
            if (dialog && inCall && audioPlayer?.isBusy != true &&
                CallRuntime.pendingTtsCount == 0 &&
                dialogState != DialogState.WAITING
            ) {
                beginListeningFlow()
            }
        }
        refreshControlStyles()
    }

    private fun toggleSpeaker() {
        audioRouter.toggleSpeaker()
        refreshControlStyles()
    }

    private fun forceRelisten() {
        if (!inCall || !dialog) return
        if (isMuted) {
            isMuted = false
            CallEventBus.updateState(muted = false)
            refreshControlStyles()
        }
        handler.removeCallbacks(fillerTimerRunnable)
        handler.removeCallbacks(typingTimerRunnable)
        handler.removeCallbacks(wait2TimerRunnable)
        handler.removeCallbacks(waitingWatchdogRunnable)
        handler.removeCallbacks(morningScheduleFallbackRunnable)
        ambientPlayer?.setTypingActive(false)
        stopListeningInternal()
        idleStartMs = System.currentTimeMillis()
        beginListeningFlow()
    }

    private fun refreshControlStyles() {
        muteCircleView?.setActive(isMuted)
        speakerCircleView?.setActive(audioRouter.isSpeakerOn)
    }

    // ---------- dialogue / STT loop ----------

    private fun setDialogState(state: DialogState) {
        dialogState = state
        val busPhase = when (state) {
            DialogState.OFF -> CallEventBus.DialogPhase.OFF
            DialogState.PLAYING -> CallEventBus.DialogPhase.PLAYING
            DialogState.LISTENING -> CallEventBus.DialogPhase.LISTENING
            DialogState.WAITING -> CallEventBus.DialogPhase.WAITING
            DialogState.MUTED -> CallEventBus.DialogPhase.MUTED
            DialogState.IDLE -> CallEventBus.DialogPhase.IDLE
        }
        CallEventBus.updateState(phase = busPhase)

        val label = when (state) {
            DialogState.OFF -> "通話中"
            DialogState.PLAYING -> "話しています…"
            DialogState.LISTENING -> "聞いています…"
            DialogState.WAITING -> "考え中…"
            DialogState.MUTED -> "ミュート中"
            DialogState.IDLE -> "待機中"
        }
        statusPillView?.text = label
        ambientPlayer?.setMode(
            when (state) {
                DialogState.LISTENING -> AmbientSoundPlayer.Mode.LISTENING
                DialogState.MUTED -> AmbientSoundPlayer.Mode.MUTED
                else -> AmbientSoundPlayer.Mode.NORMAL
            }
        )
        if (state != DialogState.WAITING) {
            ambientPlayer?.setTypingActive(false)
        }
    }

    private fun appendConversationLog(speaker: String, text: String, isUser: Boolean) {
        val container = conversationLogContainer ?: return
        val scroll = conversationScrollView
        val line = TextView(this).apply {
            this.text = "$speaker: $text"
            textSize = 13f
            setTextColor(
                if (isUser) Color.rgb(186, 220, 255) else Color.rgb(235, 245, 240)
            )
            setPadding(0, dp(2), 0, dp(4))
        }
        container.addView(line)
        while (container.childCount > 80) {
            container.removeViewAt(0)
        }
        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun beginListeningFlow() {
        if (!dialog || !inCall || callEnded) return
        if (isMuted) {
            setDialogState(DialogState.MUTED)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            setDialogState(DialogState.IDLE)
            if (!micPermissionRequested) {
                micPermissionRequested = true
                statusPillView?.text = "マイク権限を許可してください"
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            } else {
                statusPillView?.text = "マイク未許可"
            }
            return
        }
        idleStartMs = System.currentTimeMillis()
        setDialogState(DialogState.LISTENING)
        startListeningInternal()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_RECORD_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (dialog && inCall && !isMuted && dialogState == DialogState.IDLE) {
                beginListeningFlow()
            }
        } else if (dialog && inCall) {
            setDialogState(DialogState.IDLE)
            statusPillView?.text = "マイク未許可"
        }
    }

    private fun startListeningInternal() {
        if (!dialog || !inCall || callEnded || isMuted) return
        if (dialogState != DialogState.LISTENING) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setDialogState(DialogState.IDLE)
            statusPillView?.text = "音声認識が利用できません"
            return
        }
        lastPartialText = null
        try {
            val rec = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(recognitionListener)
                speechRecognizer = it
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SILENCE_ENDPOINT_MS
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SILENCE_ENDPOINT_MS
                )
            }
            rec.startListening(intent)
        } catch (e: Exception) {
            Log.e("FakeCallDbg", "CallActivity.startListeningInternal failed", e)
            onListenError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun stopListeningInternal() {
        handler.removeCallbacks(retryListenRunnable)
        try { speechRecognizer?.cancel() } catch (e: Exception) { }
        try { speechRecognizer?.destroy() } catch (e: Exception) { }
        speechRecognizer = null
    }

    private fun onRecognized(text: String?) {
        if (!dialog || !inCall || callEnded || dialogState != DialogState.LISTENING) return
        if (text == null) {
            onListenError(SpeechRecognizer.ERROR_NO_MATCH)
            return
        }
        stopListeningInternal()
        idleStartMs = System.currentTimeMillis()
        transcriptPreviewView?.text = "「$text」"
        appendConversationLog("あなた", text, isUser = true)
        lastUtteranceKind = UtteranceClassifier.classifyAndLearn(this, text)
        val route = UtteranceClassifier.routeCommand(text)
        turnStartMs = idleStartMs
        turnUserText = text
        turnFillerOffsetMs = -1L
        turnTypingOffsetMs = -1L
        turnWait2OffsetMs = -1L
        turnFirstAudioOffsetMs = -1L
        synchronized(turnMuseTexts) { turnMuseTexts.clear() }

        handler.removeCallbacks(fillerTimerRunnable)
        handler.removeCallbacks(typingTimerRunnable)
        handler.removeCallbacks(wait2TimerRunnable)
        handler.removeCallbacks(waitingWatchdogRunnable)
        handler.removeCallbacks(morningScheduleFallbackRunnable)

        if (pendingHangupConfirm) {
            pendingHangupConfirm = false
            if (route == UtteranceClassifier.CommandRoute.HANGUP_NOW ||
                route == UtteranceClassifier.CommandRoute.GREETING_BYE ||
                UtteranceClassifier.isAffirmativeForHangup(text)
            ) {
                playCannedAndFinish(UtteranceClassifier.CommandRoute.CONFIRM_YES)
                return
            }
            // If user gave a new actionable command right after "もう切る？", execute it directly.
            val isActionable = route == UtteranceClassifier.CommandRoute.COMMAND_WORK ||
                route == UtteranceClassifier.CommandRoute.COMMAND_LOOKUP ||
                route == UtteranceClassifier.CommandRoute.SCHEDULE_ALARM ||
                route == UtteranceClassifier.CommandRoute.REPLAY_LAST
            if (!isActionable) {
                playCannedReply(UtteranceClassifier.CommandRoute.ASK_MORE)
                return
            }
        }

        when (route) {
            UtteranceClassifier.CommandRoute.HANGUP_NOW,
            UtteranceClassifier.CommandRoute.GREETING_BYE -> {
                playCannedAndFinish(route)
            }
            UtteranceClassifier.CommandRoute.THANKS_ACK -> {
                pendingHangupConfirm = true
                playCannedReply(UtteranceClassifier.CommandRoute.ASK_HANGUP)
            }
            UtteranceClassifier.CommandRoute.SCHEDULE_ALARM -> {
                val parsed = UtteranceClassifier.parseScheduleRequest(text)
                if (parsed != null) {
                    Store.saveSchedule(this, parsed.hour, parsed.minute, parsed.daily)
                    Scheduler.scheduleLocal(this, parsed.triggerAtMillis)
                    appendConversationLog("システム", "モーニングコール予約: ${parsed.summary}", isUser = false)
                    CallEventBus.emit(
                        "SCHEDULE_UPDATED",
                        session,
                        mapOf("hour" to parsed.hour, "minute" to parsed.minute, "daily" to parsed.daily, "text" to text)
                    )
                }
                playCannedReply(UtteranceClassifier.CommandRoute.SCHEDULE_ALARM)
            }
            UtteranceClassifier.CommandRoute.REPLAY_LAST -> {
                val bytes = lastMuseAudioBytes
                if (bytes != null && bytes.isNotEmpty()) {
                    setDialogState(DialogState.PLAYING)
                    audioPlayer?.enqueueBytes(bytes, ext = lastMuseAudioExt, tag = "replay")
                } else {
                    playCannedReply(UtteranceClassifier.CommandRoute.UNSUPPORTED)
                }
            }
            UtteranceClassifier.CommandRoute.WAIT_PAUSE -> {
                idleStartMs = System.currentTimeMillis()
                playCannedReply(UtteranceClassifier.CommandRoute.WAIT_PAUSE)
            }
            UtteranceClassifier.CommandRoute.SPEAKER_ON -> {
                if (!audioRouter.isSpeakerOn) toggleSpeaker()
                beginListeningFlow()
            }
            UtteranceClassifier.CommandRoute.SPEAKER_OFF -> {
                if (audioRouter.isSpeakerOn) toggleSpeaker()
                beginListeningFlow()
            }
            UtteranceClassifier.CommandRoute.GREETING_MORNING -> {
                val ackLabel = audioPlayer?.enqueueCanned(UtteranceClassifier.CommandRoute.GREETING_MORNING)
                if (ackLabel != null) {
                    appendConversationLog(callerName, ackLabel, isUser = false)
                }
                DialogStore.recordUtterance(this, text, session)
                CallEventBus.emit(
                    "COMMAND_REQUEST",
                    session,
                    mapOf("route" to route.name, "text" to text)
                )
                fillerPlayedForTurn = true
                setDialogState(DialogState.WAITING)
                handler.postDelayed(morningScheduleFallbackRunnable, 8000L)
                handler.postDelayed(waitingWatchdogRunnable, WAITING_WATCHDOG_MS)
            }
            UtteranceClassifier.CommandRoute.COMMAND_WORK,
            UtteranceClassifier.CommandRoute.COMMAND_LOOKUP -> {
                val ackLabel = audioPlayer?.enqueueCanned(route)
                if (ackLabel != null) {
                    appendConversationLog(callerName, ackLabel, isUser = false)
                }
                DialogStore.recordUtterance(this, text, session)
                CallEventBus.emit(
                    "COMMAND_REQUEST",
                    session,
                    mapOf("route" to route.name, "text" to text)
                )
                fillerPlayedForTurn = true
                setDialogState(DialogState.WAITING)
                handler.postDelayed(typingTimerRunnable, 2600L)
                handler.postDelayed(waitingWatchdogRunnable, WAITING_WATCHDOG_MS)
            }
            else -> {
                playCannedReply(route)
            }
        }
    }

    private fun playCannedReply(route: UtteranceClassifier.CommandRoute) {
        val label = audioPlayer?.enqueueCanned(route)
        if (label != null) {
            appendConversationLog(callerName, label, isUser = false)
            setDialogState(DialogState.PLAYING)
        } else {
            beginListeningFlow()
        }
    }

    private fun playCannedAndFinish(route: UtteranceClassifier.CommandRoute) {
        val label = audioPlayer?.enqueueCanned(route) {
            handler.postDelayed({
                if (!callEnded) endCall("voice_hangup")
            }, 180L)
        }
        if (label != null) {
            appendConversationLog(callerName, label, isUser = false)
            setDialogState(DialogState.PLAYING)
        } else {
            endCall("voice_hangup")
        }
    }

    /**
     * Resilient hands-free retry: keeps re-arming SpeechRecognizer on silence/no-match
     * so holding the phone to your ear never stops listening prematurely. Automatically
     * ends the call only if completely idle for [IDLE_HANGUP_MS] (3 minutes).
     */
    private fun onListenError(error: Int) {
        if (!dialog || !inCall || callEnded || isMuted) return
        if (dialogState != DialogState.LISTENING) return
        stopListeningInternal()

        val idleElapsed = System.currentTimeMillis() - idleStartMs
        if (idleElapsed >= IDLE_HANGUP_MS) {
            Log.d("FakeCallDbg", "CallActivity: idle watchdog hangup after ${idleElapsed}ms")
            endCall("watchdog")
            return
        }

        val backoff = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
            error == SpeechRecognizer.ERROR_AUDIO
        ) 700L else 300L
        handler.postDelayed(retryListenRunnable, backoff)
    }

    // ---------- teardown ----------

    private fun endCall(reason: String) {
        if (callEnded) return
        dialing = false
        inCall = false
        ringbackTone?.stop()
        ringbackTone = null
        teardownDialog()
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(timerRunnable)
        handler.removeCallbacks(postPlayListenRunnable)
        handler.removeCallbacks(fillerTimerRunnable)
        handler.removeCallbacks(typingTimerRunnable)
        handler.removeCallbacks(wait2TimerRunnable)
        handler.removeCallbacks(waitingWatchdogRunnable)
        handler.removeCallbacks(morningScheduleFallbackRunnable)
        ambientPlayer?.release()
        ambientPlayer = null
        audioPlayer?.clearAndStop()
        audioPlayer?.release()
        audioPlayer = null
        try { tts?.stop() } catch (e: Exception) { }
        audioRouter.exitCall()
        RingService.stopAll(this)
        finalizeCallEnd(reason)
        if (reason == "user_hangup") {
            finish()
        } else {
            showCallEndedState()
        }
    }

    private fun showCallEndedState() {
        stopAnimations()
        if (statusPillView == null) {
            showInCallUi()
        }
        statusPillView?.apply {
            text = "通話が切れました"
            setTextColor(Color.rgb(255, 183, 178))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(56, 229, 57, 53))
            }
        }
        transcriptPreviewView?.text = "相手が通話を終了しました"
        timerView?.setTextColor(Color.argb(135, 205, 218, 240))
        appendConversationLog("システム", "通話が切れました", isUser = false)
        listOf(muteCircleView, speakerCircleView, relistenCircleView).forEach { holder ->
            holder?.circle?.isEnabled = false
            holder?.circle?.isClickable = false
            holder?.container?.alpha = 0.35f
        }
        hangupCircleView?.let { holder ->
            holder.labelView.text = "閉じる"
            holder.labelView.visibility = View.VISIBLE
            holder.circle.setOnClickListener { finish() }
        }
    }

    private fun finalizeCallEnd(reason: String) {
        if (callEnded) return
        callEnded = true
        CallEventBus.updateState(
            state = CallEventBus.CallState.IDLE,
            phase = CallEventBus.DialogPhase.OFF,
            muted = false,
            speaker = false
        )
        CallEventBus.emit("CALL_ENDED", session, mapOf("reason" to reason))
    }

    private fun teardownDialog() {
        handler.removeCallbacks(retryListenRunnable)
        handler.removeCallbacks(fillerTimerRunnable)
        handler.removeCallbacks(typingTimerRunnable)
        handler.removeCallbacks(wait2TimerRunnable)
        handler.removeCallbacks(waitingWatchdogRunnable)
        handler.removeCallbacks(morningScheduleFallbackRunnable)
        handler.removeCallbacks(postPlayListenRunnable)
        ambientPlayer?.setTypingActive(false)
        stopListeningInternal()
        dialogState = DialogState.OFF
        if (dialogActivated) {
            dialogActivated = false
            DialogStore.setDialogActive(this, false)
        }
    }

    override fun onDestroy() {
        Log.d(
            "FakeCallDbg",
            "CallActivity.onDestroy: session=$session ringing=$ringing inCall=$inCall callEnded=$callEnded"
        )
        isAlive = false
        dialing = false
        ringbackTone?.stop()
        ringbackTone = null
        stopAnimations()
        CallRuntime.unregisterHandler(this)
        teardownDialog()
        if (ringing) {
            ringing = false
            RingService.stopAll(this)
        }
        if (inCall) {
            inCall = false
            RingService.stopAll(this)
        }
        if (!callEnded) {
            finalizeCallEnd("destroyed")
        }
        handler.removeCallbacksAndMessages(null)
        audioPlayer?.release()
        audioPlayer = null
        audioRouter.exitCall()
        try { tts?.shutdown() } catch (e: Exception) { }
        tts = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { }
        wakeLock = null
        oneShotAudio?.let { path ->
            try { File(path).delete() } catch (e: Exception) { }
        }
        oneShotAudio = null
        CallStarter.cancelNotification(this)
        super.onDestroy()
    }

    // ---------- UI helpers & custom vector phone icons ----------

    private fun phoneBackdropLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(11, 16, 28), Color.rgb(22, 33, 52), Color.rgb(9, 13, 22))
        )
        setPadding(dp(20), dp(24), dp(20), dp(20))
    }

    private fun buildCallerAvatarView(): ImageView = ImageView(this).apply {
        val resId = resources.getIdentifier("ic_launcher_round", "mipmap", packageName)
        if (resId != 0) {
            setImageResource(resId)
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun circleDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun startPulseRing(view: View, delayMs: Long) {
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1700L
            startDelay = delayMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                val f = va.animatedValue as Float
                val s = 0.85f + 0.45f * f
                view.scaleX = s
                view.scaleY = s
                view.alpha = (1f - f) * 0.7f
            }
            start()
        }
        activeAnimators.add(anim)
    }

    private fun startBreathingPulse(view: View) {
        val anim = ValueAnimator.ofFloat(1f, 1.07f, 1f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                val s = va.animatedValue as Float
                view.scaleX = s
                view.scaleY = s
            }
            start()
        }
        activeAnimators.add(anim)
    }

    private fun setSpeakingGlow(speaking: Boolean) {
        val v = speakingRingView ?: return
        v.animate().alpha(if (speaking) 0.75f else 0f).setDuration(220L).start()
    }

    private fun stopAnimations() {
        for (a in activeAnimators) {
            try { a.cancel() } catch (e: Exception) { }
        }
        activeAnimators.clear()
    }

    private enum class IconKind { ANSWER, HANGUP, MIC, SPEAKER, REFRESH }

    private inner class ControlButtonHolder(
        val container: LinearLayout,
        val circle: FrameLayout,
        val icon: CallIconView,
        val defaultBg: Int,
        val defaultIconColor: Int,
        val labelView: TextView
    ) {
        fun setActive(active: Boolean) {
            circle.background = circleDrawable(if (active) Color.WHITE else defaultBg)
            icon.setIconColor(if (active) Color.rgb(18, 24, 38) else defaultIconColor)
            icon.setSlash(active && icon.kind == IconKind.MIC)
        }
    }

    private fun buildActionCircle(
        iconKind: IconKind,
        label: String,
        sizeDp: Int,
        bgColor: Int,
        iconColor: Int,
        onClick: () -> Unit
    ): ControlButtonHolder {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val iconView = CallIconView(this, iconKind, iconColor)
        val circle = FrameLayout(this).apply {
            background = circleDrawable(bgColor)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(iconView, FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER))
        }
        col.addView(circle, LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        val labelView = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.argb(220, 230, 236, 245))
            gravity = Gravity.CENTER
            visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        }
        col.addView(labelView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        return ControlButtonHolder(col, circle, iconView, bgColor, iconColor, labelView)
    }

    /** Crisp geometric vector icons drawn on Canvas (zero external drawable assets). */
    private class CallIconView(
        ctx: android.content.Context,
        val kind: IconKind,
        initialColor: Int
    ) : View(ctx) {
        private var color = initialColor
        private var slashed = false
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = initialColor
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = initialColor
        }

        fun setIconColor(c: Int) {
            color = c
            strokePaint.color = c
            fillPaint.color = c
            invalidate()
        }

        fun setSlash(on: Boolean) {
            slashed = on
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val s = minOf(w, h)
            strokePaint.strokeWidth = s * 0.085f

            when (kind) {
                IconKind.ANSWER -> drawHandset(canvas, w, h, rotateDeg = -135f)
                IconKind.HANGUP -> drawHandset(canvas, w, h, rotateDeg = 0f)
                IconKind.MIC -> drawMic(canvas, w, h, s)
                IconKind.SPEAKER -> drawSpeaker(canvas, w, h, s)
                IconKind.REFRESH -> drawRefresh(canvas, w, h, s)
            }
        }

        private fun drawHandset(canvas: Canvas, w: Float, h: Float, rotateDeg: Float) {
            canvas.save()
            canvas.rotate(rotateDeg, w / 2f, h / 2f)
            val s = minOf(w, h)
            val cx = w / 2f
            val cy = h / 2f
            val path = Path().apply {
                val r = s * 0.42f
                moveTo(cx - r, cy + s * 0.06f)
                quadTo(cx, cy - s * 0.34f, cx + r, cy + s * 0.06f)
            }
            strokePaint.strokeWidth = s * 0.16f
            canvas.drawPath(path, strokePaint)
            // Ear/mouth caps
            canvas.drawCircle(cx - s * 0.36f, cy + s * 0.12f, s * 0.11f, fillPaint)
            canvas.drawCircle(cx + s * 0.36f, cy + s * 0.12f, s * 0.11f, fillPaint)
            canvas.restore()
        }

        private fun drawMic(canvas: Canvas, w: Float, h: Float, s: Float) {
            val cx = w / 2f
            val cy = h / 2f
            val cap = RectF(cx - s * 0.16f, cy - s * 0.36f, cx + s * 0.16f, cy + s * 0.08f)
            canvas.drawRoundRect(cap, s * 0.16f, s * 0.16f, fillPaint)
            val arc = RectF(cx - s * 0.28f, cy - s * 0.18f, cx + s * 0.28f, cy + s * 0.22f)
            canvas.drawArc(arc, 0f, 180f, false, strokePaint)
            canvas.drawLine(cx, cy + s * 0.22f, cx, cy + s * 0.38f, strokePaint)
            canvas.drawLine(cx - s * 0.16f, cy + s * 0.38f, cx + s * 0.16f, cy + s * 0.38f, strokePaint)
            if (slashed) {
                canvas.drawLine(cx - s * 0.36f, cy - s * 0.36f, cx + s * 0.36f, cy + s * 0.36f, strokePaint)
            }
        }

        private fun drawSpeaker(canvas: Canvas, w: Float, h: Float, s: Float) {
            val cx = w / 2f
            val cy = h / 2f
            val body = Path().apply {
                moveTo(cx - s * 0.36f, cy - s * 0.12f)
                lineTo(cx - s * 0.16f, cy - s * 0.12f)
                lineTo(cx + s * 0.06f, cy - s * 0.32f)
                lineTo(cx + s * 0.06f, cy + s * 0.32f)
                lineTo(cx - s * 0.16f, cy + s * 0.12f)
                lineTo(cx - s * 0.36f, cy + s * 0.12f)
                close()
            }
            canvas.drawPath(body, fillPaint)
            val arc1 = RectF(cx - s * 0.04f, cy - s * 0.18f, cx + s * 0.26f, cy + s * 0.18f)
            val arc2 = RectF(cx + s * 0.04f, cy - s * 0.30f, cx + s * 0.40f, cy + s * 0.30f)
            canvas.drawArc(arc1, -45f, 90f, false, strokePaint)
            canvas.drawArc(arc2, -45f, 90f, false, strokePaint)
        }

        private fun drawRefresh(canvas: Canvas, w: Float, h: Float, s: Float) {
            val cx = w / 2f
            val cy = h / 2f
            val r = s * 0.30f
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, -40f, 285f, false, strokePaint)
            // Arrow tip at upper-right
            val tipX = cx + r * 0.80f
            val tipY = cy - r * 0.58f
            canvas.drawLine(tipX, tipY, tipX - s * 0.14f, tipY - s * 0.04f, strokePaint)
            canvas.drawLine(tipX, tipY, tipX + s * 0.03f, tipY - s * 0.16f, strokePaint)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val NO_ANSWER_MS = 60_000L
        private const val POST_PLAY_GUARD_MS = 100L
        private const val SILENCE_ENDPOINT_MS = 650L
        private const val WAITING_WATCHDOG_MS = 60_000L
        private const val IDLE_HANGUP_MS = 180_000L
        private const val BUNDLED_CLIP = "morning.wav"
        private const val REQ_RECORD_AUDIO = 2001

        @Volatile
        var isAlive: Boolean = false
            private set
    }
}
