package jp.signage.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 画面に流すテロップ（お客様の呼び出し・閉店のご案内など）。
 * 管理画面から送られたものを順番に流し、予約（毎日決まった時刻）も端末自身で実行する。
 */
object Ticker {
    const val STYLE_NORMAL = 0
    const val STYLE_INFO = 1
    const val STYLE_CALL = 2

    data class Message(
        val text: String,
        val style: Int = STYLE_NORMAL,
        /** 流す回数。0 は止めるまで流し続ける */
        val repeat: Int = 2,
        /** 0: 画面の下, 1: 画面の上 */
        val position: Int = 0,
        /** 0: 小, 1: 中, 2: 大 */
        val size: Int = 1,
        /** 0: ゆっくり, 1: ふつう, 2: はやい */
        val speed: Int = 1,
        val chime: Boolean = false,
        val speak: Boolean = false,
        val id: String = UUID.randomUUID().toString(),
    ) {
        val isStanding get() = repeat == 0

        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("text", text).put("style", style).put("repeat", repeat)
            .put("position", position).put("size", size).put("speed", speed)
            .put("chime", chime).put("speak", speak)

        companion object {
            fun fromJson(j: JSONObject) = Message(
                text = j.optString("text").trim().take(500),
                style = j.optInt("style", STYLE_NORMAL).coerceIn(0, 2),
                repeat = j.optInt("repeat", 2).coerceIn(0, 20),
                position = j.optInt("position", 0).coerceIn(0, 1),
                size = j.optInt("size", 1).coerceIn(0, 2),
                speed = j.optInt("speed", 1).coerceIn(0, 2),
                chime = j.optBoolean("chime", false),
                speak = j.optBoolean("speak", false),
                id = j.optString("id").ifEmpty { UUID.randomUUID().toString() },
            )
        }
    }

    /** 予約：time は "HH:mm"、days は月曜=bit0 … 日曜=bit6 */
    data class Schedule(val id: String, val time: String, val days: Int, val enabled: Boolean, val message: Message) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("time", time).put("days", days).put("enabled", enabled).put("message", message.toJson())

        companion object {
            private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

            fun fromJson(j: JSONObject): Schedule? {
                val time = j.optString("time")
                if (!TIME.matches(time)) return null
                val message = Message.fromJson(j.optJSONObject("message") ?: return null)
                if (message.text.isEmpty()) return null
                return Schedule(
                    id = j.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    time = time,
                    days = j.optInt("days", 0x7F) and 0x7F,
                    enabled = j.optBoolean("enabled", true),
                    // 予約は決まった回数だけ流す（ずっと流すと止める人がいないため）
                    message = message.copy(repeat = message.repeat.coerceAtLeast(1)),
                )
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val queue = ArrayDeque<Message>()
    /** 予約を同じ分に二重に流さないための記録（予約ID → 実行した日時） */
    private val fired = mutableMapOf<String, String>()

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun changed() = main.post { listeners.forEach { it() } }

    /** テロップを流す。止めるまで流すものは端末に保存し、再起動後も流す */
    @Synchronized
    fun post(context: Context, message: Message) {
        if (message.text.isEmpty()) return
        if (message.isStanding) saveStanding(context, message) else queue.addLast(message)
        changed()
    }

    /** 流しているテロップと、止めるまで流すテロップをすべて止める */
    @Synchronized
    fun stop(context: Context) {
        queue.clear()
        saveStanding(context, null)
        stopRequested = true
        changed()
    }

    /** 再生画面が読む：止める指示が出たか（読むと解除） */
    @Volatile
    var stopRequested = false
        private set

    @Synchronized
    fun consumeStop(): Boolean = stopRequested.also { stopRequested = false }

    /** 次に流す回数指定のテロップ */
    @Synchronized
    fun nextQueued(): Message? = queue.removeFirstOrNull()

    fun standing(context: Context): Message? = prefs(context).getString("tickerStanding", null)
        ?.let { runCatching { Message.fromJson(JSONObject(it)) }.getOrNull() }

    private fun saveStanding(context: Context, m: Message?) {
        prefs(context).edit().putString("tickerStanding", m?.toJson()?.toString()).apply()
    }

    fun schedules(context: Context): List<Schedule> = runCatching {
        val a = JSONArray(prefs(context).getString("tickerSchedules", "[]"))
        (0 until a.length()).mapNotNull { Schedule.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun setSchedules(context: Context, list: List<Schedule>) {
        val a = JSONArray()
        list.sortedBy { it.time }.forEach { a.put(it.toJson()) }
        prefs(context).edit().putString("tickerSchedules", a.toString()).apply()
    }

    /** 1分ごとに呼ばれ、時刻になった予約を流す */
    @Synchronized
    fun checkSchedules(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        val hm = "%02d:%02d".format(now.hour, now.minute)
        val bit = 1 shl (now.dayOfWeek.value - 1)
        val stamp = "${now.toLocalDate()} $hm"
        for (s in schedules(context)) {
            if (!s.enabled || s.time != hm || (s.days and bit) == 0 || fired[s.id] == stamp) continue
            fired[s.id] = stamp
            queue.addLast(s.message.copy(id = UUID.randomUUID().toString()))
            changed()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("signage", Context.MODE_PRIVATE)
}
