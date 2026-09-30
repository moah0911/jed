package dev.jed.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

class JedViewModel(val repo: NoteRepository, private val app: Context) : ViewModel() {

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

    // Editor state for the open note.
    var text: String by mutableStateOf("")
        private set
    var savedText: String by mutableStateOf("")
        private set
    var baseMtime: Long by mutableStateOf(0L)
        private set
    var preview: Boolean by mutableStateOf(false)
    var status: String by mutableStateOf("")
    var conflictNotice: String? by mutableStateOf(null)
        private set

    var runs: Map<Int, RunPanel> by mutableStateOf(emptyMap())
        private set
    var runInput: Map<Int, String> by mutableStateOf(emptyMap())

    private val sessions = mutableMapOf<String, RunSession>()
    private var runJobs = mapOf<Int, Job>()

    init {
        refresh()
    }

    val dirty: Boolean get() = text != savedText

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
                        val all = list.filter { NoteFiles.inFolder(it, f) }
                        val bodies = all.associate { it.id to (repo.read(it.id).orEmpty()) }
                        val found = NoteFiles.search(all, bodies, q)
                        withContext(Dispatchers.Main) { hits = found }
                    }
                } else {
                    hits = null
                }
                if (screen is Screen.Editor) {
                    // Follow clean external edits; hold dirty buffers for save.
                    val id = (screen as Screen.Editor).id
                    if (!dirty) {
                        repo.read(id)?.let {
                            text = it
                            savedText = it
                            baseMtime = repo.mtime(id)
                        }
                    }
                }
            }
        }
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
            withContext(Dispatchers.Main) {
                text = body
                savedText = body
                baseMtime = m
                preview = false
                status = ""
                conflictNotice = null
                runs = emptyMap()
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

    fun edit(next: String) {
        text = next
    }

    fun save() {
        val s = screen as? Screen.Editor ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val r = repo.save(s.id, text, baseMtime)
            val m = repo.mtime(s.id)
            withContext(Dispatchers.Main) {
                when (r) {
                    is NoteRepository.SaveResult.Saved -> {
                        savedText = text
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

    // --- runs ----------------------------------------------------------------

    private fun runEnv(): Triple<File, Map<String, String>, String> {
        val ws = workspace
        val (fm, _) = NoteFiles.splitFrontmatter(text)
        val env = HashMap<String, String>()
        if (fm.profile.isNotEmpty()) env += repo.loadProfile(fm.profile)
        env += fm.env
        // cwd stays inside a local workspace; SAF notes run in private scratch.
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

    fun runBlock(index: Int) {
        val s = screen as? Screen.Editor ?: return
        val blocks = NoteFiles.parseFences(text)
        val block = blocks.getOrNull(index) ?: return
        val kind = NoteFiles.runnerFor(block.lang) ?: return
        runJobs[index]?.cancel()
        runs = runs + (index to RunPanel(running = true))
        val (dir, env, warn) = runEnv()
        if (warn.isNotEmpty()) status = warn
        val session = sessions.getOrPut(s.id) { RunSession(if (dir.isDirectory || dir.mkdirs()) dir else app.filesDir) }
        val job = viewModelScope.launch(Dispatchers.IO) {
            val flow = when (kind) {
                NoteFiles.Runner.SHELL -> session.shell(dir.apply { mkdirs() }, env).run(block.code)
                NoteFiles.Runner.PYTHON -> PythonRunner.run(block.code, dir.apply { mkdirs() }, env)
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
                            launch(Dispatchers.Main) { runs = runs + (index to RunPanel(snap, true)) }
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
                runs = runs + (index to RunPanel(out, false, exit, note))
            }
        }
        runJobs = runJobs + (index to job)
        job.invokeOnCompletion { runJobs = runJobs - index }
    }

    fun setRunInput(index: Int, v: String) {
        runInput = runInput + (index to v)
    }

    fun sendRunInput(index: Int) {
        val s = screen as? Screen.Editor ?: return
        val v = runInput[index].orEmpty()
        if (v.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = sessions[s.id]?.sendInput(v + "\n") == true
            withContext(Dispatchers.Main) {
                if (ok) runInput = runInput + (index to "")
                else status = "No running block takes input right now."
            }
        }
    }

    fun stopBlock(index: Int) {
        val s = screen as? Screen.Editor ?: return
        runJobs[index]?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            sessions[s.id]?.interrupt()
            sessions.remove(s.id)
            withContext(Dispatchers.Main) {
                val cur = runs[index]
                runs = runs + (index to (cur?.copy(running = false, note = "Interrupted.") ?: RunPanel(note = "Interrupted.")))
            }
        }
    }

    fun clearBlock(index: Int) {
        runJobs[index]?.cancel()
        runs = runs - index
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
