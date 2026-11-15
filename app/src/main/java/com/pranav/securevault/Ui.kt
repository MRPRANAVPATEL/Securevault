package com.pranav.securevault

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EditorState(
    val id: String?,
    val type: ItemType,
    val title: String,
    val text: String
)

private fun formatDate(ms: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
}

// ---------------------------------------------------------------------------
// Lock screen
// ---------------------------------------------------------------------------

@Composable
fun LockScreen(message: String, onUnlock: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } },
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🔒", fontSize = 56.sp)
            Spacer(Modifier.height(12.dp))
            Text("SecureVault", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Everything stays on this phone, encrypted.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

// ---------------------------------------------------------------------------
// Main vault screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultApp(repo: VaultRepository, locked: Boolean, onLockNow: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var vaultItems by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    var filter by remember { mutableStateOf<ItemType?>(null) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var playing by remember { mutableStateOf<VaultItem?>(null) }
    var recording by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<VaultItem?>(null) }
    var busy by remember { mutableStateOf(false) }
    var importType by remember { mutableStateOf(ItemType.FILE) }
    var exportItem by remember { mutableStateOf<VaultItem?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    suspend fun refresh() {
        vaultItems = withContext(Dispatchers.IO) { repo.list() }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(locked) { if (locked) playing = null }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    val item = withContext(Dispatchers.IO) { repo.importUri(uri, importType) }
                    refresh()
                    snackbar.showSnackbar("Imported \"${item.title}\" (encrypted)")
                } catch (e: Exception) {
                    snackbar.showSnackbar("Import failed: ${e.message}")
                } finally {
                    busy = false
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val item = exportItem
        if (uri != null && item != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { repo.exportTo(item.id, uri) }
                    snackbar.showSnackbar("Exported a decrypted copy of \"${item.fileName}\"")
                } catch (e: Exception) {
                    snackbar.showSnackbar("Export failed: ${e.message}")
                }
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            recording = true
        } else {
            scope.launch { snackbar.showSnackbar("Microphone permission is needed to record") }
        }
    }

    fun startRecording() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) recording = true else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    BackHandler(enabled = editor != null) { editor = null }

    val currentEditor = editor
    if (currentEditor != null) {
        EditorScreen(
            state = currentEditor,
            onClose = { editor = null },
            onSave = { title, text ->
                val finalTitle = title.trim().ifEmpty { "Untitled" }
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (currentEditor.id == null) {
                                repo.addText(finalTitle, currentEditor.type, text)
                            } else {
                                repo.updateText(currentEditor.id, finalTitle, text)
                            }
                        }
                        refresh()
                        editor = null
                    } catch (e: Exception) {
                        snackbar.showSnackbar("Save failed: ${e.message}")
                    }
                }
            }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SecureVault") },
                    actions = { TextButton(onClick = onLockNow) { Text("Lock") } }
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                Box {
                    FloatingActionButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("New note") },
                            onClick = {
                                menuOpen = false
                                editor = EditorState(null, ItemType.NOTE, "", "")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("New script") },
                            onClick = {
                                menuOpen = false
                                editor = EditorState(null, ItemType.SCRIPT, "", "")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Record audio") },
                            onClick = {
                                menuOpen = false
                                startRecording()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Import audio") },
                            onClick = {
                                menuOpen = false
                                importType = ItemType.AUDIO
                                importLauncher.launch(arrayOf("audio/*"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Import script / text file") },
                            onClick = {
                                menuOpen = false
                                importType = ItemType.SCRIPT
                                importLauncher.launch(arrayOf("*/*"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Import any file") },
                            onClick = {
                                menuOpen = false
                                importType = ItemType.FILE
                                importLauncher.launch(arrayOf("*/*"))
                            }
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filter == null,
                        onClick = { filter = null },
                        label = { Text("All") }
                    )
                    ItemType.values().forEach { t ->
                        FilterChip(
                            selected = filter == t,
                            onClick = { filter = t },
                            label = { Text(t.label) }
                        )
                    }
                }

                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                val shown = vaultItems.filter { filter == null || it.type == filter }
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Nothing here yet.\nTap + to add a note, script, audio or file.",
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown, key = { it.id }) { item ->
                            ItemRow(
                                item = item,
                                onOpen = {
                                    when (item.type) {
                                        ItemType.NOTE, ItemType.SCRIPT -> scope.launch {
                                            try {
                                                val text = withContext(Dispatchers.IO) {
                                                    repo.readText(item.id)
                                                }
                                                editor = EditorState(item.id, item.type, item.title, text)
                                            } catch (e: Exception) {
                                                snackbar.showSnackbar("Cannot open: ${e.message}")
                                            }
                                        }
                                        ItemType.AUDIO -> playing = item
                                        ItemType.FILE -> {
                                            exportItem = item
                                            exportLauncher.launch(item.fileName)
                                        }
                                    }
                                },
                                onExport = {
                                    exportItem = item
                                    exportLauncher.launch(item.fileName)
                                },
                                onDelete = { confirmDelete = item }
                            )
                        }
                    }
                }
            }
        }
    }

    playing?.let { item ->
        AudioPlayerDialog(repo = repo, item = item, onDismiss = { playing = null })
    }

    if (recording) {
        RecordDialog(
            onCancel = { recording = false },
            onSaved = { file ->
                recording = false
                scope.launch {
                    try {
                        val title = "Recording " +
                            SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
                        withContext(Dispatchers.IO) { repo.addAudioFile(file, title) }
                        refresh()
                        snackbar.showSnackbar("Recording saved (encrypted)")
                    } catch (e: Exception) {
                        snackbar.showSnackbar("Could not save recording: ${e.message}")
                    }
                }
            }
        )
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${item.title}\"?") },
            text = { Text("This permanently removes it from the vault.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.delete(item.id) }
                        refresh()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ItemRow(
    item: VaultItem,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(item.type.emoji, fontSize = 26.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatDate(item.createdAt)} · ${formatSize(item.size)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Export (decrypted copy)") },
                        onClick = { menu = false; onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Note / script editor
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorState,
    onClose: () -> Unit,
    onSave: (title: String, text: String) -> Unit
) {
    var title by remember(state) { mutableStateOf(state.title) }
    var text by remember(state) { mutableStateOf(state.text) }
    val isScript = state.type == ItemType.SCRIPT

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isScript) "Script" else "Note") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Close") } },
                actions = { TextButton(onClick = { onSave(title, text) }) { Text("Save") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(12.dp)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(if (isScript) "Script" else "Text") },
                textStyle = TextStyle(
                    fontFamily = if (isScript) FontFamily.Monospace else FontFamily.Default,
                    fontSize = 15.sp
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Audio playback and recording
// ---------------------------------------------------------------------------

@Composable
fun AudioPlayerDialog(repo: VaultRepository, item: VaultItem, onDismiss: () -> Unit) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(item.id) {
        try {
            val mp = withContext(Dispatchers.IO) {
                val bytes = repo.readBytes(item.id)
                MediaPlayer().apply {
                    setDataSource(BytesDataSource(bytes))
                    prepare()
                }
            }
            mp.setOnCompletionListener {
                isPlaying = false
                progress = 0f
            }
            player = mp
        } catch (e: Exception) {
            error = e.message ?: "Cannot play this audio"
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            player?.let { p ->
                if (p.duration > 0) progress = p.currentPosition.toFloat() / p.duration
            }
            delay(250)
        }
    }

    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = {
            Column {
                val err = error
                if (err != null) {
                    Text(err, color = MaterialTheme.colorScheme.error)
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = player != null,
                onClick = {
                    player?.let { p ->
                        if (p.isPlaying) {
                            p.pause()
                            isPlaying = false
                        } else {
                            p.start()
                            isPlaying = true
                        }
                    }
                }
            ) { Text(if (isPlaying) "Pause" else "Play") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun RecordDialog(onCancel: () -> Unit, onSaved: (File) -> Unit) {
    val context = LocalContext.current
    val recorder = remember { SecureRecorder(context) }
    var seconds by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            recorder.start()
        } catch (e: Exception) {
            error = e.message ?: "Cannot start recording"
            return@LaunchedEffect
        }
        while (true) {
            delay(1000)
            seconds++
        }
    }

    DisposableEffect(Unit) {
        onDispose { recorder.cancel() }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Recording…") },
        text = {
            val err = error
            if (err != null) {
                Text(err, color = MaterialTheme.colorScheme.error)
            } else {
                Text(
                    String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60),
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        },
        confirmButton = {
            Button(
                enabled = error == null,
                onClick = {
                    val file = recorder.stop()
                    if (file != null) onSaved(file) else onCancel()
                }
            ) { Text("Stop & save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
