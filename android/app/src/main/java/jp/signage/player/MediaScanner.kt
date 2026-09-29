package jp.signage.player

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException

data class MediaEntry(
    val uri: Uri,
    /** フォルダからの相対パス（表示・並び替え用） */
    val name: String,
    val isVideo: Boolean,
    /** バイト数（不明なら 0） */
    val size: Long = 0,
)

/**
 * 選んだフォルダから画像・動画を一覧化する。
 * フォルダは Storage Access Framework の content:// か、アプリ内ブラウザで選んだ file:// のどちらか。
 */
object MediaScanner {
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    private val VIDEO_EXTS = setOf("mp4", "m4v", "webm", "mkv", "3gp", "mov", "ts")

    fun scan(resolver: ContentResolver, folder: Uri, recursive: Boolean): List<MediaEntry> {
        val out = mutableListOf<MediaEntry>()
        if (folder.scheme == "file") {
            val dir = File(folder.path ?: "")
            // USBメモリが外れた場合などは空ではなくエラーとして扱う
            if (!dir.isDirectory || dir.listFiles() == null) throw IOException("読み込めません: $dir")
            walkFiles(dir, "", recursive, out)
        } else {
            walk(resolver, folder, DocumentsContract.getTreeDocumentId(folder), "", recursive, out)
        }
        return out.sortedWith(compareBy(naturalOrder) { it.name })
    }

    private fun walkFiles(dir: File, prefix: String, recursive: Boolean, out: MutableList<MediaEntry>) {
        for (f in dir.listFiles() ?: return) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (recursive) walkFiles(f, "$prefix${f.name}/", true, out)
                continue
            }
            val kind = kindOf(f.name, "") ?: continue
            out += MediaEntry(Uri.fromFile(f), prefix + f.name, kind, f.length())
        }
    }

    /** 動画なら true、画像なら false、どちらでもなければ null */
    fun kindOf(name: String, mime: String = ""): Boolean? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("video/") || ext in VIDEO_EXTS -> true
            mime.startsWith("image/") || ext in IMAGE_EXTS -> false
            else -> null
        }
    }

    private fun walk(
        resolver: ContentResolver, tree: Uri, docId: String, prefix: String,
        recursive: Boolean, out: MutableList<MediaEntry>,
    ) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        resolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (name.startsWith(".")) continue
                if (mime == Document.MIME_TYPE_DIR) {
                    if (recursive) walk(resolver, tree, id, "$prefix$name/", true, out)
                    continue
                }
                val isVideo = kindOf(name, mime) ?: continue
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                out += MediaEntry(DocumentsContract.buildDocumentUriUsingTree(tree, id), prefix + name, isVideo, size)
            }
        }
    }

    /** "img2" < "img10" となる自然順 */
    val naturalOrder = Comparator<String> { a, b ->
        val ta = tokens(a)
        val tb = tokens(b)
        for (i in 0 until minOf(ta.size, tb.size)) {
            val x = ta[i]
            val y = tb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else {
                x.compareTo(y, ignoreCase = true)
            }
            if (c != 0) return@Comparator c
        }
        ta.size - tb.size
    }

    private val TOKEN = Regex("\\d+|\\D+")
    private fun tokens(s: String) = TOKEN.findAll(s).map { it.value }.toList()

    /** 設定画面向けの表示名 (例: 内部ストレージ/Movies/Signage) */
    fun describe(treeUri: Uri): String = runCatching {
        if (treeUri.scheme == "file") return@runCatching describePath(treeUri.path ?: "")
        val id = DocumentsContract.getTreeDocumentId(treeUri)
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        (if (volume == "primary") "内部ストレージ" else volume) + "/" + path
    }.getOrDefault(treeUri.toString())

    fun describePath(path: String): String = when {
        path.startsWith("/storage/emulated/0") -> "内部ストレージ" + path.removePrefix("/storage/emulated/0")
        path.startsWith("/storage/") -> "外部ストレージ " + path.removePrefix("/storage/")
        else -> path
    }
}
