package jp.signage.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 区画のフォルダへのファイル追加・削除・読み出し（管理画面用）。
 * フォルダは Storage Access Framework の content:// と、端末内を直接指す file:// の両方に対応する。
 */
object FolderStore {
    /** そのフォルダに書き込めるか */
    fun isWritable(context: Context, folder: Uri): Boolean {
        if (folder.scheme != "file") {
            return context.contentResolver.persistedUriPermissions.any { it.uri == folder && it.isWritePermission }
        }
        val probe = File(folder.path ?: return false, ".write-test-${System.nanoTime()}")
        return runCatching { probe.createNewFile().also { probe.delete() } }.getOrDefault(false)
    }

    /** ファイル名から危険な文字を除き、画像・動画でなければ null */
    fun sanitize(raw: String): String? {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f:*?\"<>|]"), "_")
            .trim().trimStart('.')
        if (name.isEmpty() || name.length > 150) return null
        return name.takeIf { MediaScanner.kindOf(it) != null }
    }

    /**
     * input から length バイトを読んでフォルダ直下に保存し、保存した名前を返す。
     * 書き込み中のファイルを再生しないよう、隠しファイル名で書いてから名前を変える。
     */
    fun upload(context: Context, folder: Uri, name: String, length: Long, input: InputStream): String {
        val existing = MediaScanner.scan(context.contentResolver, folder, false).map { it.name.lowercase() }.toSet()
        val finalName = uniqueName(name, existing)
        val ext = finalName.substringAfterLast('.', "")
        val temp = ".upload-${System.nanoTime()}.$ext"

        if (folder.scheme == "file") {
            val dir = File(folder.path ?: throw IOException("フォルダが不正です"))
            val tmp = File(dir, temp)
            try {
                tmp.outputStream().use { copy(input, it, length) }
                if (!tmp.renameTo(File(dir, finalName))) throw IOException("ファイル名を変更できません")
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        } else {
            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
            val doc = DocumentsContract.createDocument(resolver, parent, mime, temp)
                ?: throw IOException("ファイルを作成できません")
            try {
                (resolver.openOutputStream(doc) ?: throw IOException("書き込めません")).use { copy(input, it, length) }
                DocumentsContract.renameDocument(resolver, doc, finalName)
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                throw e
            }
        }
        invalidate(folder)
        return finalName
    }

    /** name はフォルダからの相対パス（一覧に出る名前） */
    fun delete(context: Context, folder: Uri, name: String, recursive: Boolean) {
        val entry = find(context, folder, name, recursive)
        val ok = if (entry.uri.scheme == "file") {
            File(entry.uri.path ?: "").delete()
        } else {
            DocumentsContract.deleteDocument(context.contentResolver, entry.uri)
        }
        invalidate(folder)
        if (!ok) throw IOException("削除できません（この端末ではこのフォルダのファイルを消せない可能性があります）")
    }

    fun open(context: Context, folder: Uri, name: String, recursive: Boolean): Pair<InputStream, MediaEntry> {
        val entry = find(context, folder, name, recursive)
        val stream = context.contentResolver.openInputStream(entry.uri) ?: throw IOException("読み込めません")
        return stream to entry
    }

    private fun find(context: Context, folder: Uri, name: String, recursive: Boolean): MediaEntry =
        cachedList(context, folder, recursive).firstOrNull { it.name == name }
            ?: throw IOException("ファイルが見つかりません: $name")

    /** サムネイルを続けて取得するときに毎回フォルダを読み直さないよう、一覧を少しの間覚えておく */
    private val cache = mutableMapOf<Pair<Uri, Boolean>, Pair<Long, List<MediaEntry>>>()

    @Synchronized
    private fun cachedList(context: Context, folder: Uri, recursive: Boolean): List<MediaEntry> {
        val key = folder to recursive
        val now = System.currentTimeMillis()
        cache[key]?.let { (at, list) -> if (now - at < 10_000) return list }
        return MediaScanner.scan(context.contentResolver, folder, recursive).also { cache[key] = now to it }
    }

    @Synchronized
    private fun invalidate(folder: Uri) {
        cache.keys.removeAll { it.first == folder }
    }

    /** 同じ名前があれば「名前 (2).拡張子」のようにする */
    private fun uniqueName(name: String, existing: Set<String>): String {
        if (name.lowercase() !in existing) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val candidate = "$base ($n).$ext"
            if (candidate.lowercase() !in existing) return candidate
            n++
        }
    }

    private fun copy(input: InputStream, out: OutputStream, length: Long) {
        val buf = ByteArray(256 * 1024)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("転送が途中で切れました")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    /** フォルダ選択画面の結果から、読み書きの永続アクセス権を取得する */
    fun takePermission(context: Context, uri: Uri, resultFlags: Int) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val flags = (resultFlags and rw).takeIf { it != 0 } ?: Intent.FLAG_GRANT_READ_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }
}
