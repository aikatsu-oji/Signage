package jp.simplesignage

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

/**
 * アプリの時刻。端末のシステムの設定（タイムゾーン・時刻の表示形式・時計のずれ）とは別に、アプリの設定で決める。
 * 時計の表示、ファイルの再生条件（時間帯・曜日・期間）、予約テロップの時刻に使う（天気予報の時刻は、予報の発表に合わせて日本時間）。
 */
object AppTime {
    /** 設定のタイムゾーン。空・不正なら端末の設定 */
    fun zone(prefs: Prefs): ZoneId =
        prefs.timeZone.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    /** アプリの時刻に足す、ずれ（ミリ秒）：時刻サーバーとの差（同期が ON のとき）＋ 手動の補正 */
    fun offsetMs(prefs: Prefs): Long = (if (prefs.timeSync) prefs.timeSyncOffsetMs else 0L) + prefs.timeOffsetSec * 1000L

    /** いまの時刻（ミリ秒。端末の時計に、ずれを足した値） */
    fun nowMillis(prefs: Prefs): Long = System.currentTimeMillis() + offsetMs(prefs)

    /** その土地の壁時計の時刻 */
    fun localDateTime(prefs: Prefs): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis(prefs)), zone(prefs))

    /** 再生条件の判定に使う Calendar（タイムゾーンも、設定のもの） */
    fun calendar(prefs: Prefs): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(zone(prefs))).apply { timeInMillis = nowMillis(prefs) }

    /** 時刻の表示。format：0=24 時間（15:30） / 1=12 時間（午後3:30） */
    fun hm(t: LocalDateTime, format: Int): String =
        if (format == 1) "${if (t.hour < 12) "午前" else "午後"}${if (t.hour % 12 == 0) 12 else t.hour % 12}:%02d".format(t.minute)
        else "${t.hour}:%02d".format(t.minute)
}
