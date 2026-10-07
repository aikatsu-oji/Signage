package jp.signage.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File

/**
 * 管理画面から送られた APK でアプリ自身を更新する。
 * 安全のため、端末側で許可を ON にしたときだけ受け付け、「同じアプリ・いまより新しい版・同じ署名」の APK だけ入れる。
 */
object AppUpdater {
    class State(val phase: String, val message: String, val at: Long = System.currentTimeMillis())

    /** idle / received / pending_user / installing / success / failed */
    @Volatile var state = State("idle", "")

    /** 端末の確認画面（「更新しますか？」）。バックグラウンドからは開けないことがあるため、保持して、通知・アプリ画面から開けるようにする */
    @Volatile var pendingConfirm: Intent? = null

    const val ACTION = "jp.signage.player.UPDATE_RESULT"
    const val MAX_BYTES = 200L * 1024 * 1024

    fun apkFile(ctx: Context) = File(ctx.cacheDir, "update.apk")

    /** 管理画面からのアプリ更新に対応した版か（Google Play 版は、Google Play が更新するので、対応しない） */
    val supported: Boolean get() = BuildConfig.SELF_UPDATE

    fun canInstall(ctx: Context): Boolean =
        supported && Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    private fun versionCode(i: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) i.longVersionCode else i.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(i: PackageInfo): List<Signature>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) i.signingInfo?.apkContentsSigners?.toList() else i.signatures?.toList()

    private val signFlag: Int
        @Suppress("DEPRECATION")
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /** 問題があれば、その説明。なければ null */
    fun validate(ctx: Context, f: File): String? {
        val pm = ctx.packageManager
        val apk = pm.getPackageArchiveInfo(f.path, signFlag) ?: return "APK を読み取れません（壊れているか、APK ではありません）"
        if (apk.packageName != ctx.packageName) return "このアプリの APK ではありません（${apk.packageName}）"
        val cur = pm.getPackageInfo(ctx.packageName, signFlag)
        val newCode = versionCode(apk)
        if (newCode <= versionCode(cur)) {
            return "いまの版（${cur.versionName}）より新しい APK ではありません（送られた APK：${apk.versionName}）"
        }
        val a = signers(apk)?.map { it.toCharsString() }?.toSet()
        val b = signers(cur)?.map { it.toCharsString() }?.toSet()
        if (a != null && b != null && a != b) return "署名がいまのアプリと違うため、更新できません（署名の違う APK は入れられません）"
        return null
    }

    /** インストールを始める。確認が必要な端末では、端末の画面に確認が出る */
    fun install(ctx: Context, f: File) {
        if (!supported) {
            state = State("failed", "この版（Google Play 版）は、Google Play で更新します")
            return
        }
        if (!canInstall(ctx)) {
            state = State("failed", "この端末で、SimpleSignage に「不明なアプリのインストール」の許可がありません。端末の設定画面から許可してください")
            return
        }
        try {
            val pi = ctx.packageManager.packageInstaller
            // 前回の失敗で残った、結果待ちのインストールを片付ける
            pi.mySessions.forEach { runCatching { pi.abandonSession(it.sessionId) } }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { session ->
                f.inputStream().use { input ->
                    session.openWrite("update", 0, f.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateResultReceiver::class.java).setAction(ACTION), flags)
                state = State("installing", "インストール中…")
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            state = State("failed", "インストールを始められませんでした：${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** インストールの結果を受け取る（確認が必要なときは、確認画面を開く） */
class UpdateResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            AppUpdater.apkFile(context).delete()
        }
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                AppUpdater.state = AppUpdater.State("pending_user", "端末の画面に出た確認で「更新」「インストール」をタップしてください（出ないときは、端末の通知をタップするか、サイネージのアプリを開いてください）")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                AppUpdater.pendingConfirm = confirm
                if (confirm != null) {
                    // 端末の画面が別のアプリのときなど、確認画面を自動で開けない場合があるので、通知でも知らせる
                    notifyConfirm(context, confirm)
                    try { context.startActivity(confirm) } catch (e: Exception) { /* 通知か、アプリの画面から開く */ }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppUpdater.pendingConfirm = null
                AppUpdater.state = AppUpdater.State("success", "更新しました")
            }
            else -> {
                AppUpdater.pendingConfirm = null
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "不明なエラー"
                AppUpdater.state = AppUpdater.State("failed", "更新できませんでした（$status）：$msg")
            }
        }
    }

    private fun notifyConfirm(context: Context, confirm: Intent) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel("update", "アプリの更新", android.app.NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val tap = PendingIntent.getActivity(context, 7, confirm, flags)
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.app.Notification.Builder(context, "update")
            else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
            nm.notify(
                7001,
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("SimpleSignage の更新があります")
                    .setContentText("タップして、更新を進めてください")
                    .setContentIntent(tap).setAutoCancel(true).build(),
            )
        } catch (e: Exception) { /* 通知を出せなくても、アプリの画面から開ける */ }
    }
}
