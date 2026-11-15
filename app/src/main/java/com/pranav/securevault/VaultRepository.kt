package com.pranav.securevault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal enum class ItemType(
    val label: String,
    val filterLabel: String,
    val emoji: String
) {
    NOTE("Note", "Notes", "📝"),
    SCRIPT("Script", "Scripts", "📜"),
    AUDIO("Audio", "Audio", "🎙️"),
    FILE("File", "Files", "📎");

    val isEditable: Boolean get() = this == NOTE || this == SCRIPT
}

internal data class VaultItem(
    val id: String,
    val type: ItemType,
    val title: String,
    val updatedAt: Long,
    val sizeBytes: Long,
    val fileName: String,
    val mimeType: String,
    val ciphertextDigest: String
)

internal class VaultInputException(message: String) : IOException(message)

internal class VaultRepository private constructor(context: Context) {
    private val resolver = context.contentResolver
    private val directory = File(context.filesDir, "vault")
    private val indexFile = File(directory, "index.bin")
    private val mutex = Mutex()
    private lateinit var crypto: Crypto
    private var cachedItems: List<VaultItem>? = null

    suspend fun list(): List<VaultItem> = onDisk { loadIndex() }

    suspend fun readText(id: String): String = onDisk {
        val item = findItem(id)
        require(item.type.isEditable)
        val bytes = crypto.decryptSmallFile(blob(item.id), itemAad(item.id))
        try {
            bytes.toString(Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun saveText(id: String?, type: ItemType, title: String, text: String): List<VaultItem> = onDisk {
        require(type.isEditable)
        val oldItem = id?.let(::findItem)
        require(oldItem == null || oldItem.type == type)
        val displayTitle = title.trim().ifBlank { "Untitled ${type.label.lowercase(Locale.ROOT)}" }
        val name = textFileName(displayTitle, type, oldItem)
        val mime = when {
            type == ItemType.NOTE -> "text/plain"
            oldItem?.fileName == name -> oldItem.mimeType
            else -> mimeForName(name) ?: "text/plain"
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            writeItem(type, displayTitle, name, mime, bytes.inputStream(), oldItem)
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun importFile(uri: Uri, type: ItemType): List<VaultItem> = onDisk {
        check(type != ItemType.NOTE)
        val name = documentName(uri, if (type == ItemType.SCRIPT) "Imported script.txt" else "Imported file")
        val mime = resolver.getType(uri)?.takeIf(::isConcreteMimeType)
            ?: mimeForName(name) ?: "application/octet-stream"
        if (type == ItemType.SCRIPT) {
            val text = openDocument(uri).use(::readImportedScript)
            val bytes = text.toByteArray(Charsets.UTF_8)
            try {
                writeItem(type, name, name, mime, bytes.inputStream())
            } finally {
                bytes.fill(0)
            }
        } else {
            openDocument(uri).use { writeItem(type, name, name, mime, it) }
        }
    }

    suspend fun importRecording(file: File, title: String): List<VaultItem> = onDisk {
        file.inputStream().use { writeItem(ItemType.AUDIO, title, "$title.m4a", "audio/mp4", it) }
    }

    suspend fun openAudio(id: String): BytesDataSource {
        var source: BytesDataSource? = null
        try {
            return onDisk {
                val item = findItem(id)
                require(item.type == ItemType.AUDIO)
                BytesDataSource(openItem(item) { ensureActive() }).also { source = it }
            }
        } catch (cancelled: CancellationException) {
            // withContext can cancel after IO has created the source but before
            // the caller receives it.
            runCatching { source?.close() }
            throw cancelled
        }
    }

    suspend fun export(id: String, uri: Uri) {
        var complete = false
        try {
            onDisk {
                openItem(findItem(id)) { ensureActive() }.use { source ->
                    ensureActive()
                    // Providers may reject the explicit truncate mode. The SAF
                    // CreateDocument destination is a newly created file.
                    val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Unable to open export")
                    output.use { source.copyTo(it) { ensureActive() } }
                }
                complete = true
            }
        } finally {
            if (!complete) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            }
        }
    }

    suspend fun delete(id: String): List<VaultItem> = onDisk {
        val item = findItem(id)
        if (commitIndex(loadIndex().filterNot { it.id == id })) blob(item.id).delete()
        loadIndex()
    }

    private fun openDocument(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("Unable to open the selected document")

    private fun documentName(uri: Uri, fallback: String): String {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        return cleanFileName(name.orEmpty()).ifBlank { fallback }
    }

    private fun findItem(id: String): VaultItem = loadIndex().firstOrNull { it.id == id }
        ?: throw VaultInputException("This item is no longer in the vault.")

    private fun openItem(item: VaultItem, checkActive: () -> Unit): VerifiedBlob = crypto.openVerified(
        blob(item.id), item.sizeBytes, Base64.decode(item.ciphertextDigest, Base64.NO_WRAP), checkActive
    )

    private fun blob(id: String) = File(directory, "$id.bin")

    private fun loadIndex(): List<VaultItem> {
        cachedItems?.let { return it }
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to open the vault folder")
        }
        val files = directory.listFiles() ?: throw IOException("Unable to read the vault folder")
        if (!indexFile.exists() && files.any(::isItemFile)) {
            throw IOException("The vault index is missing")
        }
        crypto = Crypto.open(allowCreate = !indexFile.exists())
        if (!indexFile.exists()) commitIndex(emptyList())

        val bytes = crypto.decryptSmallFile(indexFile, INDEX_AAD)
        val items = try {
            val json = JSONArray(bytes.toString(Charsets.UTF_8))
            List(json.length()) { index -> fromJson(json.getJSONObject(index)) }
        } finally {
            bytes.fill(0)
        }
        val activeNames = items.mapTo(HashSet()) { "${it.id}.bin" }
        if (activeNames.size != items.size) throw IOException("Duplicate item IDs in the vault index")
        for (file in files) {
            if (file.name.endsWith(".tmp") || (isItemFile(file) && file.name !in activeNames)) file.delete()
        }
        return items.sortedByDescending(VaultItem::updatedAt).also { cachedItems = it }
    }

    private fun writeItem(
        type: ItemType,
        title: String,
        name: String,
        mime: String,
        input: InputStream,
        oldItem: VaultItem? = null
    ): List<VaultItem> {
        val current = loadIndex()
        // A new UUID on replacement keeps the previous version available until
        // its replacement and the new index have been committed.
        val id = UUID.randomUUID().toString()
        val target = blob(id)
        val temp = File.createTempFile("item-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val size = FileOutputStream(temp).use { output ->
                val encrypted = DigestOutputStream(output, digest)
                crypto.encrypt(input, encrypted, itemAad(id)).also { output.fd.sync() }
            }
            Os.rename(temp.path, target.path)
            syncDirectory()
            val item = VaultItem(
                id, type, title, System.currentTimeMillis(), size, name, mime,
                Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
            )
            val next = (listOf(item) + current.filterNot { it.id == oldItem?.id })
                .sortedByDescending(VaultItem::updatedAt)
            if (commitIndex(next)) oldItem?.let { blob(it.id).delete() }
            return next
        } catch (failure: Exception) {
            if (cachedItems?.none { it.id == id } == true) target.delete()
            throw failure
        } finally {
            temp.delete()
        }
    }

    private fun commitIndex(items: List<VaultItem>): Boolean {
        val json = JSONArray().apply { items.forEach { put(toJson(it)) } }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val temp = File.createTempFile("index-", ".tmp", directory)
        try {
            FileOutputStream(temp).use { output ->
                crypto.encrypt(bytes.inputStream(), output, INDEX_AAD)
                output.fd.sync()
            }
            Os.rename(temp.path, indexFile.path)
            cachedItems = items
            return try {
                syncDirectory()
                true
            } catch (_: ErrnoException) {
                // The new index is visible. Keep old blobs until the next start
                // in case a power loss rolls back the rename.
                false
            }
        } finally {
            bytes.fill(0)
            temp.delete()
        }
    }

    private fun syncDirectory() {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    private suspend fun <T> onDisk(operation: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { operation() }
    }

    companion object {
        private const val SCRIPT_MAX_BYTES = 5_000_000
        private val INDEX_AAD = "SecureVault:index:v1".toByteArray()
        private val UUID_FILE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin")
        private val MIMETYPE = Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")
        private val UNSAFE_FILENAME = Regex("[\\p{Cntrl}/\\\\:]")
        private var instance: VaultRepository? = null

        @Synchronized
        fun get(context: Context): VaultRepository = instance
            ?: VaultRepository(context.applicationContext).also { instance = it }

        private fun isItemFile(file: File) = UUID_FILE.matches(file.name)
        private fun itemAad(id: String) = "SecureVault:item:v1:$id".toByteArray()
        private fun isConcreteMimeType(mime: String) = MIMETYPE.matches(mime)
        private fun cleanFileName(name: String) = name.replace(UNSAFE_FILENAME, "_").trimStart { it == '.' || it.isWhitespace() }.trimEnd()
        private fun mimeForName(name: String): String? = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))

        private fun textFileName(title: String, type: ItemType, old: VaultItem?): String {
            if (old != null && old.title == title) return old.fileName
            val safe = cleanFileName(title).ifBlank { type.label }
            if (type == ItemType.NOTE) return if (safe.endsWith(".txt", ignoreCase = true)) safe else "$safe.txt"
            if (safe.substringAfterLast('.', "").isNotEmpty()) return safe
            val extension = old?.fileName?.substringAfterLast('.', "").orEmpty().ifBlank { "txt" }
            return "$safe.$extension"
        }

        private fun readImportedScript(input: InputStream): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            val bytes: ByteArray
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > SCRIPT_MAX_BYTES) {
                        throw VaultInputException("Script files can be up to 5 MB.")
                    }
                    output.write(buffer, 0, count)
                }
                bytes = output.toByteArray()
            } finally {
                buffer.fill(0)
            }
            try {
                val first = bytes.getOrNull(0)?.toInt()?.and(0xff)
                val second = bytes.getOrNull(1)?.toInt()?.and(0xff)
                val third = bytes.getOrNull(2)?.toInt()?.and(0xff)
                val (charset, skip) = when {
                    first == 0xef && second == 0xbb && third == 0xbf -> Charsets.UTF_8 to 3
                    first == 0xff && second == 0xfe -> Charsets.UTF_16LE to 2
                    first == 0xfe && second == 0xff -> Charsets.UTF_16BE to 2
                    else -> Charsets.UTF_8 to 0
                }
                val text = charset.newDecoder().decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
                if ('\u0000' in text) throw VaultInputException("Choose a UTF-8 or UTF-16 text file.")
                return text
            } catch (_: CharacterCodingException) {
                throw VaultInputException("Choose a UTF-8 or UTF-16 text file.")
            } finally {
                bytes.fill(0)
            }
        }

        private fun toJson(item: VaultItem) = JSONObject()
            .put("id", item.id)
            .put("type", item.type.name)
            .put("title", item.title)
            .put("updatedAt", item.updatedAt)
            .put("sizeBytes", item.sizeBytes)
            .put("fileName", item.fileName)
            .put("mimeType", item.mimeType)
            .put("ciphertextDigest", item.ciphertextDigest)

        private fun fromJson(json: JSONObject): VaultItem {
            val id = json.getString("id")
            val size = json.getLong("sizeBytes")
            val mime = json.getString("mimeType")
            val name = json.getString("fileName")
            require(UUID_FILE.matches("$id.bin") && size >= 0 && isConcreteMimeType(mime))
            require(name.isNotBlank() && name == cleanFileName(name))
            val digest = json.getString("ciphertextDigest")
            require(Base64.decode(digest, Base64.NO_WRAP).size == 32)
            return VaultItem(
                id, ItemType.valueOf(json.getString("type")), json.getString("title"),
                json.getLong("updatedAt"), size, name, mime, digest
            )
        }
    }
}
