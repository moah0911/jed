package dev.jed.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.jed.app.notes.NoteFiles
import dev.jed.app.notes.NoteMeta
import dev.jed.app.notes.NoteRepository
import dev.jed.app.notes.Workspace
import dev.jed.app.run.PythonRunner
import dev.jed.app.run.RunEvent
import dev.jed.app.run.RunSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class JedViewModelFactory(private val repo: NoteRepository, private val app: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = JedViewModel(repo, app.applicationContext) as T
}

/** One block's live run panel. */
data class RunPanel(
    val output: String = "",
    val running: Boolean = false,
    val exit: Int? = null,
    val note: String? = null,
)

sealed interface Screen {
    data object Notes : Screen
    data class Editor(val id: String) : Screen
    data object Trash : Screen
    data object Templates : Screen
}

/**
 * One editable run of the open note: prose lines, or one fence's code lines.
 * Uids are stable across keystrokes; only a fence-structure change reassigns
 * them, carrying run panels along by (language, code) match.
 */
data class SegState(val uid: Long, val fenceLang: String?, val fenceUnclosed: Boolean, val lines: List<String>) {
    val isFence: Boolean get() = fenceLang != null
    val text: String get() = lines.joinToString("\n")
}

class JedViewModel(val repo: NoteRepository, private val app: Context) : ViewModel() {

    private val prefs = app.getSharedPreferences("jed-ui", Context.MODE_PRIVATE)

    var screen: Screen by mutableStateOf(Screen.Notes)
        private set

    var workspaces: List<Workspace> by mutableStateOf(emptyList())
        private set
    var workspace: Workspace? by mutableStateOf(null)
        private set

    var notes: List<NoteMeta> by mutableStateOf(emptyList())
        private set
    var trash: List<NoteMeta> by mutableStateOf(emptyList())
        private set
    var folders: List<String> by mutableStateOf(emptyList())
        private set
    var templates: List<NoteMeta> by mutableStateOf(emptyList())
        private set

    var query: String by mutableStateOf("")
    var folder: String by mutableStateOf("")
    var hits: List<NoteMeta>? by mutableStateOf(null)
        private set

    var theme: String by mutableStateOf(prefs.getString("theme", "dark") ?: "dark")
        private set

    // Editor state for the open note.
    var segments: List<SegState> by mutableStateOf(emptyList())
        private set
    var openFolder: String by mutableStateOf("")
        private set
    var savedText: String by mutableStateOf("")
        private set
    var baseMtime: Long by mutableStateOf(0L)
        private set
    var preview: Boolean by mutableStateOf(false)
    var status: String by mutableStateOf("")
    var conflictNotice: String? by mutableStateOf(null)
        private set

    /** Which segment holds the caret, for the accessory bar. */
    var focusedUid: Long? by mutableStateOf(null)
        private set
    var focusedSel: TextRange? by mutableStateOf(null)
        private set

    var runs: Map<Long, RunPanel> by mutableStateOf(emptyMap())
        private set
    var runInput: Map<Long, String> by mutableStateOf(emptyMap())

    private val sessions = mutableMapOf<String, RunSession>()
    private var runJobs = mapOf<Long, Job>()
    private var nextUid = 1L

    init {
        refresh()
    }

    val fullText: String get() = NoteFiles.buildText(segments.map { NoteFiles.Segment(blockOf(it), it.lines) })
    val dirty: Boolean get() = fullText != savedText
    val anyRunning: Boolean get() = runs.values.any { it.running }

    private fun blockOf(s: SegState): NoteFiles.CodeBlock? =
        s.fenceLang?.let { NoteFiles.CodeBlock(it, s.text, -1, -1, -1, if (s.fenceUnclosed) null else -1) }

