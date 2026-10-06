package jp.signage.player

import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 画像・動画のライブラリ。保存場所は、アプリ専用のフォルダ 1 か所（Android/data/…/files/library）。
 * どの区画で流すかは「配置」（Prefs.placements）で決める。同じファイルを、複数の区画に置ける。
 */
object Library {
    fun dir(context: Context): File {
        val d = context.getExternalFilesDir("library") ?: File(context.filesDir, "library")
        d.mkdirs()
        return d
    }

    /** ファイル名から危険な文字を除き、画像・動画でなければ null */
    fun sanitize(raw: String): String? {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f:*?\"<>|]"), "_")
            .trim().trimStart('.')
        if (name.isEmpty() || name.length > 150) return null
        return name.takeIf { MediaScanner.kindOf(it) != null }
    }

    /** 区画に配置されている画像・動画を、配置の順に返す（ライブラリに無いものは除く） */
    fun entries(context: Context, zone: Int): List<MediaEntry> {
        ensureMigrated(context)
        val lib = dir(context)
        return Prefs(context).placementsOf(zone).mapNotNull { name ->
            val f = File(lib, name)
            val video = MediaScanner.kindOf(name) ?: return@mapNotNull null
            if (!f.isFile) return@mapNotNull null
            MediaEntry(Uri.fromFile(f), name, video, f.length())
        }
    }

    fun find(context: Context, zone: Int, name: String): MediaEntry =
        entries(context, zone).firstOrNull { it.name == name } ?: throw IOException("ファイルが見つかりません: $name")

    fun open(context: Context, zone: Int, name: String): Pair<InputStream, MediaEntry> {
        val e = find(context, zone, name)
        return File(e.uri.path ?: throw IOException("読み込めません")).inputStream() to e
    }

    /** input から length バイトを読んでライブラリに保存し、区画に配置する。保存した名前を返す */
    fun upload(context: Context, zone: Int, name: String, length: Long, input: InputStream): String {
        ensureMigrated(context)
        val lib = dir(context)
        val finalName = uniqueName(name, lib.list()?.map { it.lowercase() }?.toSet() ?: emptySet())
        // 書き込み中のファイルを再生しないよう、隠しファイル名で書いてから名前を変える
        val tmp = File(lib, ".upload-${System.nanoTime()}.${finalName.substringAfterLast('.', "")}")
        try {
            tmp.outputStream().use { copy(input, it, length) }
            if (!tmp.renameTo(File(lib, finalName))) throw IOException("ファイル名を変更できません")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        Prefs(context).addPlacement(zone, finalName)
        return finalName
    }

    /** 区画から外す。どの区画にも使われなくなったファイルは、ライブラリからも消す。再生条件・回転も消す */
    fun remove(context: Context, zone: Int, name: String) {
        ensureMigrated(context)
        val prefs = Prefs(context)
        val stillUsed = prefs.removePlacement(zone, name)
        prefs.setFileRule(zone, name, null)
        prefs.setFileRotation(zone, name, 0)
        if (!stillUsed) File(dir(context), name).delete()
    }

    /** ライブラリの全ファイル数と、そのうち使われていない数 */
    fun summary(context: Context): Pair<Int, Int> {
        ensureMigrated(context)
        val used = Prefs(context).placements().map { it.second }.toSet()
        val files = dir(context).listFiles()?.filter { it.isFile && !it.name.startsWith(".") && MediaScanner.kindOf(it.name) != null } ?: emptyList()
        return files.size to files.count { it.name !in used }
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

    // ---------------------------------------------------------------- 旧バージョンからの移行

    /**
     * 旧バージョンの区画ごとのフォルダ（外部フォルダ・サブフォルダを含む）の画像・動画を、ライブラリに集めて配置にする。
     * 元のファイルは消さない（同じ場所なら、リンクで容量は増えない）。再生条件・回転は、新しい名前に付け替える
     */
    @Synchronized
    fun ensureMigrated(context: Context) {
        val prefs = Prefs(context)
        if (prefs.libraryMigrated) return
        val lib = dir(context)
        val existing = lib.list()?.map { it.lowercase() }?.toMutableSet() ?: mutableSetOf()
        val placements = mutableListOf<Pair<Int, String>>()
        val known = HashMap<Pair<String, Long>, String>() // (名前, 大きさ) → ライブラリ内の名前（同じファイルは 1 つにまとめる）
        val oldRules = (0 until Prefs.MAX_ZONES).associateWith { prefs.fileRulesOf(it) }
        val oldRots = (0 until Prefs.MAX_ZONES).associateWith { prefs.fileRotationsOf(it) }
        val newRules = mutableMapOf<String, org.json.JSONObject>()
        val newRots = mutableMapOf<String, Int>()
        for (i in 0 until Prefs.MAX_ZONES) {
            val folder = prefs.legacyZoneFolder(i) ?: continue
            val items = runCatching { MediaScanner.scan(context.contentResolver, folder, prefs.recursive) }.getOrNull() ?: continue
            for (e in items) {
                val base = e.name.substringAfterLast('/')
                val key = base to e.size
                var name = known[key]
                if (name == null && File(lib, base).let { it.isFile && it.length() == e.size }) {
                    name = base // 前回の移行が途中で止まった場合など、すでに同じファイルがあれば、それを使う
                    known[key] = name
                }
                if (name == null) {
                    val candidate = uniqueName(base, existing)
                    if (!linkOrCopy(context, e.uri, File(lib, candidate))) continue
                    name = candidate
                    known[key] = name
                    existing += name.lowercase()
                }
                placements += i to name
                oldRules[i]?.get(e.name)?.let { newRules["$i|$name"] = it }
                oldRots[i]?.get(e.name)?.let { newRots["$i|$name"] = it }
            }
        }
        prefs.replacePlacements(placements)
        prefs.replaceFileRulesAndRotations(newRules, newRots)
        prefs.libraryMigrated = true
    }

    private fun linkOrCopy(context: Context, src: Uri, dest: File): Boolean {
        if (src.scheme == "file") {
            val path = src.path ?: return false
            if (runCatching { Os.link(path, dest.path) }.isSuccess) return true
        }
        return runCatching {
            (context.contentResolver.openInputStream(src) ?: return false).use { input ->
                dest.outputStream().use { input.copyTo(it, 256 * 1024) }
            }
        }.onFailure { dest.delete() }.isSuccess
    }
}
