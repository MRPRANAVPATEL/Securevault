package com.pranav.securevault

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val VaultDialogProperties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

private val VaultColors = darkColorScheme(
    primary = Color(0xFFBCCBFF),
    onPrimary = Color(0xFF142B66),
    primaryContainer = Color(0xFF243F87),
    onPrimaryContainer = Color(0xFFDFE7FF),
    secondary = Color(0xFFBBC7E2),
    secondaryContainer = Color(0xFF303D5D),
    onSecondaryContainer = Color(0xFFE0E7FD),
    background = Color(0xFF10162A),
    onBackground = Color(0xFFF0F3FF),
    surface = Color(0xFF10162A),
    onSurface = Color(0xFFF0F3FF),
    surfaceVariant = Color(0xFF26314C),
    onSurfaceVariant = Color(0xFFC4CCE2),
    outline = Color(0xFF8D9ABC),
    outlineVariant = Color(0xFF384562)
)

@Composable
internal fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VaultColors, content = content)
}

@Composable
internal fun LockScreen(
    hasScreenLock: Boolean,
    authenticating: Boolean,
    message: String?,
    onUnlock: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val step = 46.dp.toPx()
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                for (index in 0..4) {
                    val width = size.width + index * step
                    val height = size.height * 0.54f + index * step
                    drawRoundRect(
                        color = Color(0xFF24365B).copy(alpha = 0.55f),
                        topLeft = Offset(centerX - width / 2, centerY - height / 2),
                        size = Size(width, height),
                        cornerRadius = CornerRadius(32.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
            Column(
                modifier = Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                )
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(if (hasScreenLock) R.string.unlock_hint else R.string.screen_lock_required),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (message != null) {
                    Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                if (hasScreenLock) {
                    Button(onClick = onUnlock, enabled = !authenticating) {
                        Text(if (authenticating) "Waiting for confirmation…" else stringResource(R.string.unlock_action))
                    }
                } else {
                    Button(onClick = onSettings) { Text(stringResource(R.string.screen_lock_settings)) }
                }
            }
        }
    }
}

@Composable
internal fun VaultApp(
    session: VaultSession,
    onUnlock: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    onImport: (ItemType) -> Unit,
    onExport: (VaultItem) -> Unit,
    onRecord: () -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val locked = session.locked
    LaunchedEffect(locked) {
        if (locked) {
            focus.clearFocus(force = true)
            keyboard?.hide()
            snackbar.currentSnackbarData?.dismiss()
        }
    }
    val message = session.message
    LaunchedEffect(message, locked) {
        if (message != null && !locked) {
            snackbar.showSnackbar(message)
            session.dismissMessage(message)
        }
    }

    Box(Modifier.fillMaxSize()) {
        val hiddenContent = if (locked) Modifier.clearAndSetSemantics {}.focusProperties { canFocus = false } else Modifier
        Scaffold(
            modifier = Modifier.fillMaxSize().then(hiddenContent),
            snackbarHost = { if (!locked) SnackbarHost(snackbar) },
            floatingActionButton = {
                if (!locked && session.editor == null && session.items != null) {
                    AddMenu(enabled = !session.working, onNote = { session.newText(ItemType.NOTE) },
                        onScript = { session.newText(ItemType.SCRIPT) }, onRecord = onRecord, onImport = onImport)
                }
            }
        ) { insets ->
            val draft = session.editor
            if (draft != null) {
                BackHandler(enabled = !locked) { session.closeEditor() }
                EditorScreen(draft, enabled = !locked && !session.working, onBack = session::closeEditor,
                    onSave = session::saveEditor, modifier = Modifier.padding(insets))
            } else {
                VaultList(session, onLock, onExport, Modifier.padding(insets))
            }
        }
        if (locked) {
            LockScreen(session.hasScreenLock, session.authenticating, session.authMessage, onUnlock, onSettings)
        } else {
            VaultDialogs(session, onExport)
        }
    }
}

@Composable
private fun VaultList(
    session: VaultSession,
    onLock: () -> Unit,
    onExport: (VaultItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onLock, enabled = !session.locked) { Text("Lock") }
        }
        if (session.working) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))

        val allItems = session.items
        if (allItems == null) {
            if (!session.locked) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (session.loadError) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("The vault could not be opened.", textAlign = TextAlign.Center)
                            TextButton(onClick = session::refresh, enabled = !session.working) { Text("Try again") }
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
            return@Column
        }
        val filter = session.filter
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = filter == null, onClick = { session.filter = null }, enabled = !session.locked, label = { Text("All") })
            ItemType.entries.forEach { type ->
                FilterChip(selected = filter == type, onClick = { session.filter = type }, enabled = !session.locked,
                    label = { Text(type.filterLabel) })
            }
        }
        val visible = remember(allItems, filter) { allItems.filter { filter == null || it.type == filter } }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (filter == null) "Add a note or import a file to get started." else "No items in ${filter.filterLabel}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visible, key = VaultItem::id) { item ->
                    ItemRow(item, enabled = !session.locked && !session.working,
                        onOpen = { session.openItem(item) }, onExport = { onExport(item) }, onDelete = { session.requestDelete(item) })
                }
            }
        }
    }
}

