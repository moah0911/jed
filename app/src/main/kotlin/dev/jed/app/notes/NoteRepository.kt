package dev.jed.app.notes

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One workspace root: the default local folder or a user-attached one. */
data class Workspace(val ref: String, val name: String, val saf: Boolean)

/**
 * Every note Jed knows, in the selected workspace root. Files are the truth:
 * plain `.md` files, trash mirrors the folders, saves are temp-plus-rename.
 * All calls are synchronous; callers run them off the main thread.
 */
class NoteRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("jed", Context.MODE_PRIVATE)

    fun workspaces(): List<Workspace> {
        val raw = prefs.getString(KEY_WS, null) ?: return listOf(defaultWorkspace().also { saveWorkspaces(listOf(it), it.ref) })
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Workspace(o.optString("ref"), o.optString("name").ifEmpty { "Notes" }, o.optBoolean("saf"))
            }.ifEmpty { listOf(defaultWorkspace()) }
        }.getOrElse { listOf(defaultWorkspace()) }
    }

    fun selectedRef(): String = prefs.getString(KEY_SEL, null) ?: workspaces().first().ref

    fun selected(): Workspace = workspaces().firstOrNull { it.ref == selectedRef() } ?: workspaces().first()

    fun select(ref: String) {
        prefs.edit().putString(KEY_SEL, ref).apply()
    }

    fun attachSaf(treeUri: Uri, name: String): Workspace {
        val ws = Workspace(treeUri.toString(), name.ifEmpty { "Folder" }, saf = true)
        val all = workspaces().filter { it.ref != ws.ref } + ws
        saveWorkspaces(all, ws.ref)
        return ws
    }

    fun detach(ref: String): Boolean {
        val all = workspaces()
        if (all.size <= 1 || all.firstOrNull { it.ref == ref }?.saf != true) return false
        saveWorkspaces(all.filter { it.ref != ref }, all.first { it.ref != ref }.ref)
        return true
    }

    fun backend(ws: Workspace = selected()): NoteBackend =
        if (ws.saf) SafBackend(context, Uri.parse(ws.ref))
        else FileBackend(File(ws.ref))

    /** Default workspace: external files dir (visible to file managers, no permission needed). */
    fun defaultDir(): File {
        val ext = context.getExternalFilesDir(null) ?: context.filesDir
        return File(ext, "notes")
    }

    private fun defaultWorkspace(): Workspace =
        Workspace(defaultDir().absolutePath, "Notes", saf = false)

    private fun saveWorkspaces(all: List<Workspace>, sel: String) {
        val arr = JSONArray()
        all.forEach { arr.put(JSONObject().put("ref", it.ref).put("name", it.name).put("saf", it.saf)) }
        prefs.edit().putString(KEY_WS, arr.toString()).putString(KEY_SEL, sel).apply()
    }

    // --- reads ---------------------------------------------------------------

    fun notes(ws: Workspace = selected()): List<NoteMeta> {
        val b = backend(ws)
        return b.listEntries().mapNotNull { e ->
            val text = b.read(e.id) ?: return@mapNotNull null
            NoteMeta(e.id, NoteFiles.titleOf(text), e.folder, e.mtime, NoteFiles.tagsOf(text))
        }.sortedByDescending { it.mtime }
    }

    fun trash(ws: Workspace = selected()): List<NoteMeta> {
        val b = backend(ws)
        if (b is FileBackend) {
            val root = File(ws.ref)
            val trash = File(root, NoteFiles.TRASH_DIR)
            if (!trash.isDirectory) return emptyList()
            return trash.walkTopDown().filter { it.isFile && it.extension == "md" }.map {
                val rel = it.relativeTo(trash).path.replace(File.separatorChar, '/')
                val text = it.readText()
                NoteMeta("${NoteFiles.TRASH_DIR}/$rel", NoteFiles.titleOf(text), it.parentFile?.let { p ->
                    runCatching { p.relativeTo(trash).path }.getOrDefault("").replace(File.separatorChar, '/')
                }.orEmpty(), it.lastModified())
            }.sortedByDescending { it.mtime }.toList()
        }
        return emptyList() // SAF trash: delete is permanent, stated in the UI.
    }

    fun read(id: String, ws: Workspace = selected()): String? = backend(ws).read(id)

    fun mtime(id: String, ws: Workspace = selected()): Long = backend(ws).mtime(id)

    fun resolveTitle(title: String, ws: Workspace = selected()): NoteMeta? =
        NoteFiles.resolveTitle(title, notes(ws))

    fun search(query: String, folder: String = "", ws: Workspace = selected()): List<NoteMeta> {
        val all = notes(ws).filter { NoteFiles.inFolder(it, folder) }
        val b = backend(ws)
        val bodies = all.associate { it.id to (b.read(it.id).orEmpty()) }
        return NoteFiles.search(all, bodies, query)
    }

    // --- writes --------------------------------------------------------------

    sealed interface SaveResult {
        data object Saved : SaveResult
        /** The disk had diverged; its version was stashed in trash, buffer won. */
        data class ConflictStashed(val trashId: String) : SaveResult
        data class Failed(val why: String) : SaveResult
    }

    /**
     * Save with the external-edit guard: when the disk version moved under a
     * dirty buffer and the bytes genuinely differ, the disk version is moved
     * to trash first, never overwritten in place.
     */
    fun save(id: String, text: String, baseMtimeMs: Long?, ws: Workspace = selected()): SaveResult {
        val b = backend(ws)
        if (baseMtimeMs != null) {
            val disk = b.read(id)
            val diskMtime = b.mtime(id)
            if (disk != null && diskMtime != baseMtimeMs && disk != text) {
                val trashId = "${NoteFiles.TRASH_DIR}/conflict-${System.currentTimeMillis()}-" + id.substringAfterLast('/')
                b.write(trashId, disk)
                return if (b.write(id, text)) SaveResult.ConflictStashed(trashId)
                else SaveResult.Failed("Could not write the note.")
            }
        }
        return if (b.write(id, text)) SaveResult.Saved else SaveResult.Failed("Could not write the note.")
    }

    fun create(folder: String, title: String, body: String, ws: Workspace = selected()): NoteMeta? {
        if (NoteFiles.folderProblem(folder) != null) return null
        val b = backend(ws)
        if (!b.mkdirs(folder)) return null
        val taken = b.listEntries().map { it.id.substringAfterLast('/') }.toSet()
        val filename = NoteFiles.uniqueName(NoteFiles.slugOf(title.ifEmpty { "Untitled" }), taken)
        val text = "# $title\n\n$body"
        val id = b.create(folder, filename, text.trimStart('\n')) ?: return null
        return NoteMeta(id, title.ifEmpty { "Untitled" }, folder, System.currentTimeMillis())
    }

    fun delete(id: String, ws: Workspace = selected()): Boolean {
        val b = backend(ws)
        val text = b.read(id) ?: return false
        if (b is SafBackend) return b.delete(id) // permanent; the confirm says so.
        val trashId = "${NoteFiles.TRASH_DIR}/$id"
        return b.write(trashId, text) && b.delete(id)
    }

    fun restore(trashId: String, ws: Workspace = selected()): String? {
        val b = backend(ws)
        if (!trashId.startsWith(NoteFiles.TRASH_DIR + "/")) return null
        val dest = trashId.removePrefix(NoteFiles.TRASH_DIR + "/")
        val text = b.read(trashId) ?: return null
        val taken = b.listEntries().map { it.id.substringAfterLast('/') }.toSet()
        var name = dest.substringAfterLast('/')
        if (name.lowercase() in taken.map { it.lowercase() }.toSet()) {
            name = NoteFiles.uniqueName(name.removeSuffix(NoteFiles.EXT), taken)
        }
        val folder = dest.substringBeforeLast('/', "")
        val finalId = if (folder.isEmpty()) name else "$folder/$name"
        b.mkdirs(folder)
        return if (b.write(finalId, text) && b.delete(trashId)) finalId else null
    }

    fun purge(trashId: String, ws: Workspace = selected()): Boolean = backend(ws).delete(trashId)

    fun emptyTrash(ws: Workspace = selected()): Int {
        var n = 0
        for (t in trash(ws)) if (purge(t.id, ws)) n += 1
        return n
    }

    // --- daily notes and templates -------------------------------------------

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun dailyNote(ws: Workspace = selected()): NoteMeta? {
        val date = today()
        notes(ws).firstOrNull { it.title == date }?.let { return it }
        val tmpl = notes(ws).firstOrNull { it.title.equals("Daily Template", ignoreCase = true) }
            ?: notes(ws).firstOrNull { it.tags.contains("daily") }
        val body = if (tmpl != null) {
            instantiate(read(tmpl.id, ws).orEmpty(), date)
        } else "# $date\n"
        val id = backend(ws).create("", NoteFiles.uniqueName(date, emptySet()), body) ?: return null
        return NoteMeta(id, date, "", System.currentTimeMillis())
    }

    fun templates(ws: Workspace = selected()): List<NoteMeta> =
        notes(ws).filter { NoteFiles.splitFrontmatter(read(it.id, ws).orEmpty()).first.template }

    /** Instantiate a template: token substitution, marker stripped. */
    fun instantiate(tmplText: String, title: String): String {
        val date = today()
        fun shift(days: Int): String {
            val c = java.util.Calendar.getInstance()
            c.add(java.util.Calendar.DAY_OF_YEAR, days)
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
        }
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        return tmplText.lines()
            .filterNot { it.trim() == "template: true" }
            .joinToString("\n")
            .replace("{{title}}", title)
            .replace("{{date}}", date)
            .replace("{{time}}", time)
            .replace("{{yesterday}}", shift(-1))
            .replace("{{tomorrow}}", shift(1))
    }

    fun newFromTemplate(tmplId: String, title: String, ws: Workspace = selected()): NoteMeta? {
        val text = read(tmplId, ws) ?: return null
        val body = instantiate(text, title).lines().dropWhile { it.trim() == "---" }.joinToString("\n")
        val clean = body.lines().filterNot { it.trim() == "template: true" }.joinToString("\n")
        val taken = backend(ws).listEntries().map { it.id.substringAfterLast('/') }.toSet()
        val filename = NoteFiles.uniqueName(NoteFiles.slugOf(title.ifEmpty { "Untitled" }), taken)
        val id = backend(ws).create("", filename, "# $title\n" + clean.substringAfter("\n").ifEmpty { "\n" }) ?: return null
        return NoteMeta(id, title, "", System.currentTimeMillis())
    }

    // --- profiles (secrets outside the notes) ---------------------------------

    private fun profilesDir(): File = File(context.filesDir, "profiles").apply { mkdirs() }

    /** A profile is a dotenv file kept outside the notes folder. */
    fun loadProfile(name: String): Map<String, String> {
        if (!name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return emptyMap()
        val f = File(profilesDir(), "$name.env")
        if (!f.isFile) return emptyMap()
        return f.readLines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || "=" !in t) null
            else t.substringBefore("=").trim() to t.substringAfter("=").trim().trim('"')
        }.toMap()
    }

    companion object {
        private const val KEY_WS = "workspaces"
        private const val KEY_SEL = "selected"
    }
}
