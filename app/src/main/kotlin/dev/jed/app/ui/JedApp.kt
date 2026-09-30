package dev.jed.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import dev.jed.app.notes.NoteFiles
import dev.jed.app.notes.NoteMeta

@Composable
fun JedApp(vm: JedViewModel) {
    when (val s = vm.screen) {
        is Screen.Notes -> MainScaffold(vm, 0) { NotesScreen(vm) }
        is Screen.Templates -> MainScaffold(vm, 1) { TemplatesScreen(vm) }
        is Screen.Trash -> MainScaffold(vm, 2) { TrashScreen(vm) }
        is Screen.Editor -> EditorScreen(vm, s.id)
    }
    vm.conflictNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { vm.dismissConflict() },
            title = { Text("External change kept") },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = { vm.dismissConflict() }) { Text("OK") } },
        )
    }
}

@Composable
private fun MainScaffold(vm: JedViewModel, tab: Int, content: @Composable () -> Unit) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { vm.openList() },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Notes") },
                    label = { Text("Notes") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { vm.openTemplates() },
                    icon = { Icon(Icons.Filled.List, contentDescription = "Templates") },
                    label = { Text("Templates") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { vm.openTrash() },
                    icon = { Icon(Icons.Filled.Delete, contentDescription = "Trash") },
                    label = { Text("Trash") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

// --- notes ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesScreen(vm: JedViewModel) {
    var showNew by remember { mutableStateOf(false) }
    var showWs by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "Folder"
            vm.attachSaf(uri, name)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.workspace?.name ?: "Jed") },
                actions = {
                    TextButton(onClick = { showWs = true }) { Text("Switch") }
                    DropdownMenu(expanded = showWs, onDismissRequest = { showWs = false }) {
                        vm.workspaces.forEach { ws ->
                            DropdownMenuItem(
                                text = { Text(ws.name + if (ws.ref == vm.workspace?.ref) " ✓" else "") },
                                onClick = {
                                    showWs = false
                                    vm.selectWorkspace(ws.ref)
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Attach folder…") },
                            onClick = {
                                showWs = false
                                attach.launch(null)
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = { vm.daily() }) { Text("◉", fontSize = 20.sp) }
                FloatingActionButton(onClick = { showNew = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New note")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.updateQuery(it) },
                label = { Text("Go to note, search text, #tag") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            if (vm.folders.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                    FolderChip("All", vm.folder.isEmpty()) { vm.updateFolder("") }
                    vm.folders.forEach { f ->
                        FolderChip(f, vm.folder == f) { vm.updateFolder(f) }
                    }
                }
            }
            val list = vm.hits ?: vm.notes.filter { NoteFiles.inFolder(it, vm.folder) }
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (vm.query.isNotBlank()) "No notes match."
                        else "No notes yet. The + button writes the first one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    list.forEach { meta ->
                        NoteRow(meta, onOpen = { vm.open(meta.id) })
                    }
                    Spacer(Modifier.height(96.dp))
                }
            }
            if (vm.status.isNotEmpty()) {
                Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showNew) {
        NewNoteDialog(onDismiss = { showNew = false }) { title, folder ->
            showNew = false
            vm.createNote(title, folder)
        }
    }
}

@Composable
private fun FolderChip(name: String, selected: Boolean, onClick: () -> Unit) {
    val label = if (name.isEmpty()) "All" else name
    Surface(
        tonalElevation = if (selected) 4.dp else 0.dp,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(end = 8.dp).selectable(selected, onClick = onClick),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else null,
        )
    }
}

@Composable
private fun NoteRow(meta: NoteMeta, onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text(meta.title) },
        supportingContent = {
            val sub = buildList {
                if (meta.folder.isNotEmpty()) add(meta.folder)
                if (meta.tags.isNotEmpty()) add(meta.tags.take(3).joinToString(" ") { "#$it" })
            }.joinToString("  ·  ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
    HorizontalDivider()
}

@Composable
private fun NewNoteDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder (optional)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(title.trim(), folder.trim()) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- editor ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(vm: JedViewModel, id: String) {
    var confirmDelete by remember { mutableStateOf(false) }
    val blocks = remember(vm.text) { NoteFiles.parseFences(vm.text) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.dirty) "● ${NoteFiles.titleOf(vm.text)}" else NoteFiles.titleOf(vm.text)) },
                navigationIcon = {
                    IconButton(onClick = { vm.openList() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.preview = !vm.preview }) {
                        Text(if (vm.preview) "Edit" else "Preview")
                    }
                    IconButton(onClick = { vm.save() }) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp)
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.preview) {
                MarkdownPreview(vm.text, onWikilink = { vm.followWikilink(it) })
            } else {
                OutlinedTextField(
                    value = vm.text,
                    onValueChange = { vm.edit(it) },
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
                )
                Text(
                    "Tap a block's Run to execute it on this device. sh blocks share the note's shell; python blocks use the device's python3.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            blocks.forEachIndexed { index, block ->
                BlockCard(vm, index, block)
            }
            if (vm.status.isNotEmpty()) {
                Text(vm.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(64.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("It moves to Trash, where it can be restored.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteCurrent()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BlockCard(vm: JedViewModel, index: Int, block: NoteFiles.CodeBlock) {
    val runnable = NoteFiles.runnerFor(block.lang)
    val run = vm.runs[index]
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (block.lang.isEmpty()) "code" else block.lang,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (runnable != null) {
                    if (run?.running == true) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.stopBlock(index) }) { Text("■ Stop") }
                    } else {
                        Button(onClick = { vm.runBlock(index) }) { Text("▶ Run") }
                    }
                    if (run != null && !run.running) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { vm.clearBlock(index) }) { Text("Clear") }
                    }
                }
            }
            SelectionContainer {
                Text(
                    block.code.trimEnd(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (runnable == null && block.lang.isNotEmpty()) {
                Text(
                    "Only sh and python blocks run on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (run != null && (run.output.isNotEmpty() || run.exit != null || run.note != null)) {
                HorizontalDivider()
                if (run.output.isNotEmpty()) {
                    SelectionContainer {
                        Text(run.output.trimEnd(), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
                run.exit?.let { Text("exit $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                run.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (run?.running == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = vm.runInput[index].orEmpty(),
                        onValueChange = { vm.setRunInput(index, it) },
                        label = { Text("Type into the running block") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { vm.sendRunInput(index) }) { Text("Send") }
                }
            }
        }
    }
}

// --- trash -------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashScreen(vm: JedViewModel) {
    var confirmEmpty by remember { mutableStateOf(false) }
    var purgeId by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                actions = {
                    if (vm.trash.isNotEmpty()) {
                        TextButton(onClick = { confirmEmpty = true }) { Text("Empty") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.workspace?.saf == true) {
                Text(
                    "Attached folders delete permanently: Android's storage access gives no trash to move to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (vm.trash.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Trash is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    vm.trash.forEach { meta ->
                        ListItem(
                            headlineContent = { Text(meta.title) },
                            supportingContent = { Text(meta.id, style = MaterialTheme.typography.bodySmall) },
                            trailingContent = {
                                Row {
                                    TextButton(onClick = { vm.restore(meta.id) }) { Text("Restore") }
                                    TextButton(onClick = { purgeId = meta.id }) { Text("Delete") }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty Trash?") },
            text = { Text("This permanently deletes ${vm.trash.size} note(s). There is no undo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    vm.emptyTrash()
                }) { Text("Empty Trash") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
    purgeId?.let { id ->
        AlertDialog(
            onDismissRequest = { purgeId = null },
            title = { Text("Delete permanently?") },
            text = { Text("There is no undo.") },
            confirmButton = {
                TextButton(onClick = {
                    purgeId = null
                    vm.purge(id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { purgeId = null }) { Text("Cancel") } },
        )
    }
}

// --- templates ---------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplatesScreen(vm: JedViewModel) {
    var useId by remember { mutableStateOf<String?>(null) }
    var useTitle by remember { mutableStateOf("") }
    var showStarter by remember { mutableStateOf(false) }
    val templates = vm.templates
    Scaffold(
        topBar = { TopAppBar(title = { Text("Templates") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showStarter = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New template")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (templates.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No templates. Mark any note with template: true in its frontmatter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    templates.forEach { meta ->
                        ListItem(
                            headlineContent = { Text(meta.title) },
                            supportingContent = { Text("{{date}} {{time}} {{title}} {{yesterday}} {{tomorrow}}") },
                            trailingContent = {
                                TextButton(onClick = {
                                    useId = meta.id
                                    useTitle = ""
                                }) { Text("Use") }
                            },
                            modifier = Modifier.clickable { vm.open(meta.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    useId?.let { id ->
        AlertDialog(
            onDismissRequest = { useId = null },
            title = { Text("New note from template") },
            text = {
                OutlinedTextField(useTitle, { useTitle = it }, label = { Text("Title") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val tid = id
                    val t = useTitle.trim()
                    useId = null
                    vm.fromTemplate(tid, t)
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { useId = null }) { Text("Cancel") } },
        )
    }
    if (showStarter) {
        NewNoteDialog(onDismiss = { showStarter = false }) { title, folder ->
            showStarter = false
            vm.createNote(
                title.ifEmpty { "Untitled Template" },
                folder,
                "---\ntemplate: true\n---\n\nWrite {{title}} on {{date}} at {{time}}.\n",
            )
        }
    }
}