@Composable
private fun AddMenu(
    enabled: Boolean,
    onNote: () -> Unit,
    onScript: () -> Unit,
    onRecord: () -> Unit,
    onImport: (ItemType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ExtendedFloatingActionButton(
            text = { Text("Add") },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            onClick = { if (enabled) expanded = true }
        )
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
            val actions = listOf(
                "New note" to onNote,
                "New script" to onScript,
                "Record audio" to onRecord,
                "Import audio" to { onImport(ItemType.AUDIO) },
                "Import script" to { onImport(ItemType.SCRIPT) },
                "Import any file" to { onImport(ItemType.FILE) }
            )
            actions.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    expanded = false
                    action()
                })
            }
        }
    }
}

@Composable
internal fun ItemRow(item: VaultItem, enabled: Boolean, onOpen: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) menu = false }
    val details = itemDetails(item)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.weight(1f).clickable(enabled = enabled, onClickLabel = "Open ${item.type.label}", onClick = onOpen)
                    .padding(start = 14.dp, top = 13.dp, bottom = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item.type.emoji, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(details, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Box(Modifier.padding(end = 4.dp)) {
                IconButton(onClick = { menu = true }, enabled = enabled) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${item.title}")
                }
                DropdownMenu(menu && enabled, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Export (decrypted copy)") }, onClick = {
                        menu = false
                        onExport()
                    })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = {
                        menu = false
                        onDelete()
                    })
                }
            }
        }
    }
}

@Composable
private fun itemDetails(item: VaultItem): String {
    val context = LocalContext.current
    val date = remember(item.updatedAt, context) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.updatedAt)) }
    return "${item.type.label} · $date · ${Formatter.formatShortFileSize(context, item.sizeBytes)}"
}

