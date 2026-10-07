package jp.simplesignage

import android.content.Context
import android.net.Uri
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 画像・動画のライブラリ。保存場所は、アプリ専用のフォルダ 1 か所（Android/data/…/files/library）。
 * どの区画で・いつ流すかは「配置」（Prefs.Placement）で決める。同じファイルを、複数の区画・時間帯に置ける。
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
        val prefs = Prefs(context)
        return prefs.placementsOf(zone).mapNotNull { p ->
            val f = File(lib, p.name)
            val video = MediaScanner.kindOf(p.name) ?: return@mapNotNull null
            if (!f.isFile) return@mapNotNull null
            MediaEntry(Uri.fromFile(f), p.name, video, f.length(), p.id, p.rule, p.exclusive, p.seconds, prefs.fileRotation(p.name))
        }
    }

    fun find(context: Context, zone: Int, name: String): MediaEntry =
        entries(context, zone).firstOrNull { it.name == name } ?: throw IOException("ファイルが見つかりません: $name")

    /** ライブラリのファイル（区画に関係なく）を読み出す */
    fun openFile(context: Context, name: String): Pair<InputStream, Long> {
        ensureMigrated(context)
        val f = libraryFile(context, name)
        return f.inputStream() to f.length()
    }

    fun libraryFile(context: Context, name: String): File {
        val f = File(dir(context), name)
        if (MediaScanner.kindOf(name) == null || f.parentFile != dir(context) || !f.isFile) throw IOException("ファイルが見つかりません: $name")
        return f
    }

    /** ライブラリの全ファイル。使われ方（配置の ID と区画）つき */
    class Item(val name: String, val size: Long, val video: Boolean, val rotation: Int, val placements: List<Pair<String, Int>>)

    fun items(context: Context): List<Item> {
        ensureMigrated(context)
        val prefs = Prefs(context)
        val used = prefs.placements().groupBy { it.name }
        val files = dir(context).listFiles()?.filter { it.isFile && !it.name.startsWith(".") && MediaScanner.kindOf(it.name) != null } ?: emptyList()
        return files.sortedWith(compareBy(MediaScanner.naturalOrder) { it.name }).map {
            Item(it.name, it.length(), MediaScanner.kindOf(it.name) == true, prefs.fileRotation(it.name),
                used[it.name]?.map { p -> p.id to p.zone } ?: emptyList())
        }
    }

    /**
     * input から length バイトを読んでライブラリに保存し、保存した名前を返す。
     * zone が null でなければ、その区画に（条件なしで）配置する
     */
    fun upload(context: Context, zone: Int?, name: String, length: Long, input: InputStream): String {
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
        if (zone != null) addPlacement(context, zone, finalName)
        return finalName
    }

    // ---------------------------------------------------------------- 配置の操作

    /** 配置を追加して ID を返す。条件のない同じ配置があれば、それを返す（重複させない） */
    @Synchronized
    fun addPlacement(
        context: Context, zone: Int, name: String,
        rule: JSONObject? = null, exclusive: Boolean = false, seconds: Int? = null,
    ): String {
        val prefs = Prefs(context)
        val list = prefs.placements()
        if (rule == null && !exclusive && seconds == null) {
            list.firstOrNull { it.zone == zone && it.name == name && it.rule == null && !it.exclusive && it.seconds == null }?.let { return it.id }
        }
        val p = Prefs.Placement(prefs.makePlacementId(), zone, name, rule, exclusive, seconds)
        prefs.replacePlacements(list + p)
        return p.id
    }

    /** 配置の条件・専用・秒数・区画を変える（fields に含まれる項目だけ）。見つからなければ false */
    @Synchronized
    fun updatePlacement(context: Context, id: String, fields: JSONObject): Boolean {
        val prefs = Prefs(context)
        var found = false
        val list = prefs.placements().map { p ->
            if (p.id != id) return@map p
            found = true
            var q = p
            if (fields.has("rule")) q = q.copy(rule = FileRule.normalize(fields.optJSONObject("rule")))
            if (fields.has("exclusive")) q = q.copy(exclusive = fields.optBoolean("exclusive", false))
            if (fields.has("seconds")) q = q.copy(seconds = cleanSeconds(if (fields.isNull("seconds")) null else fields.opt("seconds")))
            if (fields.has("zone")) {
                val z = fields.optInt("zone", -1)
                if (z !in 0 until Prefs.MAX_ZONES) throw IllegalArgumentException("区画が正しくありません")
                q = q.copy(zone = z)
            }
            q
        }
        if (found) prefs.replacePlacements(list)
        return found
    }

    private fun cleanSeconds(v: Any?): Int? {
        if (v == null || v == "" || v == 0) return null
        val n = (v as? Number)?.toInt() ?: v.toString().toIntOrNull() ?: throw IllegalArgumentException("表示秒数は 1〜3600 の数字にしてください")
        return n.coerceIn(1, 3600)
    }

    /** 配置を外す（ファイルは、ライブラリに残る） */
    @Synchronized
    fun removePlacementById(context: Context, id: String) {
        val prefs = Prefs(context)
        prefs.replacePlacements(prefs.placements().filterNot { it.id == id })
    }

    /** 区画の配置を、ids の順に並べ替える（ids に無いものは、後ろに、元の順で残す） */
    @Synchronized
    fun reorder(context: Context, zone: Int, ids: List<String>) {
        val prefs = Prefs(context)
        val all = prefs.placements()
        val mine = all.filter { it.zone == zone }
        val ordered = ids.mapNotNull { id -> mine.firstOrNull { it.id == id } } + mine.filter { it.id !in ids }
        val it = ordered.iterator()
        prefs.replacePlacements(all.map { p -> if (p.zone == zone) it.next() else p })
    }

    /** （旧 API 用）区画から、その名前の配置をすべて外す。どの区画にも使われなくなったファイルは、ライブラリからも消す */
    @Synchronized
    fun remove(context: Context, zone: Int, name: String) {
        ensureMigrated(context)
        val prefs = Prefs(context)
        val rest = prefs.placements().filterNot { it.zone == zone && it.name == name }
        prefs.replacePlacements(rest)
        if (rest.none { it.name == name }) deleteFile(context, name)
    }

    /** ライブラリからファイルを消す（すべての配置も外れる） */
    @Synchronized
    fun deleteLibraryFile(context: Context, name: String) {
        val prefs = Prefs(context)
        prefs.replacePlacements(prefs.placements().filterNot { it.name == name })
        deleteFile(context, name)
    }

    private fun deleteFile(context: Context, name: String) {
        File(dir(context), name).delete()
        Prefs(context).setFileRotation(name, 0)
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
     * 1) 旧バージョンの区画ごとのフォルダ（外部フォルダ・サブフォルダを含む）の画像・動画を、ライブラリに集めて配置にする。
     *    元のファイルは消さない（同じ場所なら、リンクで容量は増えない）
     * 2) 配置に ID を付け、ファイルごとの再生条件（区画|名前）を配置の条件に移し、回転はファイル名だけのキーにする
     */
    @Synchronized
    fun ensureMigrated(context: Context) {
        val prefs = Prefs(context)
        if (!prefs.libraryMigrated) migrateFolders(context, prefs)
        if (!prefs.placementsV2) migrateToV2(prefs)
    }

    private fun migrateFolders(context: Context, prefs: Prefs) {
        val lib = dir(context)
        val existing = lib.list()?.map { it.lowercase() }?.toMutableSet() ?: mutableSetOf()
        val placements = mutableListOf<Prefs.Placement>()
        val known = HashMap<Pair<String, Long>, String>() // (名前, 大きさ) → ライブラリ内の名前（同じファイルは 1 つにまとめる）
        val oldRules = prefs.legacyFileRules()
        val oldRots = prefs.legacyFileRotations()
        val newRules = mutableMapOf<String, JSONObject>()
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
                placements += Prefs.Placement("", i, name)
                oldRules.optJSONObject("$i|${e.name}")?.let { newRules["$i|$name"] = it }
                if (oldRots.has("$i|${e.name}")) newRots["$i|$name"] = oldRots.optInt("$i|${e.name}", 0)
            }
        }
        prefs.replacePlacements(placements.map { it.copy(id = prefs.makePlacementId()) })
        prefs.replaceFileRulesAndRotations(newRules, newRots)
        prefs.libraryMigrated = true
    }

    private fun migrateToV2(prefs: Prefs) {
        val rules = prefs.legacyFileRules()
        val rots = prefs.legacyFileRotations()
        val placements = prefs.placements().map { p ->
            val rule = rules.optJSONObject("${p.zone}|${p.name}")
            if (rule != null && p.rule == null) p.copy(rule = rule) else p
        }
        prefs.replacePlacements(placements)
        val newRots = mutableMapOf<String, Int>()
        rots.keys().forEach { k ->
            val name = k.substringAfter('|')
            if (name !in newRots) newRots[name] = rots.optInt(k, 0)
        }
        prefs.replaceFileRulesAndRotations(emptyMap(), newRots)
        prefs.placementsV2 = true
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
