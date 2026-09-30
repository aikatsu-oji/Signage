package jp.signage.player

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 画像・動画ごとの「再生する条件」。設定した項目のすべてに合うときだけ再生する（未設定の項目は常に合う）。
 *  - days：曜日（0=日〜6=土）。空なら毎日
 *  - start / end："HH:MM"。start > end のときは日をまたぐ（例 22:00〜02:00）
 *  - from / to："YYYY-MM-DD"（両端を含む）
 */
object FileRule {
    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val DATE = Regex("^\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$")

    /** 不正な値を取り除いて保存用に整える。条件が何も無ければ null */
    fun normalize(raw: JSONObject?): JSONObject? {
        if (raw == null) return null
        val out = JSONObject()
        val days = raw.optJSONArray("days")
        if (days != null) {
            val set = (0 until days.length()).map { days.optInt(it, -1) }.filter { it in 0..6 }.toSortedSet()
            if (set.isNotEmpty() && set.size < 7) out.put("days", JSONArray(set.toList()))
        }
        for (k in listOf("start", "end")) {
            val v = raw.optString(k)
            if (v.isNotEmpty()) {
                if (!TIME.matches(v)) throw IllegalArgumentException("時刻は HH:MM の形式で指定してください")
                out.put(k, v)
            }
        }
        for (k in listOf("from", "to")) {
            val v = raw.optString(k)
            if (v.isNotEmpty()) {
                if (!DATE.matches(v)) throw IllegalArgumentException("日付は YYYY-MM-DD の形式で指定してください")
                out.put(k, v)
            }
        }
        val from = out.optString("from")
        val to = out.optString("to")
        if (from.isNotEmpty() && to.isNotEmpty() && from > to) throw IllegalArgumentException("期間の終わりは開始より後にしてください")
        if (out.optString("start") == out.optString("end") && out.has("start")) throw IllegalArgumentException("開始と終了の時刻が同じです")
        return if (out.length() == 0) null else out
    }

    fun isActive(rule: JSONObject?, now: Calendar = Calendar.getInstance()): Boolean {
        if (rule == null) return true
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = minutesOf(rule.optString("start"))
        val end = minutesOf(rule.optString("end"))
        // 日をまたぐ時間帯の 0 時以降は、前日の分として扱う
        val overnight = start != null && end != null && start > end && minutes < end
        val day = Calendar.getInstance().apply { timeInMillis = now.timeInMillis; if (overnight) add(Calendar.DAY_OF_MONTH, -1) }

        val days = rule.optJSONArray("days")
        if (days != null && days.length() > 0) {
            val dow = day.get(Calendar.DAY_OF_WEEK) - 1
            if ((0 until days.length()).none { days.optInt(it, -1) == dow }) return false
        }
        val date = "%04d-%02d-%02d".format(day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH))
        val from = rule.optString("from")
        val to = rule.optString("to")
        if (from.isNotEmpty() && date < from) return false
        if (to.isNotEmpty() && date > to) return false

        return when {
            start != null && end != null -> if (start <= end) minutes in start until end else (minutes >= start || minutes < end)
            start != null -> minutes >= start
            end != null -> minutes < end
            else -> true
        }
    }

    private fun minutesOf(s: String): Int? =
        if (TIME.matches(s)) s.substring(0, 2).toInt() * 60 + s.substring(3).toInt() else null
}