@Composable
internal fun EditorScreen(draft: EditorDraft, enabled: Boolean, onBack: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    val script = draft.type == ItemType.SCRIPT
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, enabled = enabled) { Text("Back") }
            Text("${if (draft.id == null) "New" else "Edit"} ${draft.type.label.lowercase(Locale.ROOT)}", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onSave, enabled = enabled) { Text("Save") }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { draft.title = it },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next)
            )
            key(draft.page) {
                OutlinedTextField(
                    value = draft.pageText,
                    onValueChange = draft::editPage,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = { Text(if (script) "Script" else "Note") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (script) FontFamily.Monospace else FontFamily.Default),
                    keyboardOptions = if (script) KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Ascii) else
                        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
            }
            if (draft.pageCount > 1) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { draft.showPage(draft.page - 1) }, enabled = enabled && draft.page > 0) {
                        Text("Previous")
                    }
                    Text("Page ${draft.page + 1} of ${draft.pageCount}")
                    TextButton(onClick = { draft.showPage(draft.page + 1) }, enabled = enabled && draft.page + 1 < draft.pageCount) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultDialogs(session: VaultSession, onExport: (VaultItem) -> Unit) {
    if (session.confirmDiscard) {
        AlertDialog(
            onDismissRequest = session::keepEditing, properties = VaultDialogProperties,
            title = { Text("Discard unsaved changes?") },
            confirmButton = { TextButton(onClick = session::discardEditor) { Text("Discard") } },
            dismissButton = { TextButton(onClick = session::keepEditing) { Text("Keep editing") } }
        )
    }
    session.deleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = session::dismissDelete, properties = VaultDialogProperties,
            title = { Text("Delete this ${item.type.label.lowercase(Locale.ROOT)}?") },
            text = { Text("“${item.title}” will be permanently removed from the vault.") },
            confirmButton = { TextButton(onClick = session::confirmDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = session::dismissDelete) { Text("Cancel") } }
        )
    }
    session.detailItem?.let { item ->
        AlertDialog(
            onDismissRequest = session::closeDetails, properties = VaultDialogProperties,
            title = { Text(item.title) }, text = { Text(itemDetails(item)) },
            confirmButton = {
                TextButton(onClick = {
                    session.closeDetails()
                    onExport(item)
                }) { Text("Export (decrypted copy)") }
            },
            dismissButton = { TextButton(onClick = session::closeDetails) { Text("Close") } }
        )
    }
    session.audioItem?.let { item ->
        key(item.id) { AudioPlayerDialog(item, session.repository, session::closeAudio) }
    }
    if (session.showRecording) {
        RecordDialog(session.recordingSince, session::startRecording, session::saveRecording, session::cancelRecording)
    }
}

@Composable
internal fun AudioPlayerDialog(item: VaultItem, repository: VaultRepository, onClose: () -> Unit) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var source by remember { mutableStateOf<BytesDataSource?>(null) }

    LaunchedEffect(item.id) {
        try {
            val data = repository.openAudio(item.id)
            source = data
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            media.setOnPreparedListener { prepared ->
                duration = prepared.duration.coerceAtLeast(0)
                ready = true
            }
            media.setOnCompletionListener {
                playing = false
                position = duration
            }
            media.setOnErrorListener { _, _, _ ->
                failed = true
                playing = false
                true
            }
            media.setDataSource(data)
            media.prepareAsync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = try {
                player?.currentPosition ?: 0
            } catch (_: IllegalStateException) {
                failed = true
                playing = false
                break
            }
            delay(200)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            player?.let { media ->
                media.setOnPreparedListener(null)
                media.setOnCompletionListener(null)
                media.setOnErrorListener(null)
                runCatching { media.release() }
            }
            runCatching { source?.close() }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        properties = VaultDialogProperties,
        title = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    failed -> Text("This audio could not be played. The format may be unsupported or the file may be damaged.")
                    !ready -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> {
                        val progress = if (duration == 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${formatDuration(position.toLong())} / ${formatDuration(duration.toLong())}")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = ready && !failed,
                onClick = {
                    try {
                        player?.let { media ->
                            if (playing) {
                                media.pause()
                                position = media.currentPosition
                            } else {
                                if (duration > 0 && position >= duration) {
                                    media.seekTo(0)
                                    position = 0
                                }
                                media.start()
                            }
                            playing = !playing
                        }
                    } catch (_: IllegalStateException) {
                        failed = true
                        playing = false
                    }
                }
            ) { Text(if (playing) "Pause" else "Play") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
internal fun RecordDialog(startedAt: Long?, onStart: () -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    var elapsed by remember(startedAt) { mutableIntStateOf(0) }
    LaunchedEffect(startedAt) {
        if (startedAt != null) {
            while (true) {
                elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                delay(200)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = VaultDialogProperties,
        title = { Text(if (startedAt == null) "Record audio" else "Recording") },
        text = { Text(formatDuration(elapsed.toLong()), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace) },
        confirmButton = {
            if (startedAt == null) Button(onClick = onStart) { Text("Start") }
            else Button(onClick = onSave) { Text("Stop & save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1_000
    val hours = seconds / 3_600
    val minutes = seconds / 60
    return if (hours == 0L) String.format(Locale.ROOT, "%02d:%02d", minutes, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes % 60, seconds % 60)
}
