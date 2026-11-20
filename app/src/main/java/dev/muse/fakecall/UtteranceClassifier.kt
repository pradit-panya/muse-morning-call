package dev.muse.fakecall

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * Program-driven utterance router, schedule parser, & intent classifier (<1 ms).
 *
 * Routes user speech deterministically:
 * - HANGUP_NOW: "連絡切って" / "電話を切ってください" (requires 連絡/電話/通話 + 切/終了 to avoid false triggers)
 * - THANKS_ACK: "ありがとう" / "わかった" -> prompts "もう切る？"
 * - SCHEDULE_ALARM: "7時30分に起こして" / "3時間後に連絡して" -> sets AlarmManager locally + emits SCHEDULE_UPDATED
 * - REPLAY_LAST / WAIT_PAUSE / SPEAKER_ON / SPEAKER_OFF -> instant local utility actions
 * - COMMAND_WORK / COMMAND_LOOKUP -> instant clean canned ack + dispatch to Muse in background
 * - UNSUPPORTED -> instant clean "not supported yet" canned reply
 */
object UtteranceClassifier {

    enum class UtteranceKind { EMPATHY, QUESTION_LOOKUP, ACK }

    enum class CommandRoute {
        COMMAND_WORK,
        COMMAND_LOOKUP,
        SCHEDULE_ALARM,
        HANGUP_NOW,
        THANKS_ACK,
        CONFIRM_YES,
        REPLAY_LAST,
        WAIT_PAUSE,
        SPEAKER_ON,
        SPEAKER_OFF,
        GREETING_MORNING,
        GREETING_BYE,
        ASK_HANGUP,
        ASK_MORE,
        UNSUPPORTED
    }

    data class ParsedSchedule(
        val hour: Int,
        val minute: Int,
        val triggerAtMillis: Long,
        val daily: Boolean,
        val summary: String
    )

    private const val PREFS_NAME = "fakecall_speech_profile"
    private const val KEY_ENDINGS = "ending_counts_json"
    private const val KEY_TOTAL = "total_utterances"

    private val WORK_CUES = listOf(
        "プロジェクト", "進めて", "やって", "作って", "直して", "修正", "実装",
        "ビルド", "更新", "書いて", "消して", "追加", "設定", "起動", "止めて",
        "インストール", "変更", "対応して", "お願い", "実行", "テストして", "送って"
    )

    private val LOOKUP_CUES = listOf(
        "調べて", "調べ", "確認", "見て", "教えて", "探して", "チェック",
        "進み具合", "状況", "どうなっ", "予定", "天気", "何時", "いくつ", "どこ"
    )

    private val MORNING_CUES = listOf("おはよう", "お早う", "おはよ", "起きた", "おきた")
    private val BYE_CUES = listOf("バイバイ", "じゃあね", "またね", "おやすみ")
    private val THANKS_CUES = listOf(
        "ありがとう", "ありがと", "わかった", "分かりました", "わかりました",
        "了解", "りょうかい", "それじゃあ", "それじゃ"
    )
    private val CONFIRM_YES_CUES = listOf(
        "うん", "はい", "そう", "お願い", "おねがい", "切ろう", "切って",
        "いいよ", "大丈夫", "ok", "オッケー"
    )

    private val QUESTION_CUES = listOf(
        "？", "?", "どう", "何", "なに", "どこ", "いつ", "誰", "だれ", "なぜ", "どうして",
        "教えて", "調べて", "見て", "確認", "進み具合", "どうなっ", "わかる", "知ってる",
        "できる", "ある？", "ない？", "かな？", "っけ"
    )

    private val EMPATHY_ENDINGS = listOf(
        "だね", "よね", "ですね", "ますね", "なんだ", "んだよ", "かも", "だな", "よな",
        "じゃん", "だし", "けど", "てさ", "たよ", "てる", "たい", "ほしい", "つかれた",
        "疲れた", "眠い", "ねむい", "いいな", "すごい", "やばい", "楽しい", "嬉しい"
    )

    private val ACK_WORDS = setOf(
        "うん", "はい", "ああ", "そう", "了解", "りょうかい", "オッケー", "ok",
        "ありがとう", "ありがと", "おやすみ", "バイバイ", "またね", "じゃあね"
    )

