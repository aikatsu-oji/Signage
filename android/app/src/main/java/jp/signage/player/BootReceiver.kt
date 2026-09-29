package jp.signage.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 端末の起動完了時に、自動再生が有効なら再生画面を開く */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.autoStart) return
        context.startActivity(
            Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
