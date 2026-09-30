package dev.jed.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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

/** Adopt edits made outside the field (accessory bar, resegment). */
private fun syncedField(segText: String, field: TextFieldValue): TextFieldValue {
    if (field.text == segText) return field
    val end = segText.length
    val sel = TextRange(field.selection.start.coerceIn(0, end), field.selection.end.coerceIn(0, end))
    return field.copy(text = segText, selection = sel)
}

// --- notes ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NotesScreen(vm: JedViewModel) {
    var showNew by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showWorkspaces by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NoteMeta?>(null) }
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
                title = { Text("${vm.workspace?.name ?: "Jed"} · ${vm.notes.size}") },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Switch workspace…") },
                            onClick = {
                                showMenu = false
                                showWorkspaces = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Attach folder…") },
                            onClick = {
                                showMenu = false
                                attach.launch(null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Theme: ${vm.theme.replaceFirstChar { it.uppercase() }}…") },
                            onClick = {
                                showMenu = false
                                showTheme = true
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
                        NoteRow(meta, onOpen = { vm.open(meta.id) }, onDelete = { pendingDelete = meta })
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
    if (showWorkspaces) {
        AlertDialog(
            onDismissRequest = { showWorkspaces = false },
            title = { Text("Workspaces") },
            text = {
                Column {
                    vm.workspaces.forEach { ws ->
                        val sel = ws.ref == vm.workspace?.ref
                        TextButton(onClick = {
                            showWorkspaces = false
                            vm.selectWorkspace(ws.ref)
                        }) { Text((if (sel) "✓ " else "") + ws.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showWorkspaces = false }) { Text("Close") } },
        )
    }
    if (showTheme) {
        AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text("Theme") },
            text = {
                Column {
                    listOf("dark", "light", "system").forEach { t ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = vm.theme == t,
                                onClick = { vm.updateTheme(t) },
                            ),
                        ) {
                            RadioButton(selected = vm.theme == t, onClick = { vm.updateTheme(t) })
                            Text(t.replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTheme = false }) { Text("Done") } },
        )
    }
    pendingDelete?.let { meta ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${meta.title}\"?") },
            text = { Text("It moves to Trash, where it can be restored.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    vm.deleteNote(meta.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(meta: NoteMeta, onOpen: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(meta.title) },
        supportingContent = {
            val sub = buildList {
                if (meta.folder.isNotEmpty()) add(meta.folder)
                if (meta.tags.isNotEmpty()) add(meta.tags.take(3).joinToString(" ") { "#$it" })
            }.joinToString("  ·  ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
        },
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onDelete),
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditorScreen(vm: JedViewModel, id: String) {
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) vm.insertPickedImage(uri)
    }
    val imeVisible = WindowInsets.isImeVisible
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.dirty) "● ${NoteFiles.titleOf(vm.fullText)}" else NoteFiles.titleOf(vm.fullText)) },
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
        bottomBar = {
            if (imeVisible && !vm.preview) {
                AccessoryBar(vm, onImage = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp)
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (vm.preview) {
                MarkdownPreview(vm.fullText, onWikilink = { vm.followWikilink(it) })
            } else {
                vm.segments.forEach { seg ->
                    if (seg.isFence) FenceCard(vm, seg, context)
                    else ProseField(vm, seg)
                }
            }
            if (vm.status.isNotEmpty()) {
                Text(
                    vm.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
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
private fun ProseField(vm: JedViewModel, seg: SegState) {
    var field by remember(seg.uid) { mutableStateOf(TextFieldValue(seg.text)) }
    field = syncedField(seg.text, field)
    BasicTextField(
        value = field,
        onValueChange = {
            field = it
            vm.editSeg(seg.uid, it.text)
            vm.noteFocus(seg.uid)
            vm.noteSelection(it.selection)
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = if (seg.text.isEmpty()) 48.dp else 0.dp)
            .padding(vertical = 6.dp)
            .onFocusChanged { if (it.isFocused) vm.noteFocus(seg.uid) },
        decorationBox = { inner ->
            if (seg.text.isEmpty()) {
                Text("Write…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            inner()
        },
    )
}

@Composable
private fun FenceCard(vm: JedViewModel, seg: SegState, context: Context) {
    val lang = seg.fenceLang.orEmpty()
    val runnable = NoteFiles.runnerFor(lang)
    val run = vm.runs[seg.uid]
    var field by remember(seg.uid) { mutableStateOf(TextFieldValue(seg.text)) }
    field = syncedField(seg.text, field)
    Surface(
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (lang.isEmpty()) "code" else lang,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (runnable != null) {
                    if (run?.running == true) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.stopBlock(seg.uid) }) { Text("■ Stop") }
                    } else {
                        Button(onClick = { vm.runBlock(seg.uid) }) { Text("▶ Run") }
                    }
                    if (run != null && !run.running) {
                        TextButton(onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("Jed run output", run.output))
                            vm.status = "Output copied."
                        }) { Text("Copy") }
                        TextButton(onClick = { vm.clearBlock(seg.uid) }) { Text("Clear") }
                    }
                }
            }
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    vm.editSeg(seg.uid, it.text)
                    vm.noteFocus(seg.uid)
                    vm.noteSelection(it.selection)
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) vm.noteFocus(seg.uid) },
            )
            if (runnable == null) {
                Text(
                    NoteFiles.unrunnableNote(lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (run != null && (run.output.isNotEmpty() || run.exit != null || run.note != null)) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dot = when {
                        run.running -> Color(0xFF66BB6A)
                        (run.exit ?: 0) == 0 -> Color(0xFF66BB6A)
                        else -> Color(0xFFE57373)
                    }
                    Text("●", color = dot, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            run.running -> "Running"
                            run.exit != null -> "Done · exit ${run.exit}"
                            else -> "Run"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (run.output.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            run.output.trimEnd(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                run.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (run?.running == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.sendRunInput(seg.uid) }) { Text("Tap to type") }
                    OutlinedTextField(
                        value = vm.runInput[seg.uid].orEmpty(),
                        onValueChange = { vm.setRunInput(seg.uid, it) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { vm.sendRunInput(seg.uid) }) { Text("Send") }
                }
            }
        }
    }
}

// --- accessory bar -----------------------------------------------------------
//
// The strip above the keyboard: formatting verbs for prose, and the run
// face (interrupt keys) while a block runs. Names only, never behavior:
// a verb tap edits text, a key tap writes bytes to the running block.

@Composable
private fun AccessoryBar(vm: JedViewModel, onImage: () -> Unit) {
    val running = vm.runs.entries.firstOrNull { it.value.running }?.key
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (running == null) {
                BarButton("B", "Bold") { vm.wrapSelection("**", "**", vm.focusedSel) }
                BarButton("I", "Italic") { vm.wrapSelection("*", "*", vm.focusedSel) }
                BarButton("[[", "Wikilink") { vm.wrapSelection("[[", "]]", vm.focusedSel) }
                BarButton("```", "Code block") { vm.insertFence() }
                BarButton("link", "Link") { vm.wrapSelection("[", "](https://)", vm.focusedSel) }
                BarButton("img", "Insert image") { onImage() }
            } else {
                BarButton("^C", "Interrupt key") { vm.sendRunRaw("\u0003") }
                BarButton("^D", "End of input") { vm.sendRunRaw("\u0004") }
                BarButton("esc", "Escape") { vm.sendRunRaw("\u001B") }
                BarButton("↑", "Up") { vm.sendRunRaw("\u001B[A") }
                BarButton("↓", "Down") { vm.sendRunRaw("\u001B[B") }
                BarButton("←", "Left") { vm.sendRunRaw("\u001B[D") }
                BarButton("→", "Right") { vm.sendRunRaw("\u001B[C") }
                BarButton("■", "Back to note") { vm.stopBlock(running) }
            }
        }
    }
}

@Composable
private fun BarButton(label: String, description: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.height(44.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
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
    Scaffold(
        topBar = { TopAppBar(title = { Text("Templates") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showStarter = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New template")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.templates.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No templates. Mark any note with template: true in its frontmatter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    vm.templates.forEach { meta ->
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
