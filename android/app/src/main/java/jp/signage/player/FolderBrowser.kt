package jp.signage.player

import android.app.Activity
import android.app.AlertDialog
import android.os.Environment
import java.io.File

/**
 * システムのフォルダ選択画面が無い端末（一部の Android TV / STB など）向けの簡易フォルダブラウザ。
 * 内部ストレージ・SDカード・USBメモリを直接たどってフォルダを選ぶ。
 */
class FolderBrowser(private val activity: Activity, private val onSelected: (File) -> Unit) {

    fun show(start: File? = null) {
        if (start == null || !start.isDirectory) showRoots() else showDir(start)
    }

    /** 端末に接続されているストレージの一覧 */
    private fun roots(): List<Pair<String, File>> {
        val result = linkedMapOf<String, Pair<String, File>>()
        val internal = Environment.getExternalStorageDirectory()
        result[internal.absolutePath] = "内部ストレージ" to internal
        // SDカード・USBメモリ: アプリ専用フォルダのパス (/storage/XXXX-XXXX/Android/data/...) から根元を求める
        activity.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            val root = File(dir.absolutePath.substringBefore("/Android/"))
            if (root.absolutePath !in result && root.isDirectory) {
                result[root.absolutePath] = "外部ストレージ (${root.name})" to root
            }
        }
        // 上の方法で見つからない USB メモリ向けに /storage 直下も確認
        File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" }
            ?.forEach { if (it.absolutePath !in result) result[it.absolutePath] = "外部ストレージ (${it.name})" to it }
        return result.values.toList()
    }

    private fun showRoots() {
        val roots = roots()
        AlertDialog.Builder(activity)
            .setTitle("ストレージを選択")
            .setItems(roots.map { "💾 ${it.first}" }.toTypedArray()) { _, i -> showDir(roots[i].second) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showDir(dir: File) {
        val subDirs = dir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedWith(compareBy(MediaScanner.naturalOrder) { it.name })
            ?: emptyList()
        val mediaCount = dir.listFiles()?.count { it.isFile && !it.name.startsWith(".") } ?: 0
        val isRoot = roots().any { it.second.absolutePath == dir.absolutePath }
        val labels = mutableListOf(if (isRoot) "⬆ ストレージの一覧へ" else "⬆ 上のフォルダへ")
        labels += subDirs.map { "📁 ${it.name}" }

        AlertDialog.Builder(activity)
            .setTitle("${MediaScanner.describePath(dir.absolutePath)}\n（ファイル $mediaCount 件）")
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i > 0 -> showDir(subDirs[i - 1])
                    isRoot -> showRoots()
                    else -> showDir(dir.parentFile ?: dir)
                }
            }
            .setPositiveButton("このフォルダを使用") { _, _ -> onSelected(dir) }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
