package jp.signage.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri

/** 設定値の保存（SharedPreferences） */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("signage", Context.MODE_PRIVATE)

    var folderUri: Uri?
        get() = sp.getString("folder", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("folder", v?.toString()).apply()

    var imageSeconds: Int
        get() = sp.getInt("imageSeconds", 10)
        set(v) = sp.edit().putInt("imageSeconds", v.coerceIn(1, 3600)).apply()

    var shuffle: Boolean
        get() = sp.getBoolean("shuffle", false)
        set(v) = sp.edit().putBoolean("shuffle", v).apply()

    var recursive: Boolean
        get() = sp.getBoolean("recursive", true)
        set(v) = sp.edit().putBoolean("recursive", v).apply()

    var videoSound: Boolean
        get() = sp.getBoolean("videoSound", true)
        set(v) = sp.edit().putBoolean("videoSound", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", false)
        set(v) = sp.edit().putBoolean("autoStart", v).apply()

    /** ActivityInfo.SCREEN_ORIENTATION_* の値 */
    var orientation: Int
        get() = sp.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        set(v) = sp.edit().putInt("orientation", v).apply()

    val fadeMillis: Long get() = 800

    // ---- 天気予報

    var weatherEnabled: Boolean
        get() = sp.getBoolean("weatherEnabled", false)
        set(v) = sp.edit().putBoolean("weatherEnabled", v).apply()

    /** 気象庁の府県予報区コード (例: 130000 = 東京都) */
    var weatherOffice: String?
        get() = sp.getString("weatherOffice", null)
        set(v) = sp.edit().putString("weatherOffice", v).apply()

    /** 地域コード (例: 130010 = 東京地方) */
    var weatherArea: String?
        get() = sp.getString("weatherArea", null)
        set(v) = sp.edit().putString("weatherArea", v).apply()

    /** 地域名 (例: 東京地方) */
    var weatherAreaName: String?
        get() = sp.getString("weatherAreaName", null)
        set(v) = sp.edit().putString("weatherAreaName", v).apply()

    /** 表示に使う市区町村コード (例: 1310100 = 千代田区)。予報そのものは weatherArea の地域単位 */
    var weatherCity: String?
        get() = sp.getString("weatherCity", null)
        set(v) = sp.edit().putString("weatherCity", v).apply()

    var weatherCityName: String?
        get() = sp.getString("weatherCityName", null)
        set(v) = sp.edit().putString("weatherCityName", v).apply()

    /** 天気予報を差し込む間隔（分） */
    var weatherIntervalMin: Int
        get() = sp.getInt("weatherIntervalMin", 10)
        set(v) = sp.edit().putInt("weatherIntervalMin", v.coerceIn(1, 1440)).apply()

    /** 天気予報の表示秒数（1画面あたり） */
    var weatherSeconds: Int
        get() = sp.getInt("weatherSeconds", 15)
        set(v) = sp.edit().putInt("weatherSeconds", v.coerceIn(3, 600)).apply()

    /** 日ごとの予報の後に、3時間ごとの予報も表示する */
    var weatherTimeSeries: Boolean
        get() = sp.getBoolean("weatherTimeSeries", true)
        set(v) = sp.edit().putBoolean("weatherTimeSeries", v).apply()

    // ---- 時計

    /** 再生中に常に時計を表示する */
    var clockEnabled: Boolean
        get() = sp.getBoolean("clockEnabled", false)
        set(v) = sp.edit().putBoolean("clockEnabled", v).apply()

    /** 時計の位置 (CLOCK_*) */
    var clockPosition: Int
        get() = sp.getInt("clockPosition", CLOCK_TOP_RIGHT)
        set(v) = sp.edit().putInt("clockPosition", v).apply()

    /** 時計の大きさ (0: 小, 1: 中, 2: 大) */
    var clockSize: Int
        get() = sp.getInt("clockSize", 1)
        set(v) = sp.edit().putInt("clockSize", v.coerceIn(0, 2)).apply()

    // ---- 管理画面（同じネットワークの別端末から操作）

    var adminEnabled: Boolean
        get() = sp.getBoolean("adminEnabled", false)
        set(v) = sp.edit().putBoolean("adminEnabled", v).apply()

    /** 管理画面の合言葉（6桁）。未設定なら作る */
    val adminPin: String
        get() = sp.getString("adminPin", null) ?: resetAdminPin()

    fun resetAdminPin(): String {
        val pin = "%06d".format(java.security.SecureRandom().nextInt(1_000_000))
        sp.edit().putString("adminPin", pin).apply()
        return pin
    }

    // ---- 画面分割

    /** 画面の分割方法 (LAYOUT_*) */
    var layout: Int
        get() = sp.getInt("layout", LAYOUT_SINGLE)
        set(v) = sp.edit().putInt("layout", v).apply()

    /** 2分割のときの区画1の割合（%）。残りが区画2 */
    var splitPercent: Int
        get() = sp.getInt("splitPercent", 50)
        set(v) = sp.edit().putInt("splitPercent", v.coerceIn(10, 90)).apply()

    /** 区画 i の表示内容 (ZONE_*)。区画0 はメイン */
    fun zoneType(i: Int): Int {
        val default = if (i == 2) ZONE_WEATHER else ZONE_FOLDER
        // 以前の版の「時計」など、今は無い種類はその区画の既定値に戻す
        return sp.getInt("zoneType$i", default).takeIf { it == ZONE_FOLDER || it == ZONE_WEATHER } ?: default
    }

    fun setZoneType(i: Int, type: Int) = sp.edit().putInt("zoneType$i", type).apply()

    /** 区画 i のフォルダ。区画0 は従来の「再生フォルダ」 */
    fun zoneFolder(i: Int): Uri? =
        if (i == 0) folderUri else sp.getString("zoneFolder$i", null)?.let(Uri::parse)

    fun setZoneFolder(i: Int, uri: Uri?) {
        if (i == 0) folderUri = uri else sp.edit().putString("zoneFolder$i", uri?.toString()).apply()
    }

    /** 使用中の全区画のフォルダ（フォルダへのアクセス権を残すため） */
    fun allZoneFolders(): Set<Uri> = (0 until MAX_ZONES).mapNotNull(::zoneFolder).toSet()

    companion object {
        const val LAYOUT_SINGLE = 0
        /** 左右に2分割 */
        const val LAYOUT_LEFT_RIGHT = 1
        /** 上下に2分割 */
        const val LAYOUT_TOP_BOTTOM = 2
        /** 大きなメイン区画＋小さなサイド区画2つ */
        const val LAYOUT_MAIN_SIDE = 3
        const val MAX_ZONES = 3

        /** 区画の呼び名（設定画面・管理画面の表示用） */
        fun zoneNames(layout: Int): List<String> = when (layout) {
            LAYOUT_LEFT_RIGHT -> listOf("左", "右")
            LAYOUT_TOP_BOTTOM -> listOf("上", "下")
            LAYOUT_MAIN_SIDE -> listOf("メイン", "サイド1：横長画面では右上、縦長画面では左下", "サイド2：右下")
            else -> listOf("全画面")
        }

        fun zoneCount(layout: Int) = when (layout) {
            LAYOUT_LEFT_RIGHT, LAYOUT_TOP_BOTTOM -> 2
            LAYOUT_MAIN_SIDE -> 3
            else -> 1
        }

        const val ZONE_FOLDER = 0
        const val ZONE_WEATHER = 1

        const val CLOCK_TOP_RIGHT = 0
        const val CLOCK_BOTTOM_RIGHT = 1
        const val CLOCK_TOP_LEFT = 2
        const val CLOCK_BOTTOM_LEFT = 3
    }
}
