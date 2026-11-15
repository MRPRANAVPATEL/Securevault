package com.pranav.securevault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

enum class ItemType(val label: String, val emoji: String) {
    NOTE("Notes", "📝"),
    SCRIPT("Scripts", "💻"),
    AUDIO("Audio", "🎵"),
    FILE("Files", "📄")
}

data class VaultItem(
    val id: String,
    val title: String,
    val type: ItemType,
    val mime: String,
    val createdAt: Long,
    val size: Long,
    val fileName: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("type", type.name)
        .put("mime", mime)
        .put("createdAt", createdAt)
        .put("size", size)
        .put("fileName", fileName)

    companion object {
        fun fromJson(o: JSONObject) = VaultItem(
            id = o.getString("id"),
            title = o.getString("title"),
            type = ItemType.valueOf(o.getString("type")),
            mime = o.getString("mime"),
            createdAt = o.getLong("createdAt"),
            size = o.getLong("size"),
            fileName = o.getString("fileName")
        )
    }
}

/**
 * Everything is stored in the app's private folder, encrypted with [Crypto].
 * The item list (titles, dates) is encrypted too, so nothing readable is on disk.
 */
class VaultRepository(private val context: Context) {
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val indexFile = File(dir, "index.bin")

    private fun blob(id: String) = File(dir, "$id.bin")

    @Synchronized
    fun list(): List<VaultItem> {
        if (!indexFile.exists()) return emptyList()
        val json = String(Crypto.decryptBytes(indexFile.readBytes()), Charsets.UTF_8)
        val arr = JSONArray(json)
        return (0 until arr.length())
            .map { VaultItem.fromJson(arr.getJSONObject(it)) }
            .sortedByDescending { it.createdAt }
    }

    @Synchronized
    private fun saveIndex(items: List<VaultItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        val tmp = File(dir, "index.tmp")
        tmp.writeBytes(Crypto.encryptBytes(arr.toString().toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            tmp.renameTo(indexFile)
        }
    }

    @Synchronized
    private fun register(item: VaultItem) = saveIndex(list() + item)

    private fun writeEncrypted(id: String, bytes: ByteArray) {
        Crypto.encryptStream(FileOutputStream(blob(id))).use { it.write(bytes) }
    }

    // ---------- Notes and scripts (text) ----------

    fun addText(title: String, type: ItemType, text: String): VaultItem {
        val id = UUID.randomUUID().toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeEncrypted(id, bytes)
        val item = VaultItem(
            id = id,
            title = title,
            type = type,
            mime = "text/plain",
            createdAt = System.currentTimeMillis(),
            size = bytes.size.toLong(),
            fileName = safeName(title) + ".txt"
        )
        register(item)
        return item
    }

    @Synchronized
    fun updateText(id: String, title: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeEncrypted(id, bytes)
        saveIndex(list().map {
            if (it.id == id) it.copy(
                title = title,
                size = bytes.size.toLong(),
                fileName = safeName(title) + ".txt"
            ) else it
        })
    }

    fun readText(id: String): String = String(readBytes(id), Charsets.UTF_8)

    // ---------- Audio / files ----------

    /** Encrypts a freshly recorded file and deletes the plain copy. */
    fun addAudioFile(plain: File, title: String): VaultItem {
        val id = UUID.randomUUID().toString()
        val plainSize = plain.length()
        try {
            FileInputStream(plain).use { input ->
                Crypto.encryptStream(FileOutputStream(blob(id))).use { out -> input.copyTo(out) }
            }
        } finally {
            plain.delete()
        }
        val item = VaultItem(
            id = id,
            title = title,
            type = ItemType.AUDIO,
            mime = "audio/mp4",
            createdAt = System.currentTimeMillis(),
            size = plainSize,
            fileName = safeName(title) + ".m4a"
        )
        register(item)
        return item
    }

    /** Imports a file the user picked. [type] decides how it is stored. */
    fun importUri(uri: Uri, type: ItemType): VaultItem {
        val resolver = context.contentResolver
        val name = displayName(uri) ?: "file"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()

        val size: Long
        if (type == ItemType.SCRIPT) {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Cannot open the file")
            require(bytes.size <= 5_000_000) { "Script is too large (max 5 MB)" }
            writeEncrypted(id, bytes)
            size = bytes.size.toLong()
        } else {
            val input = resolver.openInputStream(uri) ?: error("Cannot open the file")
            size = input.use { src ->
                Crypto.encryptStream(FileOutputStream(blob(id))).use { out -> src.copyTo(out) }
            }
        }

        val item = VaultItem(
            id = id,
            title = name.substringBeforeLast('.', name),
            type = type,
            mime = mime,
            createdAt = System.currentTimeMillis(),
            size = size,
            fileName = name
        )
        register(item)
        return item
    }

    fun readBytes(id: String): ByteArray =
        Crypto.decryptStream(FileInputStream(blob(id))).use { it.readBytes() }

    /** Writes a DECRYPTED copy to a location the user chose. */
    fun exportTo(id: String, uri: Uri) {
        val out = context.contentResolver.openOutputStream(uri) ?: error("Cannot write the file")
        out.use { dest ->
            Crypto.decryptStream(FileInputStream(blob(id))).use { it.copyTo(dest) }
        }
    }

    @Synchronized
    fun delete(id: String) {
        blob(id).delete()
        saveIndex(list().filter { it.id != id })
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun safeName(title: String): String =
        title.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().ifEmpty { "item" }
}
