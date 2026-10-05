package jp.signage.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** 1日分の予報 */
data class DayForecast(
    val date: LocalDate,
    val code: String,
    /** 天気の文章（今日・明日のみ） */
    val text: String? = null,
    val max: String? = null,
    val min: String? = null,
    /** 6時間ごとの降水確率 (0-6, 6-12, 12-18, 18-24時)。無い時間帯は null */
    val pops6h: List<String?>? = null,
    /** 日単位の降水確率（週間予報） */
    val pop: String? = null,
)

data class WeatherData(
    val areaName: String,
    val reportTime: LocalDateTime,
    val days: List<DayForecast>,
    val week: List<DayForecast>,
    /** 通信できず前回取得したデータを使っている */
    val stale: Boolean,
)

/** 3時間ごとの予報の1コマ（time から3時間） */
data class TimeSlot(
    val time: LocalDateTime,
    val weather: String,
    val temp: String?,
    val windDir: String?,
    /** 風速の範囲 (例: 3〜5) m/s */
    val windRange: String?,
)

data class TimeSeriesData(
    /** 気温の観測地点名 (例: 東京) */
    val pointName: String,
    val reportTime: LocalDateTime,
    val slots: List<TimeSlot>,
    val stale: Boolean,
)

/** 地方ごとの一覧の 1 か所（府県のタイル）。lon・lat は実際の位置。pos があれば、地図の外の決まった位置（％）に置く */
data class RegionTile(val label: String, val office: String, val hint: String?, val lon: Float, val lat: Float, val pos: Pair<Float, Float>?)

/** view は地図の表示範囲（経度の最小・最大, 緯度の最小・最大）、stretch は横の引き伸ばし */
data class Region(val id: String, val name: String, val tiles: List<RegionTile>, val view: FloatArray, val stretch: Float)

/** 地図の背景（都道府県の境界）。経緯度を scale 倍した整数の、外周の点列（x, y, x, y, ...） */
class MapData(val scale: Int, val prefs: Map<String, List<IntArray>>)

/** 一覧のタイル 1 つ分の予報（day が無いときは取得できなかった） */
data class TileForecast(val tile: RegionTile, val day: DayForecast?)

/** 地方ごとの一覧の 1 画面分 */
data class RegionPageData(
    val name: String,
    /** 0=きょう 1=あした */
    val dayIndex: Int,
    val date: LocalDate,
    val tiles: List<TileForecast>,
    val reportTime: LocalDateTime,
    val stale: Boolean,
)

/** 市区町村と、その予報を出している地域 (例: 千代田区 → 東京地方) */
data class City(val code: String, val name: String, val areaCode: String, val areaName: String)

data class Office(
    val code: String,
    val name: String,
    /** 地域 (コード, 名前) */
    val areas: List<Pair<String, String>>,
    val cities: List<City>,
)

/**
 * 気象庁の予報データ (https://www.jma.go.jp/bosai/forecast/) の取得と解析。
 * 30分以内に取得したデータがあればそれを使い、通信に失敗したら前回のデータを使う。
 */
object Weather {
    private const val FORECAST_URL = "https://www.jma.go.jp/bosai/forecast/data/forecast/%s.json"
    private const val TIME_SERIES_URL = "https://www.jma.go.jp/bosai/jmatile/data/wdist/VPFD/%s.json"
    private const val AREA_URL = "https://www.jma.go.jp/bosai/common/const/area.json"
    private const val MAX_AGE = 30 * 60_000L

    /** hint を指定すると、地域コードの代わりに、名前にそれを含む地域（例: 「中部」）の予報を返す */
    fun get(context: Context, office: String, areaCode: String?, hint: String? = null): WeatherData? {
        val (json, stale) = cached(context, "forecast_$office.json", FORECAST_URL.format(office)) {
            parse(it, areaCode, hint)
        } ?: return null
        return runCatching { parse(json, areaCode, hint).copy(stale = stale) }.getOrNull()
    }

    /** 3時間ごとの予報（地域時系列予報）。areaCode は地域コード (例: 130010) */
    fun getTimeSeries(context: Context, areaCode: String): TimeSeriesData? {
        val (json, stale) = cached(context, "timeseries_$areaCode.json", TIME_SERIES_URL.format(areaCode)) {
            parseTimeSeries(it)
        } ?: return null
        return runCatching { parseTimeSeries(json).copy(stale = stale) }.getOrNull()
    }

