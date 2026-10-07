package jp.signage.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder

/**
 * 管理画面のサーバーを常駐させるフォアグラウンドサービス。
 * アプリが画面に出ていないと Android がプロセスを凍結し、別の端末からの接続に応答できなくなるため、
 * 通知を出して動かし続ける。Wi-Fi の省電力で通信が止まらないようにロックも取る。
 */
class AdminService : Service() {
    companion object {
        private const val CHANNEL = "admin"
        private const val NOTIFICATION_ID = 1

        /** 設定に合わせてサービスを開始・停止する */
        fun sync(context: Context) {
            val intent = Intent(context, AdminService::class.java)
            if (Prefs(context).adminEnabled) {
                runCatching { context.startForegroundService(intent) }
                    .onFailure { AdminServer.update(context) } // 開始できない状況でもサーバーだけは動かす
            } else {
                context.stopService(intent)
                AdminServer.update(context)
            }
        }
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private val listener: (String) -> Unit = { if (it == AdminServer.EVENT_SERVER) updateNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "管理画面", NotificationManager.IMPORTANCE_LOW).apply {
                description = "別の端末から操作するための管理画面が動いていることを表示します"
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "signage:admin")
            .apply { setReferenceCounted(false); acquire() }
        AdminServer.addListener(listener)
        AdminServer.update(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs(this).adminEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        AdminServer.update(this)
        return START_STICKY
    }

    override fun onDestroy() {
        AdminServer.removeListener(listener)
        wifiLock?.let { if (it.isHeld) it.release() }
        AdminServer.update(this)
        super.onDestroy()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val text = if (AdminServer.isRunning) {
            AdminServer.localAddresses().firstOrNull()?.let { "${AdminServer.scheme}://$it:${AdminServer.port}/" }
                ?: "Wi-Fi・LAN に接続されていません"
        } else {
            "起動中…"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FROM_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_admin)
            .setContentTitle("SimpleSignage 管理画面")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
