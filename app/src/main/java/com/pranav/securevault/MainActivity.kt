package com.pranav.securevault

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FragmentActivity() {
    private val session: VaultSession by viewModels()
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val relock = Runnable { lockVault() }
    private lateinit var prompt: BiometricPrompt

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) lockVault()
        }
    }
    private val importDocument = registerForActivityResult(LocalOpenDocument()) { uri ->
        session.receiveDocument(uri)
    }
    private val exportDocument = registerForActivityResult(LocalCreateDocument { session.exportMime }) { uri ->
        session.receiveDocument(uri)
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) session.showRecorder()
        else session.showMessage("Microphone permission is needed to record audio.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        registerScreenOffReceiver()
        session.hasScreenLock = keyguard.isDeviceSecure
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (!session.authenticating) return
                if (power.isInteractive && keyguard.isDeviceSecure && !keyguard.isDeviceLocked && !backgroundExpired()) {
                    session.unlock()
                } else {
                    lockVault()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!session.authenticating) return
                session.authenticating = false
                session.authMessage = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED -> null
                    else -> errString.toString()
                }
            }
        })
        setContent {
            VaultTheme {
                VaultApp(
                    session = session,
                    onUnlock = ::authenticate,
                    onSettings = ::openSecuritySettings,
                    onLock = ::lockVault,
                    onImport = ::chooseImport,
                    onExport = ::chooseExport,
                    onRecord = ::requestRecording
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(relock)
        if (backgroundExpired() || !power.isInteractive) lockVault()
        session.backgroundSince = null
    }

    override fun onResume() {
        super.onResume()
        session.hasScreenLock = keyguard.isDeviceSecure
        if (!session.hasScreenLock || !power.isInteractive) lockVault()
    }

    override fun onStop() {
        super.onStop()
        session.closeAudio()
        session.cancelRecording()
        session.backgroundSince = SystemClock.elapsedRealtime()
        if (!power.isInteractive) lockVault()
        handler.postDelayed(relock, BACKGROUND_TIMEOUT_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(relock)
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    private fun backgroundExpired(): Boolean = session.backgroundSince?.let {
        SystemClock.elapsedRealtime() - it >= BACKGROUND_TIMEOUT_MS
    } ?: false

    private fun lockVault() {
        val wasAuthenticating = session.authenticating
        session.lock()
        if (wasAuthenticating && ::prompt.isInitialized) prompt.cancelAuthentication()
    }

    private fun authenticate() {
        if (!session.locked || session.authenticating || !power.isInteractive) return
        session.hasScreenLock = keyguard.isDeviceSecure
        if (!session.hasScreenLock) return
        if (BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            session.authMessage = "Unlock is unavailable right now. Check your phone’s screen lock and try again."
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_title))
            .setSubtitle(getString(R.string.unlock_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        session.authMessage = null
        session.authenticating = true
        try {
            prompt.authenticate(info)
        } catch (_: RuntimeException) {
            session.authenticating = false
            session.authMessage = "The unlock prompt could not open. Try again."
        }
    }

    private fun openSecuritySettings() {
        try {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            session.authMessage = "Open Settings on your phone and set a screen lock."
        }
    }

    private fun chooseImport(type: ItemType) {
        if (!session.beginDocument(DocumentAction.Import(type))) return
        val mimeTypes = if (type == ItemType.AUDIO) arrayOf("audio/*", "application/ogg") else arrayOf("*/*")
        try {
            importDocument.launch(mimeTypes)
        } catch (_: ActivityNotFoundException) {
            session.receiveDocument(null)
            session.showMessage("The system file picker could not open.")
        }
    }

    private fun chooseExport(item: VaultItem) {
        if (!session.beginDocument(DocumentAction.Export(item))) return
        try {
            exportDocument.launch(item.fileName)
        } catch (_: ActivityNotFoundException) {
            session.receiveDocument(null)
            session.showMessage("The system save dialog could not open.")
        }
    }

    private fun requestRecording() {
        if (session.locked || session.working) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            session.showRecorder()
        } else {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun registerScreenOffReceiver() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, filter)
    }

    private companion object {
        const val BACKGROUND_TIMEOUT_MS = 60_000L
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}

private class LocalOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

private class LocalCreateDocument(private val mime: () -> String) :
    ActivityResultContracts.CreateDocument("application/octet-stream") {
    override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input)
        .setType(mime()).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

internal sealed interface DocumentAction {
    data class Import(val type: ItemType) : DocumentAction
    data class Export(val item: VaultItem) : DocumentAction
}

internal class EditorDraft(
    val id: String?,
    val type: ItemType,
    private val initialTitle: String,
    private val initialText: String
) {
    private val pages = mutableStateListOf<String>().apply { addAll(splitIntoPages(initialText)) }
    var title by mutableStateOf(initialTitle)
    var page by mutableIntStateOf(0)
        private set
    val pageCount: Int get() = pages.size
    val pageText: String get() = pages[page]
    val changed: Boolean get() = title != initialTitle || fullText() != initialText

    fun fullText(): String = if (pages.size == 1) pages[0] else pages.joinToString("")

    fun showPage(index: Int) {
        page = index.coerceIn(pages.indices)
    }

    fun editPage(text: String) {
        if (text.length <= PAGE_CHARS * 2) {
            pages[page] = text
            return
        }
        val parts = splitIntoPages(text)
        pages[page] = parts.first()
        pages.addAll(page + 1, parts.drop(1))
        page += parts.lastIndex
    }

    private companion object {
        const val PAGE_CHARS = 32_768

        fun splitIntoPages(text: String): List<String> {
            if (text.length <= PAGE_CHARS) return listOf(text)
            return buildList {
                var start = 0
                while (start < text.length) {
                    var end = start + minOf(PAGE_CHARS, text.length - start)
                    if (end < text.length) {
                        val lineBreak = text.lastIndexOf('\n', end - 1)
                        if (lineBreak >= start + PAGE_CHARS / 2) end = lineBreak + 1
                        if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
                        if (text[end - 1] == '\r' && text[end] == '\n') end--
                    }
                    add(text.substring(start, end))
                    start = end
                }
            }
        }
    }
}

internal class VaultSession(application: Application) : AndroidViewModel(application) {
    val repository = VaultRepository.get(application)
    private val recorder = SecureRecorder(application) {
        cancelRecording()
        showMessage("Recording stopped because the microphone became unavailable.")
    }

    var locked by mutableStateOf(true)
        private set
    var hasScreenLock by mutableStateOf(false)
    var authenticating by mutableStateOf(false)
    var authMessage by mutableStateOf<String?>(null)
    var backgroundSince: Long? = null
    var items by mutableStateOf<List<VaultItem>?>(null)
        private set
    var loadError by mutableStateOf(false)
        private set
    var filter by mutableStateOf<ItemType?>(null)
    var working by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var editor by mutableStateOf<EditorDraft?>(null)
        private set
    var confirmDiscard by mutableStateOf(false)
        private set
    var deleteItem by mutableStateOf<VaultItem?>(null)
        private set
    var detailItem by mutableStateOf<VaultItem?>(null)
        private set
    var audioItem by mutableStateOf<VaultItem?>(null)
        private set
    var showRecording by mutableStateOf(false)
        private set
    var recordingSince by mutableStateOf<Long?>(null)
        private set
    private var documentAction: DocumentAction? = null
    private var documentResult: Pair<DocumentAction, Uri>? = null
    private var exportJob: Job? = null

    val exportMime: String get() = (documentAction as? DocumentAction.Export)?.item?.mimeType ?: "application/octet-stream"

    init {
        SecureRecorder.removeAbandonedRecordings(application.cacheDir)
    }

    fun unlock() {
        locked = false
        authenticating = false
        authMessage = null
        if (items == null) refresh()
        handleDocument()
    }

    fun lock() {
        locked = true
        authenticating = false
        authMessage = null
        confirmDiscard = false
        deleteItem = null
        detailItem = null
        closeAudio()
        cancelRecording()
        exportJob?.cancel()
    }

    fun refresh() {
        if (locked || working) return
        loadError = false
        runTask("The vault could not be opened.", onFailure = { loadError = true }) {
            items = repository.list()
        }
    }

    fun newText(type: ItemType) {
        if (locked || working) return
        editor = EditorDraft(null, type, "", "")
    }

    fun openItem(item: VaultItem) {
        if (locked || working) return
        when (item.type) {
            ItemType.NOTE, ItemType.SCRIPT -> runTask("This text could not be opened.") {
                editor = EditorDraft(item.id, item.type, item.title, repository.readText(item.id))
            }
            ItemType.AUDIO -> audioItem = item
            ItemType.FILE -> detailItem = item
        }
    }

    fun saveEditor() {
        if (locked || working) return
        val draft = editor ?: return
        runTask("Changes could not be saved.") {
            items = repository.saveText(draft.id, draft.type, draft.title, draft.fullText())
            editor = null
        }
    }

    fun closeEditor() {
        if (locked || working) return
        if (editor?.changed == true) confirmDiscard = true else editor = null
    }

    fun keepEditing() {
        confirmDiscard = false
    }

    fun discardEditor() {
        if (locked || working) return
        confirmDiscard = false
        editor = null
    }

    fun requestDelete(item: VaultItem) {
        if (!locked && !working) deleteItem = item
    }

    fun dismissDelete() {
        deleteItem = null
    }

    fun confirmDelete() {
        if (locked || working) return
        val item = deleteItem ?: return
        deleteItem = null
        runTask("This item could not be deleted.") { items = repository.delete(item.id) }
    }

    fun closeDetails() {
        detailItem = null
    }

    fun closeAudio() {
        audioItem = null
    }

    fun showRecorder() {
        if (!locked && !working) showRecording = true
    }

    fun startRecording() {
        if (locked || working || !showRecording || recordingSince != null) return
        try {
            recorder.start()
            recordingSince = SystemClock.elapsedRealtime()
        } catch (_: Exception) {
            cancelRecording()
            showMessage("Recording could not start. Check microphone access and available space.")
        }
    }

    fun saveRecording() {
        if (locked || working || recordingSince == null) return
        val file = try {
            recorder.finish()
        } catch (_: Exception) {
            cancelRecording()
            showMessage("No usable audio was recorded. Try recording again.")
            return
        }
        recordingSince = null
        showRecording = false
        runTask("The recording could not be saved.") {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(Date())
                items = repository.importRecording(file, "Recording $timestamp")
            } finally {
                file.delete()
            }
        }
    }

    fun cancelRecording() {
        if (recordingSince != null) recorder.cancel()
        recordingSince = null
        showRecording = false
    }

    fun beginDocument(action: DocumentAction): Boolean {
        if (locked || working || documentAction != null || documentResult != null) return false
        documentAction = action
        return true
    }

    fun receiveDocument(uri: Uri?) {
        val action = documentAction ?: return
        documentAction = null
        if (uri == null) return
        documentResult = action to uri
        handleDocument()
    }

    private fun handleDocument() {
        if (locked || working) return
        val (action, uri) = documentResult ?: return
        documentResult = null
        when (action) {
            is DocumentAction.Import -> runTask("This file could not be imported.") {
                items = repository.importFile(uri, action.type)
                filter = action.type
                showMessage("Imported into SecureVault.")
            }
            is DocumentAction.Export -> {
                exportJob = runTask("The decrypted copy could not be exported.") {
                    repository.export(action.item.id, uri)
                    showMessage("Decrypted copy exported.")
                }
            }
        }
    }

    fun showMessage(text: String) {
        message = text
    }

    fun dismissMessage(text: String) {
        if (message == text) message = null
    }

    private fun runTask(failureText: String, onFailure: () -> Unit = {}, task: suspend () -> Unit): Job {
        working = true
        message = null
        return viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                task()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure()
                message = (failure as? VaultInputException)?.message ?: failureText
            } finally {
                working = false
                handleDocument()
            }
        }
    }

    override fun onCleared() {
        recorder.cancel()
    }
}
