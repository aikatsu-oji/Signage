package jp.signage.player

import java.util.concurrent.ConcurrentHashMap

/** 再生画面がいま何を表示しているか（管理画面のモニタリング用）。再生画面が動いている間だけ更新される */
object PlayerStatus {
    @Volatile var running = false
    @Volatile var runningSince = 0L

    class ZoneInfo(
        val kind: String, val name: String, val video: Boolean,
        val pos: Int, val total: Int, val message: String, val at: Long,
    )

    val zones = ConcurrentHashMap<Int, ZoneInfo>()

    fun media(zone: Int, name: String, video: Boolean, pos: Int, total: Int) {
        zones[zone] = ZoneInfo("media", name, video, pos, total, "", System.currentTimeMillis())
    }

    fun message(zone: Int, text: String?) {
        val old = zones[zone]
        zones[zone] = ZoneInfo(
            old?.kind ?: "media", if (text == null) old?.name ?: "" else "", old?.video ?: false,
            old?.pos ?: 0, old?.total ?: 0, text ?: "", System.currentTimeMillis(),
        )
    }

    fun clear() = zones.clear()
}