    fun isAffirmativeForHangup(rawText: String): Boolean {
        val clean = rawText.trim().lowercase().trimEnd('。', '！', '？', '!', '?')
        if (clean.isEmpty()) return false
        if (clean.contains("まだ") || clean.contains("ううん") || clean.contains("いや") ||
            clean.contains("待って") || clean.contains("切らない")
        ) {
            return false
        }
        return CONFIRM_YES_CUES.any { clean == it || clean.contains(it) }
    }

    fun parseScheduleRequest(rawText: String): ParsedSchedule? {
        val norm = normalizeDigits(rawText)
        val hasIntent = norm.contains("モーニング") || norm.contains("起こ") ||
            norm.contains("予約") || norm.contains("セット") ||
            norm.contains("連絡") || norm.contains("電話") || norm.contains("コール")
        if (!hasIntent) return null

        // Relative: "〇時間後" / "〇分後" (also "1時間半後")
        val relHourMin = Regex("(\\d+)\\s*時間\\s*(?:(\\d+)\\s*分|半)?\\s*後").find(norm)
        if (relHourMin != null) {
            val addH = relHourMin.groupValues[1].toIntOrNull() ?: return null
            val addM = when {
                relHourMin.groupValues[2].isNotEmpty() -> relHourMin.groupValues[2].toIntOrNull() ?: 0
                relHourMin.value.contains("半") -> 30
                else -> 0
            }
            val cal = Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, addH)
                add(Calendar.MINUTE, addM)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val m = cal.get(Calendar.MINUTE)
            return ParsedSchedule(h, m, cal.timeInMillis, daily = false, summary = String.format("%02d:%02d（1回）", h, m))
        }
        val relMin = Regex("(\\d+)\\s*分\\s*後").find(norm)
        if (relMin != null) {
            val addM = relMin.groupValues[1].toIntOrNull() ?: return null
            val cal = Calendar.getInstance().apply {
                add(Calendar.MINUTE, addM)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val m = cal.get(Calendar.MINUTE)
            return ParsedSchedule(h, m, cal.timeInMillis, daily = false, summary = String.format("%02d:%02d（1回）", h, m))
        }

        // Absolute: "〇時〇分" / "〇時半" / "〇時"
        val absMatch = Regex("(?:午後|夜)?\\s*(\\d{1,2})\\s*時\\s*(?:(\\d{1,2})\\s*分|半)?").find(norm)
        if (absMatch != null) {
            var h = absMatch.groupValues[1].toIntOrNull() ?: return null
            val m = when {
                absMatch.groupValues[2].isNotEmpty() -> absMatch.groupValues[2].toIntOrNull() ?: 0
                absMatch.value.contains("半") -> 30
                else -> 0
            }
            if ((absMatch.value.contains("午後") || absMatch.value.contains("夜")) && h in 1..11) {
                h += 12
            }
            if (h !in 0..23 || m !in 0..59) return null
            val daily = norm.contains("毎日")
            val triggerAt = Scheduler.nextTriggerMillis(h, m)
            return ParsedSchedule(
                h, m, triggerAt, daily,
                String.format("%02d:%02d（%s）", h, m, if (daily) "毎日" else "次回")
            )
        }
        return null
    }