    /**
     * 30分以内に取得したものがあればそれを、無ければ取得し直して返す。
     * 取得に失敗したら前回のデータを stale = true で返し、一度も取得できていなければ null。
     */
    private fun cached(context: Context, name: String, url: String, validate: (String) -> Unit): Pair<String, Boolean>? {
        val file = File(context.cacheDir, name)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE
        var stale = false
        if (!fresh) {
            try {
                val json = httpGet(url)
                validate(json) // 壊れたデータで上書きしないよう先に検証
                file.writeText(json)
            } catch (e: Exception) {
                stale = true
            }
        }
        if (!file.exists()) return null
        return file.readText() to stale
    }

    /** 地方（府県予報区）と、その中の地域の一覧。取得できないときは空 */
    fun offices(context: Context): List<Office> {
        val file = File(context.filesDir, "area.json")
        val old = !file.exists() || System.currentTimeMillis() - file.lastModified() > 30L * 24 * 3600_000
        if (old) runCatching { httpGet(AREA_URL).also { JSONObject(it) } }.onSuccess { file.writeText(it) }
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val offices = root.getJSONObject("offices")
            val class10s = root.getJSONObject("class10s")
            val class15s = root.optJSONObject("class15s")
            val class20s = root.optJSONObject("class20s")
            fun JSONObject?.children(): List<String> =
                this?.optJSONArray("children")?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList()

            offices.keys().asSequence().sorted().map { code ->
                val o = offices.getJSONObject(code)
                val areas = o.children().map { c -> c to (class10s.optJSONObject(c)?.optString("name") ?: c) }
                // 地域 (class10) → 細分区域 (class15) → 市区町村 (class20)
                val cities = areas.flatMap { (areaCode, areaName) ->
                    class10s.optJSONObject(areaCode).children().flatMap { c15 ->
                        class15s?.optJSONObject(c15).children().mapNotNull { c20 ->
                            class20s?.optJSONObject(c20)?.optString("name")?.let { City(c20, it, areaCode, areaName) }
                        }
                    }
                }.distinctBy { it.code }
                Office(code, o.getString("name"), areas, cities)
            }.toList()
        }.getOrDefault(emptyList())
    }

    /** 地方ごとの一覧の定義（assets/weather_regions.json） */
    fun regions(context: Context): List<Region> = runCatching {
        val root = JSONObject(context.assets.open("weather_regions.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        val arr = root.getJSONArray("regions")
        (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val tiles = r.getJSONArray("tiles")
            val v = r.getJSONArray("view")
            Region(r.getString("id"), r.getString("name"), (0 until tiles.length()).map { k ->
                val t = tiles.getJSONObject(k)
                val pos = t.optJSONArray("pos")?.let { Pair(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
                RegionTile(t.getString("label"), t.getString("office"), t.optString("hint").ifEmpty { null },
                    t.getDouble("lon").toFloat(), t.getDouble("lat").toFloat(), pos)
            }, FloatArray(4) { v.getDouble(it).toFloat() }, r.optDouble("stretch", 1.0).toFloat())
        }
    }.getOrDefault(emptyList())

    @Volatile private var mapCache: MapData? = null

    /** 地図の背景（assets/weather_map.json）。読めなければ null（背景なしで表示する） */
    fun mapData(context: Context): MapData? = mapCache ?: runCatching {
        val root = JSONObject(context.assets.open("weather_map.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        val prefs = root.getJSONObject("prefs")
        MapData(root.getInt("scale"), prefs.keys().asSequence().associateWith { code ->
            val rings = prefs.getJSONArray(code)
            (0 until rings.length()).map { i -> rings.getJSONArray(i).let { a -> IntArray(a.length()) { a.getInt(it) } } }
        })
    }.getOrNull()?.also { mapCache = it }

    /** 選んだ地方の一覧の画面を作る（府県ごとの予報を並行して取得する。通信するのでバックグラウンドで呼ぶ） */
    fun regionPages(context: Context, regions: List<Region>, dayMode: Int): List<RegionPageData> {
        val tiles = regions.flatMap { it.tiles }.distinctBy { it.office to it.hint }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val forecasts: Map<Pair<String, String?>, WeatherData?> = try {
            tiles.map { t -> t to pool.submit<WeatherData?> { runCatching { get(context, t.office, null, t.hint) }.getOrNull() } }
                .associate { (t, f) -> (t.office to t.hint) to runCatching { f.get() }.getOrNull() }
        } finally {
            pool.shutdown()
        }
        val days = when (dayMode) { 0 -> listOf(0); 2 -> listOf(0, 1); else -> listOf(1) }
        return regions.flatMap { r ->
            days.mapNotNull { d ->
                val list = r.tiles.map { t -> TileForecast(t, forecasts[t.office to t.hint]?.days?.getOrNull(d)) }
                val any = r.tiles.firstNotNullOfOrNull { forecasts[it.office to it.hint] } ?: return@mapNotNull null
                val day = list.firstNotNullOfOrNull { it.day } ?: return@mapNotNull null
                RegionPageData(r.name, d, day.date, list, any.reportTime, r.tiles.any { forecasts[it.office to it.hint]?.stale == true })
            }
        }
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "SignagePlayer/1.0")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // ---------------------------------------------------------------- 解析

    fun parse(json: String, areaCode: String?, hint: String? = null): WeatherData {
        val root = JSONArray(json)
        val short = root.getJSONObject(0)
        val report = OffsetDateTime.parse(short.getString("reportDatetime")).toLocalDateTime()
        val ts = short.getJSONArray("timeSeries")

        // 天気（今日・明日・明後日）
        val ts0 = ts.getJSONObject(0)
        val areas0 = ts0.getJSONArray("areas")
        val ai = findArea(areas0, areaCode, hint)
        val a0 = areas0.getJSONObject(ai)
        val areaName = a0.getJSONObject("area").getString("name")
        val times0 = times(ts0)
        val codes = a0.getJSONArray("weatherCodes")
        val texts = a0.optJSONArray("weathers")

        // 降水確率（6時間ごと）
        val ts1 = ts.optJSONObject(1)
        val popTimes = ts1?.let(::times) ?: emptyList()
        val pops = ts1?.getJSONArray("areas")?.let { it.getJSONObject(ai.coerceAtMost(it.length() - 1)) }
            ?.optJSONArray("pops")

        // 気温（朝の最低・日中の最高）
        val ts2 = ts.optJSONObject(2)
        val tempTimes = ts2?.let(::times) ?: emptyList()
        val temps = ts2?.getJSONArray("areas")?.let { it.getJSONObject(ai.coerceAtMost(it.length() - 1)) }
            ?.optJSONArray("temps")

        val days = (0 until minOf(2, times0.size, codes.length())).map { i ->
            val date = times0[i].toLocalDate()
            val pops6h = listOf(0, 6, 12, 18).map { h ->
                popTimes.indexOfFirst { it.toLocalDate() == date && it.hour == h }
                    .takeIf { it >= 0 }?.let { pops?.optString(it)?.ifEmpty { null } }
            }
            fun temp(hour: Int) = tempTimes.indices
                .lastOrNull { tempTimes[it].toLocalDate() == date && tempTimes[it].hour == hour }
                ?.let { temps?.optString(it)?.ifEmpty { null } }
            DayForecast(
                date = date,
                code = codes.getString(i),
                text = texts?.optString(i)?.replace("　", "")?.ifEmpty { null },
                max = temp(9),
                // 当日の 0時の値は最低気温ではない場合があるため、翌日以降のみ使う
                min = if (date == report.toLocalDate()) null else temp(0),
                pops6h = pops6h,
            )
        }

        // 週間予報（明日以降。今日・明日のカードより後の日だけ使う）
        val week = mutableListOf<DayForecast>()
        root.optJSONObject(1)?.optJSONArray("timeSeries")?.let { wts ->
            val w0 = wts.getJSONObject(0)
            val wAreas = w0.getJSONArray("areas")
            val wa = wAreas.getJSONObject(findArea(wAreas, areaCode))
            val wTimes = times(w0)
            val wCodes = wa.getJSONArray("weatherCodes")
            val wPops = wa.optJSONArray("pops")
            val t = wts.optJSONObject(1)?.getJSONArray("areas")?.let { it.getJSONObject(0) }
            val last = days.lastOrNull()?.date ?: report.toLocalDate()
            for (i in wTimes.indices) {
                val date = wTimes[i].toLocalDate()
                if (!date.isAfter(last)) continue
                week += DayForecast(
                    date = date,
                    code = wCodes.optString(i),
                    max = t?.optJSONArray("tempsMax")?.optString(i)?.ifEmpty { null },
                    min = t?.optJSONArray("tempsMin")?.optString(i)?.ifEmpty { null },
                    pop = wPops?.optString(i)?.ifEmpty { null },
                )
            }
        }
        return WeatherData(areaName, report, days, week, stale = false)
    }

    fun parseTimeSeries(json: String): TimeSeriesData {
        val root = JSONObject(json)
        val report = OffsetDateTime.parse(root.getString("reportDateTime")).toLocalDateTime()
        val area = root.getJSONObject("areaTimeSeries")
        val times = area.getJSONArray("timeDefines").let { arr ->
            (0 until arr.length()).map { OffsetDateTime.parse(arr.getJSONObject(it).getString("dateTime")).toLocalDateTime() }
        }
        val weather = area.getJSONArray("weather")
        val wind = area.optJSONArray("wind")

        // 気温は代表地点の値（時刻で対応づける）
        val point = root.optJSONObject("pointTimeSeries") ?: root.optJSONArray("pointTimeSeries")?.optJSONObject(0)
        val pointTimes = point?.optJSONArray("timeDefines")?.let { arr ->
            (0 until arr.length()).map { OffsetDateTime.parse(arr.getJSONObject(it).getString("dateTime")).toLocalDateTime() }
        } ?: emptyList()
        val temps = point?.optJSONArray("temperature")

        // 既に終わった時間帯は除く
        val now = LocalDateTime.now(ZoneId.of("Asia/Tokyo")) // 予報の時刻は日本時間
        val slots = times.indices.mapNotNull { i ->
            val t = times[i]
            if (t.plusHours(3) <= now) return@mapNotNull null
            val w = wind?.optJSONObject(i)
            val ti = pointTimes.indexOf(t)
            TimeSlot(
                time = t,
                weather = weather.optString(i),
                temp = if (ti >= 0) temps?.opt(ti)?.toString()?.ifEmpty { null } else null,
                windDir = w?.optString("direction")?.ifEmpty { null },
                windRange = w?.optString("range")?.trim()?.replace(" ", "〜")?.ifEmpty { null },
            )
        }
        return TimeSeriesData(point?.optString("pointNameJP") ?: "", report, slots, stale = false)
    }

    private fun times(ts: JSONObject): List<LocalDateTime> {
        val arr = ts.getJSONArray("timeDefines")
        return (0 until arr.length()).map { OffsetDateTime.parse(arr.getString(it)).toLocalDateTime() }
    }

    private fun findArea(areas: JSONArray, code: String?, hint: String? = null): Int {
        if (!hint.isNullOrEmpty()) {
            for (i in 0 until areas.length()) {
                if (areas.getJSONObject(i).getJSONObject("area").optString("name").contains(hint)) return i
            }
        }
        if (code != null) {
            for (i in 0 until areas.length()) {
                if (areas.getJSONObject(i).getJSONObject("area").optString("code") == code) return i
            }
        }
        return 0
    }

    // ---------------------------------------------------------------- アイコン

    private const val SUN = "☀️"
    private const val CLOUD = "☁️"
    private const val RAIN = "☔"
    private const val SNOW = "⛄"
    private const val MOON = "🌙"

    /** 天気の文字（晴れ・くもり・雨・雪など）→ 絵文字。夜の晴れは月にする */
    fun textIcon(text: String, hour: Int): Pair<String, String?> {
        val night = hour >= 18 || hour < 6
        return when {
            "雪" in text && "雨" in text -> RAIN to SNOW
            "雪" in text -> SNOW to null
            "雨" in text -> RAIN to null
            "くもり" in text || "曇" in text -> CLOUD to null
            "晴" in text -> (if (night) MOON else SUN) to null
            else -> CLOUD to null
        }
    }

    /** 天気コード → (主な天気, 補助の天気) の絵文字 */
    fun icon(code: String): Pair<String, String?> {
        val n = code.toIntOrNull() ?: return CLOUD to null
        val main = when (n / 100) { 1 -> SUN; 2 -> CLOUD; 3 -> RAIN; 4 -> SNOW; else -> CLOUD }
        val sub = when (n) {
            100, 200, 300, 400 -> null
            101, 110, 111, 130, 131, 132 -> CLOUD
            104, 105, 115, 116, 117, 160, 170, 181 -> SNOW
            in 102..199 -> RAIN
            201, 210, 211, 223, 231 -> SUN
            204, 205, 209, 215, 216, 217, 228, 229, 230, 250, 260, 270, 281 -> SNOW
            in 202..299 -> RAIN
            301, 311, 316, 320, 323, 324, 325 -> SUN
            302, 313, 350 -> CLOUD
            in 303..399 -> SNOW
            401, 411, 420 -> SUN
            402, 413, 450 -> CLOUD
            in 403..499 -> RAIN
            else -> null
        }
        return main to sub
    }
}
