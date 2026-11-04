package io.github.zod701.pagurin

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import android.os.StatFs
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

// format: the import tool's (pagurin_import.py FORMAT), 0 before it wrote one; features: what the host can do as the import found it, null when it did not look.
// adapter: the kind of port; hostKnows: the settings (and "driver") its host reads, null when its host could not be read; badge: the kind's word for it (Adapter.badge)
data class Game(val id: String, val name: String, val display: String, @StringRes val badge: Int?, val bytes: Long, val dir: File, val lastPlayed: Long, val format: Int = 0, val features: Set<String>? = null,
                val adapter: Adapter = Nx2Android, val hostKnows: Set<String>? = null, val own: List<Setting> = adapter.settings) {
    // What can be set for this game: its kind's settings as they apply to it (Adapter.gameSettings), those its host reads
    val settings get() = own.filter { hostKnows?.contains(it.key) ?: true }
    val takesDriver get() = adapter.driverMarker != null && has("drivers") && (hostKnows?.contains("driver") ?: true)
    val managesSaves get() = adapter.saves == SaveMode.HOST_REQUEST && has("saves")
    val tooNew get() = format > SUPPORTED_FORMAT
    fun has(feature: String) = features?.contains(feature) ?: true
}

// The newest game folder this app reads. A later import tool may store a game differently: such a game is not started
const val SUPPORTED_FORMAT = 1
data class Driver(val name: String, val bytes: Long)
data class SaveFolder(val name: String, val bytes: Long, val time: Long)

private const val BLOCK_START = "# pagurin begin"
private const val BLOCK_END = "# pagurin end"
const val SYSTEM_DRIVER = "@system"   // a game's driver: the device's, even where its port brought its own
private val ADRENOTOOLS = listOf("libadrenotools.so", "libhook_impl.so", "libmain_hook.so", "libfile_redirect_hook.so", "libgsl_alloc_hook.so")

class Store(private val context: Context) {
    private val files: File = context.getExternalFilesDir(null) ?: context.filesDir
    private val gamesDir = File(files, "games")
    val driversDir = File(files, "drivers").apply { mkdirs() }
    private val runningFile = File(context.filesDir, "running.txt")

