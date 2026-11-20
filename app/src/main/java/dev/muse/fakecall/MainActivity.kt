package dev.muse.fakecall

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings & launcher screen for muse morning call. */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var serverStatusView: TextView
    private var pendingCallAfterMic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure the bundled default voice is active.
        Store.clearCustomAudio(this)
        PcmAudioPlayer.preloadFillersAsync(this)
        if (Store.serverEnabled(this) && !CallServerService.running) {
            CallServerService.start(this)
        }

        // Launched from the pinned home-screen "museにかける" shortcut.
        if (intent?.action == ACTION_DIAL_MUSE) {
            CallStarter.startOutgoingCall(this)
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        window.statusBarColor = Color.rgb(16, 22, 36)
        window.navigationBarColor = Color.rgb(16, 22, 36)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        // ---------- Header ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(14))
        }
        val iconView = ImageView(this).apply {
            val resId = resources.getIdentifier("ic_launcher_round", "mipmap", packageName)
            if (resId != 0) setImageResource(resId)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        header.addView(iconView, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(12)
        })
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "muse morning call"
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        })
        titleCol.addView(TextView(this).apply {
            text = "v${CallServerService.APP_VERSION}"
            textSize = 12f
            setTextColor(Color.rgb(154, 168, 196))
        })
        header.addView(titleCol)
        root.addView(header)

        // ---------- Primary Call Card ----------
        val callCard = buildCard()
        val callMuseBtn = styledButton(
            label = "muse に電話をかける",
            bgColor = Color.rgb(46, 160, 88),
            textColor = Color.WHITE,
            textSizeSp = 16f
        ) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                pendingCallAfterMic = true
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO_MAIN
                )
            } else {
                launchOutgoingCall()
            }
        }
        callCard.addView(callMuseBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
        ))

        val pinShortcutBtn = styledButton(
            label = "ホーム画面に「museにかける」を追加",
            bgColor = Color.rgb(42, 52, 76),
            textColor = Color.rgb(220, 228, 244),
            textSizeSp = 13f
        ) { pinDialShortcut() }
        callCard.addView(pinShortcutBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { topMargin = dp(8) })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && !nm.canUseFullScreenIntent()) {
                val fsBtn = styledButton(
                    label = "全画面着信を許可する（設定を開く）",
                    bgColor = Color.rgb(92, 66, 36),
                    textColor = Color.rgb(255, 224, 178),
                    textSizeSp = 13f
                ) {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Exception) {
                        Toast.makeText(this, "設定画面を開けませんでした", Toast.LENGTH_SHORT).show()
                    }
                }
                callCard.addView(fsBtn, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
                ).apply { topMargin = dp(8) })
            }
        }
        root.addView(callCard, cardLayoutParams())

        // ---------- Morning Schedule Card (Scroll Wheels + 2-col Buttons) ----------
        val schedCard = buildCard()
        schedCard.addView(sectionTitle("モーニングコール着信予約"))

        val wheelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(4))
        }
        val hourPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 23
            value = Store.scheduleHour(this@MainActivity)
            setFormatter { String.format(Locale.US, "%02d", it) }
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val colonView = TextView(this).apply {
            text = ":"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(dp(12), 0, dp(12), 0)
        }
        val minutePicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 59
            value = Store.scheduleMinute(this@MainActivity)
            setFormatter { String.format(Locale.US, "%02d", it) }
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        wheelRow.addView(hourPicker, LinearLayout.LayoutParams(dp(76), dp(120)))
        wheelRow.addView(colonView)
        wheelRow.addView(minutePicker, LinearLayout.LayoutParams(dp(76), dp(120)))
        schedCard.addView(wheelRow)

        val dailyCheck = CheckBox(this).apply {
            text = "毎日繰り返す"
            textSize = 14f
            setTextColor(Color.rgb(224, 232, 246))
            isChecked = Store.scheduleDaily(this@MainActivity)
        }
        schedCard.addView(dailyCheck, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        })

        val schedBtnRow = twoColumnRow(
            left = styledButton(
                label = "予約する",
                bgColor = Color.rgb(66, 114, 214),
                textColor = Color.WHITE,
                textSizeSp = 14f
            ) {
                val h = hourPicker.value
                val m = minutePicker.value
                Store.saveSchedule(this@MainActivity, h, m, dailyCheck.isChecked)
                val at = Scheduler.nextTriggerMillis(h, m)
                Scheduler.scheduleLocal(this@MainActivity, at)
                if (!Scheduler.canExact(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "正確なアラーム権限が未許可のため、時刻がずれる場合があります",
                        Toast.LENGTH_LONG
                    ).show()
                    openExactAlarmSettings()
                }
                refreshStatus()
            },
            right = styledButton(
                label = "予約を取り消す",
                bgColor = Color.rgb(52, 60, 82),
                textColor = Color.rgb(220, 228, 244),
                textSizeSp = 14f
            ) {
                Scheduler.cancelLocal(this@MainActivity)
                Store.clearSchedule(this@MainActivity)
                refreshStatus()
            }
        )
        schedCard.addView(schedBtnRow)

        statusView = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.rgb(168, 182, 210))
        }
        schedCard.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        root.addView(schedCard, cardLayoutParams())

        // ---------- Tailscale Receive-Server Card (2-col Start/Stop + Token) ----------
        val serverCard = buildCard()
        serverCard.addView(sectionTitle("muse からの着信（Tailscale）"))

        serverStatusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(185, 200, 228))
        }
        serverCard.addView(serverStatusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        val serverBtnRow = twoColumnRow(
            left = styledButton(
                label = "受け口を開始",
                bgColor = Color.rgb(46, 134, 193),
                textColor = Color.WHITE,
                textSizeSp = 14f
            ) {
                Store.setServerEnabled(this@MainActivity, true)
                CallServerService.start(this@MainActivity)
                refreshServerStatus()
            },
            right = styledButton(
                label = "受け口を停止",
                bgColor = Color.rgb(52, 60, 82),
                textColor = Color.rgb(220, 228, 244),
                textSizeSp = 14f
            ) {
                Store.setServerEnabled(this@MainActivity, false)
                CallServerService.stop(this@MainActivity)
                refreshServerStatus()
            }
        )
        serverCard.addView(serverBtnRow)

        val tokenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(20, 26, 42))
            }
        }
        val tokenText = TextView(this).apply {
            text = Store.serverToken(this@MainActivity)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(176, 192, 220))
            setTextIsSelectable(true)
        }
        tokenRow.addView(tokenText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })
        val copyTokenBtn = styledButton(
            label = "コピー",
            bgColor = Color.rgb(58, 68, 96),
            textColor = Color.WHITE,
            textSizeSp = 12f
        ) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(
                ClipData.newPlainText("muse morning call token", Store.serverToken(this@MainActivity))
            )
            Toast.makeText(this@MainActivity, "トークンをコピーしました", Toast.LENGTH_SHORT).show()
        }
        tokenRow.addView(copyTokenBtn, LinearLayout.LayoutParams(dp(72), dp(34)))
        serverCard.addView(tokenRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        root.addView(serverCard, cardLayoutParams())

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(16, 22, 36))
            addView(root, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(scroll)
        refreshAll()
    }

    private fun launchOutgoingCall() {
        if (!Store.serverEnabled(this)) {
            Store.setServerEnabled(this, true)
            CallServerService.start(this)
        }
        CallStarter.startOutgoingCall(this)
    }

    private fun buildCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.rgb(26, 34, 54))
        }
    }

    private fun cardLayoutParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }

    private fun sectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.rgb(228, 236, 250))
        setPadding(0, 0, 0, dp(8))
    }

    private fun styledButton(
        label: String,
        bgColor: Int,
        textColor: Int,
        textSizeSp: Float,
        onClick: () -> Unit
    ): Button = Button(this).apply {
        text = label
        textSize = textSizeSp
        isAllCaps = false
        setTextColor(textColor)
        setPadding(dp(10), 0, dp(10), 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(bgColor)
        }
        setOnClickListener { onClick() }
    }

    private fun twoColumnRow(left: View, right: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        addView(right, LinearLayout.LayoutParams(0, dp(44), 1f))
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        if (::statusView.isInitialized) refreshStatus()
        if (::serverStatusView.isInitialized) refreshServerStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO_MAIN && pendingCallAfterMic) {
            pendingCallAfterMic = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchOutgoingCall()
            } else {
                Toast.makeText(this, "対話にはマイク権限が必要です", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshServerStatus() {
        val state = when {
            CallServerService.running -> "待機中"
            Store.serverEnabled(this) -> "停止中（開始処理待ち／失敗）"
            else -> "停止中"
        }
        serverStatusView.text =
            "受け口: $state（ポート ${CallServerService.PORT} / Tailscale のみ）"
    }

    private fun refreshStatus() {
        val sb = StringBuilder()
        if (Store.scheduleOn(this)) {
            val at = Scheduler.nextTriggerMillis(
                Store.scheduleHour(this), Store.scheduleMinute(this)
            )
            val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)
            sb.append("予約: 次回 ").append(fmt.format(Date(at)))
            sb.append(if (Store.scheduleDaily(this)) "（毎日）" else "（一回）")
        } else {
            sb.append("予約: なし")
        }
        sb.append("  |  正確なアラーム: ")
            .append(if (Scheduler.canExact(this)) "許可" else "未許可")
        statusView.text = sb.toString()
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) { }
        }
    }

    private fun pinDialShortcut() {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, "このランチャーはショートカット追加に未対応です", Toast.LENGTH_SHORT).show()
            return
        }
        val dialIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_DIAL_MUSE
        }
        val resId = resources.getIdentifier("ic_launcher_round", "mipmap", packageName)
        val shortcutIcon = if (resId != 0) {
            Icon.createWithResource(this, resId)
        } else {
            Icon.createWithResource(this, android.R.drawable.sym_action_call)
        }
        val info = ShortcutInfo.Builder(this, "dial_muse")
            .setShortLabel("museにかける")
            .setLongLabel("museに電話をかける")
            .setIcon(shortcutIcon)
            .setIntent(dialIntent)
            .build()
        sm.requestPinShortcut(info, null)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_DIAL_MUSE = "dev.muse.fakecall.ACTION_DIAL_MUSE"
        private const val REQ_RECORD_AUDIO_MAIN = 2002
    }
}