    fun routeCommand(rawText: String): CommandRoute {
        val text = rawText.trim()
        if (text.isEmpty()) return CommandRoute.UNSUPPORTED
        val lower = text.lowercase()

        // Safe explicit hangup: require call/contact noun + cut/end verb so "〇〇を切る" never misfires.
        val hasCallNoun = lower.contains("連絡") || lower.contains("電話") || lower.contains("通話")
        val hasCutVerb = lower.contains("切") || lower.contains("終了") || lower.contains("終わ")
        if (hasCallNoun && hasCutVerb) {
            return CommandRoute.HANGUP_NOW
        }

        if (parseScheduleRequest(text) != null) {
            return CommandRoute.SCHEDULE_ALARM
        }

        if (lower.contains("もう一回") || lower.contains("もう1回") || lower.contains("聞き直") || lower.contains("聞こえなかっ")) {
            return CommandRoute.REPLAY_LAST
        }
        if (lower.contains("ちょっと待って") || lower.contains("少し待って") || lower.contains("待ってて") || lower.contains("メモ")) {
            return CommandRoute.WAIT_PAUSE
        }
        if (lower.contains("スピーカー")) {
            return if (lower.contains("オフ") || lower.contains("戻") || lower.contains("切")) {
                CommandRoute.SPEAKER_OFF
            } else {
                CommandRoute.SPEAKER_ON
            }
        }

        if (WORK_CUES.any { lower.contains(it) }) {
            return CommandRoute.COMMAND_WORK
        }
        if (LOOKUP_CUES.any { lower.contains(it) }) {
            return CommandRoute.COMMAND_LOOKUP
        }
        if (lower.contains("で") && (lower.endsWith("して") || lower.endsWith("して。") || lower.contains("してく"))) {
            return CommandRoute.COMMAND_WORK
        }

        if (text.length <= 18 && THANKS_CUES.any { lower.contains(it) }) {
            return CommandRoute.THANKS_ACK
        }
        if (MORNING_CUES.any { lower.contains(it) }) {
            return CommandRoute.GREETING_MORNING
        }
        if (text.length <= 14 && BYE_CUES.any { lower.contains(it) }) {
            return CommandRoute.GREETING_BYE
        }
        return CommandRoute.UNSUPPORTED
    }

    private fun normalizeDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                in '０'..'９' -> sb.append(('0'.code + (c.code - '０'.code)).toChar())
                '一' -> sb.append('1')
                '二' -> sb.append('2')
                '三' -> sb.append('3')
                '四' -> sb.append('4')
                '五' -> sb.append('5')
                '六' -> sb.append('6')
                '七' -> sb.append('7')
                '八' -> sb.append('8')
                '九' -> sb.append('9')
                '〇', '零' -> sb.append('0')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun classifyAndLearn(ctx: Context, rawText: String): UtteranceKind {
        val text = rawText.trim()
        if (text.isEmpty()) return UtteranceKind.EMPATHY
        val lower = text.lowercase()

        learnHabits(ctx, text)

        if (text.length <= 8 && ACK_WORDS.any { lower == it || lower == "$it。" }) {
            return UtteranceKind.ACK
        }
        if (QUESTION_CUES.any { lower.contains(it) }) {
            return UtteranceKind.QUESTION_LOOKUP
        }
        if (EMPATHY_ENDINGS.any { lower.endsWith(it) || lower.endsWith("$it。") }) {
            return UtteranceKind.EMPATHY
        }

        val learned = loadTopEndings(ctx)
        if (learned.any { lower.endsWith(it) || lower.endsWith("$it。") }) {
            return UtteranceKind.EMPATHY
        }
        return if (text.length >= 12) UtteranceKind.EMPATHY else UtteranceKind.QUESTION_LOOKUP
    }

    private fun learnHabits(ctx: Context, text: String) {
        try {
            val prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val total = prefs.getInt(KEY_TOTAL, 0) + 1
            val json = try {
                JSONObject(prefs.getString(KEY_ENDINGS, "{}") ?: "{}")
            } catch (_: Exception) {
                JSONObject()
            }
            val clean = text.trimEnd('。', '！', '？', '!', '?')
            if (clean.length >= 4 && !QUESTION_CUES.any { text.contains(it) }) {
                val tail2 = clean.takeLast(2)
                val tail3 = clean.takeLast(3)
                json.put(tail2, json.optInt(tail2, 0) + 1)
                json.put(tail3, json.optInt(tail3, 0) + 1)
            }
            prefs.edit()
                .putInt(KEY_TOTAL, total)
                .putString(KEY_ENDINGS, json.toString())
                .apply()
        } catch (_: Exception) { }
    }

    private fun loadTopEndings(ctx: Context): List<String> {
        return try {
            val prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = JSONObject(prefs.getString(KEY_ENDINGS, "{}") ?: "{}")
            val list = ArrayList<Pair<String, Int>>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val c = json.optInt(k, 0)
                if (c >= 3) list.add(k to c)
            }
            list.sortedByDescending { it.second }.take(12).map { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