    // ---- games ----
    // A game folder is a game of the first kind whose manifest it holds
    fun games(): List<Game> = gamesDir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull { dir ->
        val adapter = ADAPTERS.firstOrNull { File(dir, it.manifest).isFile } ?: return@mapNotNull null
        runCatching {
            val j = JSONObject(File(dir, adapter.manifest).readText())
            val p = j.optJSONObject("pagurin"); val f = p?.optJSONArray("features")
            Game(dir.name, j.optString("name", dir.name), j.optString("display"), adapter.badge(j), j.optLong("bytes"), dir, gameJson(dir).optLong("lastPlayed"),
                p?.optInt("format") ?: 0, f?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }, adapter, hostKnows(dir, adapter), adapter.gameSettings(j)).also { settle(it) }
        }.getOrNull()
    }.sortedBy { it.name }

    // Removes a game: its folder leaves the library at once, by a rename, and is deleted behind it (hundreds of thousands of files take minutes; what an ended app
    // left is finished at the next start). keepSave: the save stays in internal storage, so the game goes on from it when it is added again
    fun removeGame(game: Game, keepSave: Boolean) {
        val gone = File(gamesDir, ".removing-${game.id}-${System.currentTimeMillis()}")
        check(game.dir.renameTo(gone)) { "cannot move ${game.dir}" }
        val inner = File(context.filesDir, "games/${game.id}")
        if (keepSave) inner.listFiles().orEmpty().filter { !it.name.startsWith("save") }.forEach { it.deleteRecursively() } else inner.deleteRecursively()
        pinFile(game).delete(); File(context.filesDir, "hostknows/${game.id}.json").delete()
        thread { gone.deleteRecursively() }
    }

    private fun finishRemoving() = thread { gamesDir.listFiles().orEmpty().filter { it.name.startsWith(".removing-") }.forEach { it.deleteRecursively() } }
    init { finishRemoving() }

    // What the game's host reads, found in its library once and kept in internal storage until the library changes (a new import, a newer port)
    private fun hostKnows(dir: File, adapter: Adapter): Set<String>? {
        val lib = File(dir, "host/libdmhost.so").takeIf { it.isFile } ?: return null
        val cache = File(context.filesDir, "hostknows/${dir.name}.json"); val stamp = "${lib.length()}:${lib.lastModified()}:${adapter.kind}:${adapter.settings.size}"
        runCatching { JSONObject(cache.readText()) }.getOrNull()?.takeIf { it.optString("stamp") == stamp }?.optJSONArray("keys")?.let { a -> return (0 until a.length()).map { a.getString(it) }.toSet() }
        val keys = runCatching { adapter.hostKnows(lib.readBytes()) }.getOrNull() ?: return null
        runCatching { cache.parentFile?.mkdirs(); cache.writeText(JSONObject().put("stamp", stamp).put("keys", org.json.JSONArray(keys.sorted())).toString()) }
        return keys
    }

    fun icon(game: Game): Bitmap? = runCatching {
        ZipFile(File(game.dir, "game.apk")).use { z -> z.getEntry("res/drawable-nodpi-v4/icon.png")?.let { e -> z.getInputStream(e).use(BitmapFactory::decodeStream) } }
    }.getOrNull()

    fun freeBytes(): Long = StatFs(files.path).availableBytes

    fun logTail(game: Game, lines: Int = 200): String = runCatching { File(game.dir, "log.txt").readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")

    // ---- settings: each setting, and the GPU driver, holds a value of its own for each game, in the game's pagurin.json. There is no default behind them: what a game
    // runs with is what its settings tab shows. A game's values are set when Pagurin first lists it, to what it would have run with then (what the pause menu last
    // saved, else the port's own, else the host's), so adding a game changes nothing. The pause menu can still change a settings.txt row in play: that change is taken
    // into the game's values before its next start, rather than lost ----
    private fun gameJson(dir: File) = runCatching { JSONObject(File(dir, "pagurin.json").readText()) }.getOrDefault(JSONObject())
    private fun writeJson(f: File, j: JSONObject) = replace(f, j.toString(2))

    fun values(game: Game): Map<String, String> { val v = gameJson(game.dir).optJSONObject("values") ?: return emptyMap(); return v.keys().asSequence().associateWith { v.getString(it) } }

    fun setValue(game: Game, key: String, value: String) {
        val j = gameJson(game.dir); j.put("values", (j.optJSONObject("values") ?: JSONObject()).put(key, value)); writeJson(File(game.dir, "pagurin.json"), j)
    }

    fun driver(game: Game): String = gameJson(game.dir).optString("driver")   // "" the game's own, SYSTEM_DRIVER the device's, else a package of drivers/

    fun setDriver(game: Game, name: String) { val j = gameJson(game.dir).put("driver", name); writeJson(File(game.dir, "pagurin.json"), j) }

    private fun rows(f: File) = runCatching { f.readLines() }.getOrDefault(emptyList()).filter { '=' in it && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun portEnv(game: Game): Map<String, String> {
        var inside = false
        return runCatching { File(game.dir, "env.txt").readLines() }.getOrDefault(emptyList()).filter { ln ->
            if (ln == BLOCK_START) inside = true else if (ln == BLOCK_END) inside = false
            !inside && ln != BLOCK_END && '=' in ln && !ln.startsWith("#")
        }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    // What the game would run with if Pagurin set nothing: what the pause menu saved (settings.txt), the port's own (settings.default.txt, env.txt), else the host's
    private fun runsWith(game: Game, s: Setting): String =
        (if (s.inEnv) portEnv(game)[s.key] else rows(File(game.dir, "settings.txt"))[s.key] ?: rows(File(game.dir, "settings.default.txt"))[s.key]) ?: game.adapter.hostDefault(s.key)

    // Gives a game a value for every setting it lacks one for, and takes in what the pause menu changed since Pagurin last wrote settings.txt ("applied")
    private fun settle(game: Game) {
        val j = gameJson(game.dir); val v = j.optJSONObject("values") ?: JSONObject(); var changed = false
        val applied = j.optJSONObject("applied")
        if (applied != null) {
            val now = rows(File(game.dir, "settings.txt"))
            for (k in applied.keys().asSequence().toList()) {
                val n = now[k] ?: continue
                if (n == applied.optString(k)) continue
                if (k != "driver") v.put(k, n)   // (the host read a driver row before it took one from env.txt)
                applied.put(k, n); changed = true
            }
        }
        for (s in game.settings) if (!v.has(s.key)) { v.put(s.key, if (s.key == game.adapter.languageKey && gameLanguage().isNotEmpty()) pickLanguage(s, gameLanguage()) else runsWith(game, s)); changed = true }
        if (changed) { j.put("values", v); writeJson(File(game.dir, "pagurin.json"), j) }
    }

    // ---- the game language: one for every game, as a Switch has one system language. Each game that has a language setting gets it, else English, else its
    // first language (a game picks among its own, as a Switch does); setting it sets every such game again, its own choice too. "" until it is chosen ----
    private val prefs = context.getSharedPreferences("pagurin", Context.MODE_PRIVATE)   // Language's file
    fun gameLanguage(): String = prefs.getString("game_language", "").orEmpty()

    fun setGameLanguage(code: String) {
        prefs.edit().putString("game_language", code).apply()
        for (g in games()) g.settings.firstOrNull { it.key == g.adapter.languageKey }?.let { setValue(g, it.key, pickLanguage(it, code)) }
    }


    // ---- drivers: packages the person added, each game's choice among them, its own (""), or the device's (SYSTEM_DRIVER) ----
    fun drivers(): List<Driver> = driversDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".zip") && !it.name.startsWith(".") }
        .map { Driver(it.name.removeSuffix(".zip"), it.length()) }.sortedBy { it.name.lowercase() }

    // A game that used the driver goes back to its own
    fun deleteDriver(name: String) {
        File(driversDir, "$name.zip").delete()
        games().filter { driver(it) == name }.forEach { setDriver(it, "") }
    }

    // ---- the game's code: host/*.so and mods/ run with the app's rights, from shared storage where another app or adb can write. The host goes to internal storage, which
    // only this app writes, from the same bytes its digest is taken of; mods/ stays where the host reads it and is checked just before the start. The digest last trusted
    // is kept in internal storage: a game's first start trusts it, a later change (a new import, or a file someone else wrote) is asked about ----
    class Staged(val digest: String, val trusted: String?, internal val dir: File) { val changed get() = trusted != null && trusted != digest }

    private fun pinFile(game: Game) = File(context.filesDir, "pins/${game.id}")

    // Copies host/ to internal storage beside the host in use (host.new), and digests it with mods/
    fun stageCode(game: Game): Staged {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        fun add(rel: String, bytes: ByteArray) { sha.update(rel.toByteArray()); sha.update(0); sha.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array()); sha.update(bytes) }
        val staged = File(context.filesDir, "games/${game.id}/host.new").apply { deleteRecursively(); mkdirs() }
        val libs = File(game.dir, "host").listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".so") }.sortedBy { it.name }
        check(libs.any { it.name == "libdmhost.so" }) { context.getString(R.string.error_no_host) }
        for (f in libs) { val b = f.readBytes(); add("host/${f.name}", b); File(staged, f.name).writeBytes(b) }
        val mods = File(game.dir, "mods")
        mods.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(mods).invariantSeparatorsPath }.forEach { add("mods/${it.relativeTo(mods).invariantSeparatorsPath}", it.readBytes()) }
        val digest = sha.digest().joinToString("") { "%02x".format(it) }
        return Staged(digest, runCatching { pinFile(game).readText().trim() }.getOrNull(), staged)
    }

    // The staged host becomes the one the stub loads, read-only, and its digest the trusted one
    fun commitCode(game: Game, s: Staged) {
        val host = File(context.filesDir, "games/${game.id}/host")
        host.deleteRecursively()
        check(s.dir.renameTo(host)) { "cannot install $host" }
        host.listFiles().orEmpty().forEach { it.setWritable(false, false) }
        pinFile(game).apply { parentFile?.mkdirs() }.writeText(s.digest)
    }

    fun dropCode(s: Staged) { s.dir.deleteRecursively() }

    // ---- starting a game ----
    private fun gameIntent(id: String) = Intent().setClassName(context, "android.app.NativeActivity").putExtra("game_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun gameProcess() = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses.orEmpty().firstOrNull { it.processName == "${context.packageName}:game" }

    fun runningId(): String? = if (gameProcess() != null) runCatching { runningFile.readText().trim() }.getOrNull() else null

    // s: the game's code, staged and checked (stageCode), which becomes the code in use
    fun play(game: Game, s: Staged) {
        commitCode(game, s)
        applySettings(game)
        val j = gameJson(game.dir).put("lastPlayed", System.currentTimeMillis()); writeJson(File(game.dir, "pagurin.json"), j)
        runningFile.writeText(game.id)
        context.startActivity(gameIntent(game.id))
    }

    fun resume(id: String) = context.startActivity(gameIntent(id))

    // The game's task finishing ends the host (front_android.c on_destroy: _exit); a process that outlives it is killed
    suspend fun stopGame() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.filter { it.taskInfo.baseIntent.component?.className == "android.app.NativeActivity" }.forEach { it.finishAndRemoveTask() }
        repeat(15) { if (gameProcess() == null) return; kotlinx.coroutines.delay(100) }
        gameProcess()?.let { Process.killProcess(it.pid) }
        repeat(15) { if (gameProcess() == null) return; kotlinx.coroutines.delay(100) }
    }

    // The game's values, written where the host reads them, at every start: over what the pause menu saved, and over a port's new env.txt. What settings.txt then
    // holds is kept as "applied", so a change the pause menu makes in play is told apart and taken in (settle)
    private fun applySettings(game: Game) {
        settle(game)
        val chosen = values(game)
        val sets = game.settings.filter { !it.inEnv && it.key in chosen }.associate { it.key to chosen.getValue(it.key) }
        val j = gameJson(game.dir); j.put("applied", JSONObject(sets)); writeJson(File(game.dir, "pagurin.json"), j)
        val settings = File(game.dir, "settings.txt")
        val kept = runCatching { settings.readLines() }.getOrDefault(emptyList()).filter { ln -> !ln.startsWith("driver=") && sets.keys.none { ln.startsWith("$it=") } }
        replace(settings, (kept + sets.map { "${it.key}=${it.value}" }).joinToString("\n", postfix = "\n"))

        val env = File(game.dir, "env.txt"); val out = mutableListOf<String>(); var inside = false
        for (ln in runCatching { env.readLines() }.getOrDefault(emptyList())) {
            if (ln == BLOCK_START) inside = true else if (ln == BLOCK_END) inside = false else if (!inside) out += ln
        }
        val rows = game.settings.filter { it.inEnv && it.key in chosen }.map { "${it.key}=${chosen.getValue(it.key)}" } + listOfNotNull(driverRow(game))
        if (rows.isNotEmpty()) out += listOf(BLOCK_START) + rows + BLOCK_END   // last: the app reads env.txt in order, so these win over the profile's
        replace(env, out.joinToString("\n", postfix = "\n"))
    }

    // The env.txt row for the game's driver, null to leave the port's own as it is. The host loads a driver by name from its own folder, through libadrenotools and its
    // hooks beside it (nx2android gfx_vk_core.c vk_init), and an empty name is the device's driver. A port made with a driver brought those libraries; for any other
    // they come from this app, which builds them (stub/CMakeLists.txt). The host folder was made anew for this start (commitCode)
    private fun driverRow(game: Game): String? {
        if (!game.takesDriver) return null
        val name = driver(game)
        if (name == SYSTEM_DRIVER) return "DM_VK_DRIVER="
        val zip = File(driversDir, "$name.zip").takeIf { name.isNotEmpty() && it.isFile } ?: return null
        val host = File(context.filesDir, "games/${game.id}/host")
        for (lib in ADRENOTOOLS) File(host, lib).takeIf { !it.exists() }?.let { File(context.applicationInfo.nativeLibraryDir, lib).copyTo(it); it.setWritable(false, false) }
        return "DM_VK_DRIVER=" + ZipFile(zip).use { z ->
            val libs = z.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".so") }.toList()
            check(libs.isNotEmpty()) { context.getString(R.string.picker_not_driver) }
            for (e in libs) File(host, e.name.substringAfterLast('/')).let { f -> f.delete(); z.getInputStream(e).use { i -> f.outputStream().use { i.copyTo(it) } }; f.setWritable(false, false) }
            val named = z.getEntry("meta.json")?.let { m -> runCatching { JSONObject(z.getInputStream(m).use { it.readBytes().decodeToString() }).optString("libraryName") }.getOrNull() }
            named?.takeIf { n -> libs.any { it.name.substringAfterLast('/') == n } } ?: libs.first().name.substringAfterLast('/')
        }
    }

    // ---- saves: the host's save manager, asked through a file (nx2android: saves_android.c, porter/saves.py) ----
    // A save brought from an app by an earlier version kept the app's private modes, which the host, as this app, cannot read: opened up here, on paths of its own only
    private val takenSave = Regex("from-app-\\d{8}-\\d{6}(\\.device)?")
    private fun repairTakenSaves(game: Game) {
        val bad = File(game.dir, "saves").listFiles().orEmpty().filter { it.name.matches(takenSave) && it.listFiles() == null }
        if (bad.isNotEmpty() && AynRoot.available()) runCatching { rootScript(bad.joinToString("\n") { "chmod -R 777 '${it.path}'" }) }
    }

    fun saves(game: Game): List<SaveFolder> = repairTakenSaves(game).let { File(game.dir, "saves") }.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") && !it.name.endsWith(".device") }
        .map { d -> SaveFolder(d.name, d.walkTopDown().filter { it.isFile }.sumOf { it.length() }, d.lastModified()) }.sortedByDescending { it.time }

    // ---- a save from elsewhere: a folder another app keeps its saves in (an emulator's), or a zip. Somewhere in it a folder named after the game holds the save; one
    // whose parent is the all-zero user is the device save. With no such folder, the whole thing is the save. It becomes a folder of saves/, which the import takes ----
    private class Node(val name: String, val isDir: Boolean, val mtime: Long, val open: () -> java.io.InputStream, val children: () -> List<Node>)

    private fun treeNode(tree: Uri, id: String, name: String, isDir: Boolean, mtime: Long): Node = Node(name, isDir, mtime,
        { checkNotNull(context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))) { name } },
        {
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), cols, null, null, null)?.use { c ->
                generateSequence { if (c.moveToNext()) treeNode(tree, c.getString(0), c.getString(1), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, c.getLong(3)) else null }.toList()
            }.orEmpty()
        })

    private fun zipNode(zip: ZipFile): Node {
        class Dir(val name: String) { val dirs = linkedMapOf<String, Dir>(); val files = mutableListOf<java.util.zip.ZipEntry>() }
        val root = Dir("")
        for (e in zip.entries()) {
            val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            check(parts.none { it == ".." }) { e.name }   // a path out of the folder it is copied to
            if (parts.isEmpty()) continue
            val d = parts.dropLast(if (e.isDirectory) 0 else 1).fold(root) { d, p -> d.dirs.getOrPut(p) { Dir(p) } }
            if (!e.isDirectory) d.files += e
        }
        fun node(d: Dir): Node = Node(d.name, true, 0, { error(d.name) }, { d.dirs.values.map(::node) + d.files.map { e -> Node(e.name.substringAfterLast('/'), false, e.time, { zip.getInputStream(e) }, { emptyList() }) } })
        return node(root)
    }

    private fun addSave(game: Game, root: Node): String {
        var user: Node? = null; var device: Node? = null
        fun find(n: Node, depth: Int) {
            for (c in n.children().filter { it.isDir }) {
                if (c.name.equals(game.id, ignoreCase = true)) {
                    if (n.name.length >= 16 && n.name.all { it == '0' }) device = c else if (user == null || c.mtime > user!!.mtime) user = c   // several users: the latest save
                } else if (depth < 6) find(c, depth + 1)
            }
        }
        find(root, 0)
        val from = user ?: root.takeIf { device == null } ?: error(context.getString(R.string.no_save_found))
        val dir = File(game.dir, "saves").apply { mkdirs() }
        val name = "imported-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val part = File(dir, ".$name.part"); val partDevice = File(dir, ".$name.part.device")
        fun copy(n: Node, to: File): Int = n.children().sumOf { c ->
            check(c.name.isNotEmpty() && c.name != "." && c.name != ".." && '/' !in c.name) { c.name }
            if (c.isDir) copy(c, File(to, c.name)) else { to.mkdirs(); c.open().use { i -> File(to, c.name).outputStream().use { i.copyTo(it) } }; 1 }
        }
        try {
            check(copy(from, part) > 0) { context.getString(R.string.no_save_found) }
            device?.let { copy(it, partDevice) }
            check(part.renameTo(File(dir, name)) && (!partDevice.exists() || partDevice.renameTo(File(dir, "$name.device")))) { "cannot write $dir" }
        } finally { part.deleteRecursively(); partDevice.deleteRecursively() }
        return name
    }

    fun addSaveFromFolder(game: Game, tree: Uri): String = addSave(game, treeNode(tree, DocumentsContract.getTreeDocumentId(tree), "", true, 0))

    fun addSaveFromZip(game: Game, uri: Uri): String {
        val tmp = File(context.cacheDir, "save.zip")
        try {
            checkNotNull(context.contentResolver.openInputStream(uri)) { context.getString(R.string.picker_read_failed) }.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            return ZipFile(tmp).use { addSave(game, zipNode(it)) }
        } finally { tmp.delete() }
    }

    // ---- ports installed as apps of their own (nx2android's), taken over: another app's folders are closed to this one, so it goes through AYN's root service (AynRoot)
    // or, elsewhere, Shizuku's shell (ShizukuShell). The game data is moved (a rename on the same storage: seconds, no space), the host and theme taken from its APK, and
    // the save copied from the app's internal storage (root), or exported by the app's own save manager first (the shell cannot read internal storage) ----
    data class InstalledPort(val pkg: String, val apk: String, val id: String, val name: String, val display: String, val bytes: Long, val inLibrary: Boolean)

    private val pkgOk = Regex("[A-Za-z0-9_.]{1,100}"); private val idOk = Regex("[0-9A-Fa-f]{16}")

    enum class Reach { ROOT, SHELL }
    fun reach(): Reach? = if (AynRoot.available()) Reach.ROOT else if (ShizukuShell.granted()) Reach.SHELL else null

    // A script with the rights reach() gives; it fails with its last line unless it ends well
    private fun privScript(body: String): String = when (reach()) {
        Reach.ROOT -> rootScript(body)
        Reach.SHELL -> {
            val out = ShizukuShell.exec(context, "set -e\n$body\necho PAGURIN_OK\n")?.lines()?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }
            check(out == "PAGURIN_OK") { out ?: "no answer" }; out
        }
        null -> error(context.getString(R.string.no_reach))
    }

    // A script for the root service, run with a short command; its last line comes back
    private fun rootScript(body: String): String {
        val f = File(files, ".root.sh"); val log = File(files, ".root.log")
        f.writeText("set -e\n$body\necho PAGURIN_OK\n")
        val out = AynRoot.exec("sh ${f.path} > ${log.path} 2>&1; tail -n 1 ${log.path}")?.trim()
        f.delete()
        check(out == "PAGURIN_OK") { (out ?: "no answer").ifEmpty { "no answer" } }
        log.delete()
        return out
    }

    fun installedPorts(): List<InstalledPort> {
        if (reach() == null) return emptyList()
        // the packs' manifests, one per line in a file this app reads; packages of apps installed now only
        val list = File(files, ".ports.txt")
        privScript("cd /sdcard/Android/data; for f in */files/nx2a-data.json; do p=\${f%%/*}; [ \"\$p\" = ${context.packageName} ] && continue; a=\$(pm path \"\$p\" 2>/dev/null | head -n 1); [ -n \"\$a\" ] || continue; echo \"\$p \${a#package:} \$(tr -d '\\r\\n' < \"\$f\")\"; done > ${list.path}")
        val have = games().map { it.id.uppercase() }.toSet()
        return list.readLines().also { list.delete() }.mapNotNull { ln ->
            val (pkg, apk, json) = ln.split(' ', limit = 3).takeIf { it.size == 3 } ?: return@mapNotNull null
            val j = runCatching { JSONObject(json) }.getOrNull() ?: return@mapNotNull null
            val id = j.optString("title_id")
            if (!pkg.matches(pkgOk) || !id.matches(idOk) || !apk.endsWith(".apk")) null else InstalledPort(pkg, apk, id.uppercase(), j.optString("name", pkg), j.optString("display"), j.optLong("bytes"), id.uppercase() in have)
        }.sortedBy { it.name }
    }

    // Returns the name of the save folder it brought, for the import, or null when the app had no save yet
    fun takeOver(port: InstalledPort, step: (String) -> Unit): String? {
        val dest = File(gamesDir, port.id); val src = "/sdcard/Android/data/${port.pkg}/files"
        step(context.getString(R.string.takeover_host))
        val apk = port.apk   // an app's APK is readable by any app that knows its path, which the root service gave
        dest.mkdirs()
        val host = File(dest, "host").apply { deleteRecursively(); mkdirs() }
        var hostBytes: ByteArray? = null
        ZipFile(apk).use { z -> z.entries().asSequence().filter { it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }.forEach { e ->
            val b = z.getInputStream(e).use { it.readBytes() }; File(host, e.name.substringAfterLast('/')).writeBytes(b); if (e.name.endsWith("/libdmhost.so")) hostBytes = b } }
        check(hostBytes != null) { context.getString(R.string.error_no_host) }
        File(apk).copyTo(File(dest, "game.apk"), overwrite = true)
        val reach = checkNotNull(reach()) { context.getString(R.string.no_reach) }
        fun has(vararg marks: String) = marks.all { m -> indexOf(hostBytes!!, m.toByteArray()) >= 0 }

        // The shell's way to the save: the app exports it into its saves/ folder (nx2android's saves_android.c), which then moves with the rest. Before anything
        // moves: a save that cannot come along stops it here, while the app still has everything
        var exported: String? = null
        if (reach == Reach.SHELL) {
            step(context.getString(R.string.takeover_export))
            check(has("import.txt", "export.txt", "result.txt")) { context.getString(R.string.takeover_no_save_manager) }
            val token = SecureRandom().nextLong().toULong().toString(16); val answer = File(files, ".export-result.txt").apply { delete() }
            privScript("""
                S=$src
                am force-stop ${port.pkg}
                mkdir -p ${'$'}S/saves; rm -f ${'$'}S/saves/result.txt
                printf 'export\nrequest=%s\nquit\n' $token > ${'$'}S/saves/export.txt
                am start -n ${port.pkg}/android.app.NativeActivity > /dev/null
                i=0; until grep -q "request=$token" ${'$'}S/saves/result.txt 2>/dev/null; do i=${'$'}((i+1)); [ ${'$'}i -le 120 ] || { echo "the app did not answer"; exit 1; }; sleep 0.5; done
                am force-stop ${port.pkg}
                cp ${'$'}S/saves/result.txt ${answer.path}
            """.trimIndent())
            val kv = answer.readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }; answer.delete()
            if (kv["result"] == "ok") exported = kv["name"]
            else check(kv["error"] == "the game has not saved yet") { context.getString(R.string.takeover_export_failed, kv["error"] ?: "") }   // no save: nothing to bring
        }

        step(context.getString(R.string.takeover_move))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()); val save = exported ?: "from-app-$stamp"
        val copyInternal = if (reach == Reach.ROOT) "true" else "false"   // the shell cannot read V, and has the export instead
        privScript("""
            am force-stop ${port.pkg}
            S=$src; D=${dest.path}; V=/data/data/${port.pkg}/files
            [ -f ${'$'}S/nx2a-data.json ] || { echo "no data pack in ${'$'}S"; exit 1; }
            # what Pagurin's copy of the game had, but its exports, its choices and what was just taken from the APK
            for x in ${'$'}D/*; do case ${'$'}{x##*/} in saves|pagurin.json|host|game.apk) ;; *) rm -rf "${'$'}x" ;; esac; done
            mkdir -p ${'$'}D/saves ${driversDir.path}
            for x in ${'$'}S/*; do case ${'$'}{x##*/} in saves) ;; drivers) ;; *) mv "${'$'}x" ${'$'}D/ ;; esac; done
            for z in ${'$'}S/drivers/*.zip; do [ -f "${'$'}z" ] && [ ! -e ${driversDir.path}/${'$'}{z##*/} ] && mv "${'$'}z" ${driversDir.path}/; done; true
            for x in ${'$'}S/saves/*; do [ -d "${'$'}x" ] && [ ! -e ${'$'}D/saves/${'$'}{x##*/} ] && mv "${'$'}x" ${'$'}D/saves/; done; true
            if $copyInternal && [ -d ${'$'}V/save ] && [ -n "${'$'}(ls -A ${'$'}V/save)" ]; then
              cp -r ${'$'}V/save ${'$'}D/saves/.$save.part
              [ -d ${'$'}V/save_device ] && cp -r ${'$'}V/save_device ${'$'}D/saves/.$save.part.device
              chmod -R 777 ${'$'}D/saves/.$save.part*   # cp keeps the app's private modes (700, root's now): the host, as this app, could not read them
              [ -d ${'$'}D/saves/.$save.part.device ] && mv ${'$'}D/saves/.$save.part.device ${'$'}D/saves/$save.device
              mv ${'$'}D/saves/.$save.part ${'$'}D/saves/$save
            fi
        """.trimIndent())

        step(context.getString(R.string.takeover_finish))
        val remote = dest.path
        val env = File(dest, "env.txt")
        if (env.isFile) replace(env, env.readText().replace("/sdcard/Android/data/${port.pkg}/files", remote).replace("/storage/emulated/0/Android/data/${port.pkg}/files", remote))
        val rows = runCatching { File(dest, "settings.txt").readLines() }.getOrDefault(emptyList()).filter { '=' in it && !it.startsWith("ui=") && !it.startsWith("driver=") }
        replace(File(dest, "settings.default.txt"), (rows + listOfNotNull("docked=1".takeIf { rows.none { it.startsWith("docked=") } })).joinToString("\n", postfix = "\n"))
        val man = JSONObject(File(dest, "nx2a-data.json").readText())
        val features = listOfNotNull("saves".takeIf { has("import.txt", "export.txt", "result.txt") }, "drivers".takeIf { has("DM_VK_DRIVER") })
        man.put("package", context.packageName).put("status", man.optString("status", "experimental").ifEmpty { "experimental" })
            .put("pagurin", JSONObject().put("format", SUPPORTED_FORMAT).put("features", org.json.JSONArray(features)).put("warnings", org.json.JSONArray()).put("from_app", port.pkg))
        replace(File(dest, "nx2a-data.json"), man.toString(2))
        // The app was this person's own install, and they asked for it: its code is trusted as it is now
        val g = games().first { it.id.equals(port.id, ignoreCase = true) }
        commitCode(g, stageCode(g))
        return save.takeIf { File(dest, "saves/$it").isDirectory }
    }

    fun uninstallApp(pkg: String) { check(pkg.matches(pkgOk)); privScript("pm uninstall $pkg") }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) { for (k in needle.indices) if (hay[i + k] != needle[k]) continue@outer; return i }
        return -1
    }

    fun deleteSave(game: Game, name: String): Boolean {
        val dir = File(game.dir, "saves")
        return File(dir, name).deleteRecursively() && File(dir, "$name.device").deleteRecursively()   // the device save beside it; a folder that is not there counts as deleted
    }

    // The whole log, to Download/Pagurin: a file manager can reach it there, and the app's own folder is closed to them. No picker, so a controller is enough, and no permission (MediaStore). Returns the file's name
    fun saveLog(game: Game): String {
        val log = File(game.dir, "log.txt"); check(log.isFile) { context.getString(R.string.error_no_log) }
        val name = game.name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_') + "-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "text/plain"); put(MediaStore.Downloads.RELATIVE_PATH, "Download/Pagurin") }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { context.getString(R.string.error_create_file) }
        try { checkNotNull(resolver.openOutputStream(uri)) { context.getString(R.string.error_open_file) }.use { out -> log.inputStream().use { it.copyTo(out) } } }
        catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        return name
    }

    // Returns the host's answer. The host handles the request at its start, then ends without starting the game
    private suspend fun request(game: Game, file: String, first: String): Map<String, String> {
        // The request starts the game's host too: only code already trusted runs this way, as no one is asked here
        val s = withContext(Dispatchers.IO) { stageCode(game) }
        if (s.changed || s.trusted == null) { dropCode(s); return mapOf("result" to "error", "error" to context.getString(R.string.code_not_trusted)) }
        withContext(Dispatchers.IO) { commitCode(game, s) }
        val dir = File(game.dir, "saves").apply { mkdirs() }; val token = SecureRandom().nextLong().toULong().toString(16)
        replace(File(dir, file), "$first\nrequest=$token\nquit\n")
        runningFile.writeText(game.id); context.startActivity(gameIntent(game.id))
        repeat(120) {
            val kv = runCatching { File(dir, "result.txt").readLines() }.getOrDefault(emptyList()).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            if (kv["request"] == token) return kv
            kotlinx.coroutines.delay(500)
        }
        File(dir, file).delete(); return mapOf("result" to "error", "error" to context.getString(R.string.no_answer))
    }

    suspend fun exportSave(game: Game) = request(game, "export.txt", "export")
    suspend fun importSave(game: Game, name: String) = request(game, "import.txt", name)

    // Whole or not at all. Pushed files are owned by the shell, so the old one is replaced, never written into
    private fun replace(f: File, text: String) { val t = File(f.parentFile, ".${f.name}.new"); t.writeText(text); check(t.renameTo(f) || (f.delete() && t.renameTo(f))) { "cannot write $f" } }
}
