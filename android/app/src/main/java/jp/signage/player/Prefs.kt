package jp.signage.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import org.json.JSONObject
import java.io.File

/** 設定値の保存（SharedPreferences） */
class Prefs(context: Context) {
    private val appContext: Context = context.applicationContext ?: context
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

    /** 画像・動画の表示方法（FitMode.*）。初期値は余白をなるべく残さない「おまかせ」 */
    var fitMode: Int
        get() = sp.getInt("fitMode", FitMode.AUTO)
        set(v) = sp.edit().putInt("fitMode", v.coerceIn(0, 3)).apply()

    /** 動画の互換モード（動画が途中で止まる機種向けに SurfaceView で描画する） */
    /** 複数の区画で動画を同時に再生するとき、2 つ目以降の区画はソフトウェアデコード・音声なしで再生する（ハードウェアデコーダーの取り合いで止まるのを防ぐ） */
    var videoMultiSoft: Boolean
        get() = sp.getBoolean("videoMultiSoft", true)
        set(v) = sp.edit().putBoolean("videoMultiSoft", v).apply()

    var videoCompat: Boolean
        get() = sp.getBoolean("videoCompat", false)
        set(v) = sp.edit().putBoolean("videoCompat", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", false)
        set(v) = sp.edit().putBoolean("autoStart", v).apply()

    /** 画面を回して表示する（テレビ専用：Fire TV・Google TV）。0=回さない / 1=右（時計回り）に 90 度 / 2=左（反時計回り）に 90 度 */
    var screenRotate: Int
        get() = sp.getInt("screenRotate", 0)
        set(v) = sp.edit().putInt("screenRotate", v.coerceIn(0, 2)).apply()

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

    /** 管理画面から設定が変わるたびに増える番号（端末の設定画面が表示を更新するため） */
    val settingsVersion: Int get() = sp.getInt("settingsVersion", 0)

    fun bumpSettingsVersion() = sp.edit().putInt("settingsVersion", settingsVersion + 1).apply()

    var adminEnabled: Boolean
        get() = sp.getBoolean("adminEnabled", false)
        set(v) = sp.edit().putBoolean("adminEnabled", v).apply()

    /** 管理画面から送られた APK でのアプリ更新を許可するか（初期状態は許可しない） */
    var allowRemoteUpdate: Boolean
        get() = sp.getBoolean("allowRemoteUpdate", false)
        set(v) = sp.edit().putBoolean("allowRemoteUpdate", v).apply()

    /** グループ（組織）コード。空ならグループなし */
    var groupCode: String
        get() = sp.getString("groupCode", "") ?: ""
        set(v) = sp.edit().putString("groupCode", v).apply()

    /** 管理画面の通信を HTTPS（自己署名の証明書）にするか。変えたあと、サーバーを起動し直す */
    var https: Boolean
        get() = sp.getBoolean("https", false)
        set(v) = sp.edit().putBoolean("https", v).apply()

    /** 操作できる端末を MAC アドレスで制限するか（初期状態は制限なし） */
    var macLock: Boolean
        get() = sp.getBoolean("macLock", false)
        set(v) = sp.edit().putBoolean("macLock", v).apply()

    /** 制限中でも VPN（Tailscale）経由は許可するか */
    var allowVpn: Boolean
        get() = sp.getBoolean("allowVpn", true)
        set(v) = sp.edit().putBoolean("allowVpn", v).apply()

    var allowedMacs: List<MacAccess.Device>
        get() = MacAccess.fromJson(sp.getString("allowedMacs", null))
        set(v) = sp.edit().putString("allowedMacs", MacAccess.toJson(v).toString()).apply()

    /** 制限を解除して、登録した端末の一覧も消す（誰も操作できなくなったとき用） */
    fun resetAccess() = sp.edit().putBoolean("macLock", false).remove("allowedMacs").apply()

    /** 管理画面の合言葉（6桁）。未設定なら作る */
    val adminPin: String
        get() = sp.getString("adminPin", null) ?: resetAdminPin()

    fun resetAdminPin(): String {
        val pin = "%06d".format(java.security.SecureRandom().nextInt(1_000_000))
        sp.edit().putString("adminPin", pin).apply()
        return pin
    }

    /** 6桁の数字なら PIN として設定する（複数台で同じ PIN にそろえるため） */
    fun setAdminPin(pin: String): Boolean {
        if (!Regex("\\d{6}").matches(pin)) return false
        sp.edit().putString("adminPin", pin).apply()
        return true
    }

    /** 端末を見分けるための ID（最初に作ったものをずっと使う） */
    val deviceId: String
        get() = sp.getString("deviceId", null)
            ?: java.util.UUID.randomUUID().toString().also { sp.edit().putString("deviceId", it).apply() }

    /** 管理画面の一覧に出す端末名（例: 入口, レジ横） */
    var deviceName: String
        get() = sp.getString("deviceName", null)?.takeIf { it.isNotBlank() }
            ?: "${android.os.Build.MODEL}-${deviceId.take(4)}"
        set(v) = sp.edit().putString("deviceName", v.trim().take(40)).apply()

    // ---- 画面分割

    /** 画面の分割方法 (LAYOUT_*) */
    var layout: Int
        get() = sp.getInt("layout", LAYOUT_SINGLE)
        set(v) = sp.edit().putInt("layout", v).apply()

    /** 2分割のときの区画1の割合（%）。残りが区画2 */
    var splitPercent: Int
        get() = sp.getInt("splitPercent", 50)
        set(v) = sp.edit().putInt("splitPercent", v.coerceIn(10, 90)).apply()

    /** メイン＋サイドのときのメイン区画の割合（%）。残りがサイド */
    var mainPercent: Int
        get() = sp.getInt("mainPercent", 70)
        set(v) = sp.edit().putInt("mainPercent", v.coerceIn(20, 90)).apply()

    /** メイン＋サイドのときのサイド1（区画2）の割合（%）。残りがサイド2（区画3） */
    var sidePercent: Int
        get() = sp.getInt("sidePercent", 50)
        set(v) = sp.edit().putInt("sidePercent", v.coerceIn(10, 90)).apply()

    /** 区画 i の表示内容 (ZONE_*)。区画0 はメイン */
    fun zoneType(i: Int): Int {
        val default = if (i == 2) ZONE_WEATHER else ZONE_FOLDER
        // 以前の版の「時計」など、今は無い種類はその区画の既定値に戻す
        return sp.getInt("zoneType$i", default).takeIf { it in ZONE_FOLDER..ZONE_RSS } ?: default
    }

    fun setZoneType(i: Int, type: Int) = sp.edit().putInt("zoneType$i", type).apply()

    /** Web ページ・RSS の区画の URL（http / https のみ） */
    fun zoneUrl(i: Int): String = sp.getString("zoneUrl$i", "") ?: ""

    fun setZoneUrl(i: Int, url: String) = sp.edit().putString("zoneUrl$i", url).apply()

    /** Web ページ・RSS の区画を読み直す間隔（分） */
    fun zoneRefreshMin(i: Int): Int = sp.getInt("zoneRefreshMin$i", 10).coerceIn(1, 1440)

    fun setZoneRefreshMin(i: Int, min: Int) = sp.edit().putInt("zoneRefreshMin$i", min.coerceIn(1, 1440)).apply()

    /** 区画 i のフォルダ。区画0 は従来の「再生フォルダ」 */
    fun zoneFolder(i: Int): Uri? =
        (if (i == 0) folderUri else sp.getString("zoneFolder$i", null)?.let(Uri::parse)) ?: appFolder(i)

    /**
     * まだフォルダを選んでいない区画の初期値は、アプリ専用のフォルダ（Android/data/…/files/zoneN）。
     * 権限なしで読み書きでき、管理画面からすぐ画像・動画を追加できる（アプリを削除すると中身も消える）
     */
    fun appFolder(i: Int): Uri? = runCatching {
        val dir = appContext.getExternalFilesDir("zone${i + 1}") ?: File(appContext.filesDir, "zone${i + 1}")
        dir.mkdirs()
        Uri.fromFile(dir)
    }.getOrNull()

    /** 画像・動画ごとの表示の回転（キーは「区画|ファイル名」、値は 90・180・270）。回転なしは 0 */
    private fun fileRotations(): JSONObject = runCatching { JSONObject(sp.getString("fileRotations", "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun fileRotation(zone: Int, name: String): Int = fileRotations().optInt("$zone|$name", 0)

    fun fileRotationsOf(zone: Int): Map<String, Int> {
        val all = fileRotations()
        val prefix = "$zone|"
        return all.keys().asSequence().filter { it.startsWith(prefix) }.associate { it.removePrefix(prefix) to all.optInt(it, 0) }
    }

    fun setFileRotation(zone: Int, name: String, degrees: Int) {
        val all = fileRotations()
        if (degrees == 90 || degrees == 180 || degrees == 270) all.put("$zone|$name", degrees) else all.remove("$zone|$name")
        sp.edit().putString("fileRotations", all.toString()).apply()
    }

    /** 画像・動画ごとの再生条件（キーは「区画|ファイル名」）。条件が無いファイルは常に再生 */
    private fun fileRules(): JSONObject = runCatching { JSONObject(sp.getString("fileRules", "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun fileRule(zone: Int, name: String): JSONObject? = fileRules().optJSONObject("$zone|$name")

    fun setFileRule(zone: Int, name: String, rule: JSONObject?) {
        val all = fileRules()
        if (rule == null) all.remove("$zone|$name") else all.put("$zone|$name", rule)
        sp.edit().putString("fileRules", all.toString()).apply()
    }

    /** 全ファイルの条件を一度に取り出す（一覧の表示用） */
    fun fileRulesOf(zone: Int): Map<String, JSONObject> {
        val all = fileRules()
        val prefix = "$zone|"
        return all.keys().asSequence().filter { it.startsWith(prefix) }
            .mapNotNull { k -> all.optJSONObject(k)?.let { k.removePrefix(prefix) to it } }.toMap()
    }

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
        const val ZONE_WEB = 2
        const val ZONE_RSS = 3

        const val CLOCK_TOP_RIGHT = 0
        const val CLOCK_BOTTOM_RIGHT = 1
        const val CLOCK_TOP_LEFT = 2
        const val CLOCK_BOTTOM_LEFT = 3
    }
}
