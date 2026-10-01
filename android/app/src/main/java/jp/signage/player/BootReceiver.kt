package jp.signage.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 端末の起動完了時に、自動再生が有効なら再生画面を開く */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 他のアプリが送った偽の通知では動かさない（起動完了の通知だけを受け付ける）
        if (intent.action !in BOOT_ACTIONS) return
        val prefs = Prefs(context)
        // 管理画面は自動再生の設定に関係なく、電源ON時から使えるようにする
        AdminService.sync(context)
        if (!prefs.autoStart) return
        context.startActivity(
            Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