    fun updateTheme(t: String) {
        theme = t
        prefs.edit().putString("theme", t).apply()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = repo.workspaces()
            val sel = repo.selected()
            val ns = repo.notes(sel)
            val ts = repo.trash(sel)
            val tpl = repo.templates(sel)
            withContext(Dispatchers.Main) {
                workspaces = all
                workspace = sel
                notes = ns
                trash = ts
                templates = tpl
                folders = ns.map { it.folder }.distinct().sorted()
                if (folder.isNotEmpty() && folder !in folders) folder = ""
                if (query.isNotBlank()) {
                    val q = query
                    val f = folder
                    val list = ns
                    viewModelScope.launch(Dispatchers.IO) {
                        val scoped = list.filter { NoteFiles.inFolder(it, f) }
                        val bodies = scoped.associate { it.id to (repo.read(it.id).orEmpty()) }
                        val found = NoteFiles.search(scoped, bodies, q)
                        withContext(Dispatchers.Main) { hits = found }
                    }
                } else {
                    hits = null
                }
                if (screen is Screen.Editor && !dirty) {
                    // Follow clean external edits; hold dirty buffers for save.
                    val id = (screen as Screen.Editor).id
                    repo.read(id)?.let {
                        loadText(it, repo.mtime(id))
                    }
                }
            }
        }
    }

    // --- text / segments -------------------------------------------------------

    private fun freshSegments(text: String): List<SegState> =
        NoteFiles.segmentize(text).map { s ->
            SegState(nextUid++, s.fence?.lang, s.fence?.unclosed == true, s.lines)
        }

    private fun loadText(text: String, mtime: Long) {
        savedText = text
        baseMtime = mtime
        segments = freshSegments(text)
        runs = emptyMap()
        runInput = emptyMap()
    }

    /** Patch one segment's lines; resegment only on fence-structure change. */
    fun editSeg(uid: Long, newText: String) {
        val idx = segments.indexOfFirst { it.uid == uid }
        if (idx < 0) return
        val seg = segments[idx]
        val updated = segments.toMutableList()
        updated[idx] = seg.copy(lines = newText.split("\n"))
        segments = updated
        if (structureChanged()) resegment()
    }

    private fun structureChanged(): Boolean {
        val sig = NoteFiles.parseFences(fullText).map { Triple(it.startLine, it.lang, it.unclosed) }
        var at = 0
        val cur = mutableListOf<Triple<Int, String, Boolean>>()
        for (s in segments) {
            if (s.isFence) {
                cur += Triple(at, s.fenceLang ?: "", s.fenceUnclosed)
                at += s.lines.size + if (s.fenceUnclosed) 1 else 2
            } else {
                at += s.lines.size
            }
        }
        return sig != cur
    }

    /** Rebuild segments, keeping uids and run panels where content matches.
     * A structural edit cancels live runs: the code a run was started with
     * no longer exists as written. */
    private fun resegment() {
        for ((_, job) in runJobs) job.cancel()
        runJobs = emptyMap()
        val oldRuns = runs
        val oldSegs = segments
        val newSegs = freshSegments(fullText)
        // Match fences by (language, code):Typing around a run keeps its panel.
        val carried = mutableMapOf<Long, RunPanel>()
        val carriedInput = mutableMapOf<Long, String>()
        val used = HashSet<Long>()
        for (n in newSegs) {
            if (!n.isFence) continue
            val o = oldSegs.firstOrNull { it.isFence && it.uid !in used && it.fenceLang == n.fenceLang && it.text == n.text }
            if (o != null) {
                used += o.uid
                oldRuns[o.uid]?.let { panel ->
                    carried[n.uid] = if (panel.running) panel.copy(running = false, note = "Edited while running.") else panel
                }
                runInput[o.uid]?.let { carriedInput[n.uid] = it }
            }
        }
        runs = carried
        runInput = carriedInput
        segments = newSegs
    }

    fun noteFocus(uid: Long) {
        focusedUid = uid
    }

    fun noteSelection(sel: TextRange) {
        focusedSel = sel
    }

    // Accessory-bar edits act on the focused segment, else the last prose one.
    private fun targetSeg(): SegState =
        segments.firstOrNull { it.uid == focusedUid && !it.isFence }
            ?: segments.lastOrNull { !it.isFence }
            ?: SegState(nextUid++, null, false, emptyList()).also { segments = segments + it }

    fun insertText(s: String, sel: TextRange? = null) {
        val t = targetSeg()
        val cur = t.text
        val (a, b) = sel?.let { minOf(it.start, it.end) to maxOf(it.start, it.end) } ?: (cur.length to cur.length)
        val safeA = a.coerceIn(0, cur.length)
        val safeB = b.coerceIn(0, cur.length)
        editSeg(t.uid, cur.substring(0, safeA) + s + cur.substring(safeB))
    }

    fun wrapSelection(before: String, after: String, sel: TextRange? = null) {
        val t = targetSeg()
        val cur = t.text
        val (a, b) = sel?.let { minOf(it.start, it.end) to maxOf(it.start, it.end) } ?: (cur.length to cur.length)
        val safeA = a.coerceIn(0, cur.length)
        val safeB = b.coerceIn(0, cur.length)
        val inner = cur.substring(safeA, safeB).ifEmpty { "text" }
        editSeg(t.uid, cur.substring(0, safeA) + before + inner + after + cur.substring(safeB))
    }

    fun insertFence() {
        val t = targetSeg()
        val cur = t.text
        val at = cur.length
        val insert = "\n```sh\n\n```\n"
        editSeg(t.uid, cur.substring(0, at) + insert + cur.substring(at))
    }

    // --- navigation ----------------------------------------------------------

    fun openList() {
        if (screen is Screen.Editor && dirty) save()
        closeRuns()
        screen = Screen.Notes
        refresh()
    }

    fun openTrash() {
        screen = Screen.Trash
        refresh()
    }

    fun openTemplates() {
        screen = Screen.Templates
        refresh()
    }

    fun open(id: String) {
        if (screen is Screen.Editor && dirty) save()
        closeRuns()
        viewModelScope.launch(Dispatchers.IO) {
            val body = repo.read(id).orEmpty()
            val m = repo.mtime(id)
            val folderOf = if ('/' in id) id.substringBeforeLast('/') else ""
            withContext(Dispatchers.Main) {
                openFolder = folderOf
                preview = false
                status = ""
                conflictNotice = null
                focusedUid = null
                loadText(body, m)
                screen = Screen.Editor(id)
            }
        }
    }

    fun followWikilink(title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = repo.resolveTitle(title)
            if (target != null) withContext(Dispatchers.Main) { open(target.id) }
            else withContext(Dispatchers.Main) { status = "No note titled \"$title\"." }
        }
    }

    // --- editing -------------------------------------------------------------

    fun save() {
        val s = screen as? Screen.Editor ?: return
        val body = fullText
        viewModelScope.launch(Dispatchers.IO) {
            val r = repo.save(s.id, body, baseMtime)
            val m = repo.mtime(s.id)
            withContext(Dispatchers.Main) {
                when (r) {
                    is NoteRepository.SaveResult.Saved -> {
                        savedText = body
                        baseMtime = m
                        status = "Saved."
                    }
                    is NoteRepository.SaveResult.ConflictStashed ->
                        conflictNotice = "The file changed outside Jed; that version was kept in Trash and your text won."
                    is NoteRepository.SaveResult.Failed -> status = r.why
                }
                refresh()
            }
        }
    }

    fun dismissConflict() {
        conflictNotice = null
    }

    fun createNote(title: String, folder: String, body: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val meta = repo.create(folder, title.ifEmpty { "Untitled" }, body)
            withContext(Dispatchers.Main) {
                if (meta == null) status = "Could not create the note."
                else open(meta.id)
            }
        }
    }

    fun deleteCurrent() {
        val s = screen as? Screen.Editor ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repo.delete(s.id)
            withContext(Dispatchers.Main) {
                if (ok) openList() else status = "Could not delete the note."
            }
        }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.delete(id)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun daily() {
        viewModelScope.launch(Dispatchers.IO) {
            val meta = repo.dailyNote()
            withContext(Dispatchers.Main) {
                if (meta == null) status = "Could not open today's note."
                else open(meta.id)
            }
        }
    }

    fun fromTemplate(tmplId: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val meta = repo.newFromTemplate(tmplId, title.ifEmpty { "Untitled" })
            withContext(Dispatchers.Main) {
                if (meta == null) status = "Could not instantiate the template."
                else open(meta.id)
            }
        }
    }

    fun restore(trashId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = repo.restore(trashId)
            withContext(Dispatchers.Main) {
                if (id == null) status = "Could not restore the note."
                else refresh()
            }
        }
    }

    fun purge(trashId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.purge(trashId)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.emptyTrash()
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    // --- search --------------------------------------------------------------

    fun updateQuery(q: String) {
        query = q
        viewModelScope.launch(Dispatchers.IO) {
            val found = computeHits()
            withContext(Dispatchers.Main) { hits = found }
        }
    }

    fun updateFolder(f: String) {
        folder = f
        viewModelScope.launch(Dispatchers.IO) {
            val found = computeHits()
            withContext(Dispatchers.Main) { hits = found }
        }
    }

    private fun computeHits(): List<NoteMeta>? {
        if (query.isBlank()) return null
        val all = notes.filter { NoteFiles.inFolder(it, folder) }
        val bodies = all.associate { it.id to (repo.read(it.id).orEmpty()) }
        return NoteFiles.search(all, bodies, query)
    }

    // --- workspaces ----------------------------------------------------------

    fun selectWorkspace(ref: String) {
        repo.select(ref)
        query = ""
        folder = ""
        openList()
    }

    fun attachSaf(uri: Uri, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                withContext(Dispatchers.Main) { status = "Jed could not keep access to that folder." }
                return@launch
            }
            repo.attachSaf(uri, name.ifEmpty { "Folder" })
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    // --- images --------------------------------------------------------------

    /**
     * Insert a picked picture: bytes go to this root's `.jed-assets/` pool
     * (re-encoded here so EXIF, and the location in it, stays on the phone),
     * the note keeps only a relative reference.
     */
    fun insertPickedImage(uri: Uri) {
        val s = screen as? Screen.Editor ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val raw = runCatching {
                app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (raw == null) {
                withContext(Dispatchers.Main) { status = "The picture could not be read." }
                return@launch
            }
            val isPng = raw.size > 4 && raw[0] == 0x89.toByte() && raw[1] == 0x50.toByte() && raw[2] == 0x4E.toByte() && raw[3] == 0x47.toByte()
            val bytes = if (isPng) {
                raw
            } else {
                val bmp = runCatching {
                    android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, android.graphics.BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    })
                }.getOrNull() ?: run {
                    withContext(Dispatchers.Main) { status = "That file is not a picture Jed can use." }
                    return@launch
                }
                val out = java.io.ByteArrayOutputStream()
                if (!bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)) {
                    withContext(Dispatchers.Main) { status = "The picture could not be stored." }
                    return@launch
                }
                out.toByteArray()
            }
            insertImage(bytes, isPng)
        }
    }
    fun insertImage(bytes: ByteArray, mimePng: Boolean) {
        val s = screen as? Screen.Editor ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ws = repo.selected()
            val backend = repo.backend(ws)
            val data = bytes
            val ext = if (mimePng) ".png" else ".jpg"
            val taken = backend.listEntries().map { it.id.substringAfterLast('/') }.toSet()
            var name = "img${System.currentTimeMillis()}$ext"
            var n = 1
            while (name.lowercase() in taken.map { it.lowercase() }) {
                name = "img${System.currentTimeMillis()}-$n$ext"
                n += 1
            }
            val stored = backend.createBytes(NoteFiles.ASSETS_DIR, name, if (mimePng) "image/png" else "image/jpeg", data)
            withContext(Dispatchers.Main) {
                if (stored == null) {
                    status = "The picture could not be stored."
                    return@withContext
                }
                val depth = if (openFolder.isEmpty()) 0 else openFolder.split("/").size
                val ref = "${"../".repeat(depth)}${NoteFiles.ASSETS_DIR}/$name"
                insertText("![]($ref)")
                status = "Picture inserted."
            }
        }
    }

    // --- runs ----------------------------------------------------------------

    private fun runEnv(): Triple<File, Map<String, String>, String> {
        val ws = workspace
        val (fm, _) = NoteFiles.splitFrontmatter(fullText)
        val env = HashMap<String, String>()
        if (fm.profile.isNotEmpty()) env += repo.loadProfile(fm.profile)
        env += fm.env
        if (ws != null && !ws.saf && fm.cwd.isNotEmpty()) {
            val root = File(ws.ref).canonicalFile
            val dir = File(root, fm.cwd).canonicalFile
            if (dir.path == root.path || dir.path.startsWith(root.path + File.separator)) {
                return Triple(dir, env, "")
            }
            return Triple(root, env, "cwd: escapes the workspace, so the run used the workspace root.")
        }
        val base = if (ws != null && !ws.saf) File(ws.ref) else File(app.filesDir, "run")
        return Triple(base, env, "")
    }

    fun runBlock(uid: Long) {
        val s = screen as? Screen.Editor ?: return
        val seg = segments.firstOrNull { it.uid == uid && it.isFence } ?: return
        val kind = NoteFiles.runnerFor(seg.fenceLang ?: "") ?: return
        // The card edits the same text the run reads: persist first.
        if (dirty) save()
        runJobs[uid]?.cancel()
        runs = runs + (uid to RunPanel(running = true))
        val (dir, env, warn) = runEnv()
        if (warn.isNotEmpty()) status = warn
        dir.mkdirs()
        val session = sessions.getOrPut(s.id) { RunSession(if (dir.isDirectory) dir else app.filesDir) }
        val code = seg.text
        val job = viewModelScope.launch(Dispatchers.IO) {
            val flow = when (kind) {
                NoteFiles.Runner.SHELL -> session.shell(dir, env).run(code)
                NoteFiles.Runner.PYTHON -> PythonRunner.run(code, dir, env)
            }
            val sb = StringBuilder()
            var exit: Int? = null
            var note: String? = null
            try {
                flow.collect { e: RunEvent ->
                    when (e) {
                        is RunEvent.Chunk -> {
                            sb.append(e.text)
                            val snap = sb.toString()
                            launch(Dispatchers.Main) { runs = runs + (uid to RunPanel(snap, true)) }
                        }
                        is RunEvent.Done -> {
                            exit = e.exit
                            note = e.note
                        }
                    }
                }
            } catch (_: Exception) {
                note = "The run was stopped."
            }
            val out = sb.toString()
            withContext(Dispatchers.Main) {
                runs = runs + (uid to RunPanel(out, false, exit, note))
            }
        }
        runJobs = runJobs + (uid to job)
        job.invokeOnCompletion { runJobs = runJobs - uid }
    }

    fun setRunInput(uid: Long, v: String) {
        runInput = runInput + (uid to v)
    }

    private fun runningUid(): Long? = runs.entries.firstOrNull { it.value.running }?.key

    fun sendRunInput(uid: Long) {
        val s = screen as? Screen.Editor ?: return
        val v = runInput[uid].orEmpty()
        if (v.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = sessions[s.id]?.sendInput(v + "\n") == true
            withContext(Dispatchers.Main) {
                if (ok) runInput = runInput + (uid to "")
                else status = "No running block takes input right now."
            }
        }
    }

    /** A raw key for the focused run: ^C, ^D, esc, arrows. No newline. */
    fun sendRunRaw(raw: String) {
        val s = screen as? Screen.Editor ?: return
        val uid = runningUid() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = sessions[s.id]?.sendInput(raw) == true
            withContext(Dispatchers.Main) {
                if (!ok) status = "No running block takes input right now."
            }
        }
    }

    fun stopBlock(uid: Long) {
        val s = screen as? Screen.Editor ?: return
        runJobs[uid]?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            sessions[s.id]?.interrupt()
            sessions.remove(s.id)
            withContext(Dispatchers.Main) {
                val cur = runs[uid]
                runs = runs + (uid to (cur?.copy(running = false, note = "Interrupted.") ?: RunPanel(note = "Interrupted.")))
            }
        }
    }

    fun clearBlock(uid: Long) {
        runJobs[uid]?.cancel()
        runJobs = runJobs - uid
        runs = runs - uid
    }

    private fun closeRuns() {
        runJobs.values.forEach { it.cancel() }
        runJobs = emptyMap()
        sessions.values.forEach { it.close() }
        sessions.clear()
        runs = emptyMap()
        runInput = emptyMap()
    }

    override fun onCleared() {
        closeRuns()
    }
}
