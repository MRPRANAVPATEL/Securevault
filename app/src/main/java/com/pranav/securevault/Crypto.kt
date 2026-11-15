package com.pranav.securevault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec

private const val IV_BYTES = 12
private const val TAG_BYTES = 16
private const val BLOCK_BYTES = 16
private const val BUFFER_BYTES = 64 * 1024
private const val MAX_GCM_BYTES = (1L shl 36) - 32
private const val GCM = "AES/GCM/NoPadding"
private const val CTR = "AES/CTR/NoPadding"

internal class Crypto private constructor(private val key: SecretKey) {
    fun encrypt(input: InputStream, output: OutputStream, associatedData: ByteArray): Long {
        val cipher = Cipher.getInstance(GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        val iv = checkNotNull(cipher.iv)
        check(iv.size == IV_BYTES) { "Unexpected GCM IV length" }
        output.write(iv)

        val buffer = ByteArray(BUFFER_BYTES)
        var size = 0L
        try {
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                size += count
                if (size > MAX_GCM_BYTES) throw IOException("File exceeds the AES-GCM size limit")
                cipher.update(buffer, 0, count)?.let(output::write)
            }
            output.write(cipher.doFinal())
            return size
        } finally {
            buffer.fill(0)
        }
    }

    fun decryptSmallFile(file: File, associatedData: ByteArray): ByteArray = RandomAccessFile(file, "r").use { input ->
        val encryptedSize = input.length() - IV_BYTES
        if (encryptedSize !in TAG_BYTES.toLong()..Int.MAX_VALUE.toLong()) throw IOException("Invalid encrypted file length")
        val iv = ByteArray(IV_BYTES).also(input::readFully)
        val encrypted = ByteArray(encryptedSize.toInt()).also(input::readFully)
        val cipher = Cipher.getInstance(GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, iv))
        cipher.updateAAD(associatedData)
        cipher.doFinal(encrypted)
    }

    fun openVerified(
        file: File,
        expectedSize: Long,
        expectedDigest: ByteArray,
        checkActive: () -> Unit
    ): VerifiedBlob {
        val input = RandomAccessFile(file, "r")
        try {
            val size = input.length() - IV_BYTES - TAG_BYTES
            if (size !in 0L..MAX_GCM_BYTES || size != expectedSize) throw IOException("Invalid vault file length")

            // The GCM-authenticated index pins the digest of the entire immutable blob.
            // This verifies it in bounded memory before Android Keystore decrypts any
            // payload. Keystore's GCM decryptor would buffer the whole plaintext.
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            var remaining = input.length()
            while (remaining > 0) {
                checkActive()
                val count = minOf(remaining, buffer.size.toLong()).toInt()
                input.readFully(buffer, 0, count)
                digest.update(buffer, 0, count)
                remaining -= count
            }
            if (!MessageDigest.isEqual(expectedDigest, digest.digest())) {
                throw AEADBadTagException("Vault file authentication failed")
            }
            input.seek(0)
            val iv = ByteArray(IV_BYTES).also(input::readFully)
            return VerifiedBlob(input, key, iv, size)
        } catch (failure: Throwable) {
            runCatching { input.close() }
            throw failure
        }
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "com.pranav.securevault.vault.v1"

        fun open(allowCreate: Boolean): Crypto {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return Crypto(it) }
            if (!allowCreate) throw IOException("The vault encryption key is unavailable")

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM, KeyProperties.BLOCK_MODE_CTR)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return Crypto(generator.generateKey())
        }
    }
}

internal class VerifiedBlob(
    private val input: RandomAccessFile,
    private val key: SecretKey,
    private val iv: ByteArray,
    val size: Long
) : Closeable {
    // Repository writes publish new files by rename. The reader stays attached to
    // the authenticated file descriptor even if the item is updated or deleted.
    fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= size) return -1
        val count = minOf(length.toLong(), size - position).toInt()
        val skip = (position % BLOCK_BYTES).toInt()
        val alignedPosition = position - skip
        val ciphertext = ByteArray(skip + count)
        input.seek(IV_BYTES + alignedPosition)
        input.readFully(ciphertext)
        val plaintext = counterCipher(alignedPosition).doFinal(ciphertext)
        try {
            plaintext.copyInto(destination, offset, skip, skip + count)
        } finally {
            plaintext.fill(0)
        }
        return count
    }

    fun copyTo(output: OutputStream, checkActive: () -> Unit) {
        if (size == 0L) return
        val buffer = ByteArray(BUFFER_BYTES)
        input.seek(IV_BYTES.toLong())
        val counter = counterCipher(0)
        var remaining = size
        while (remaining > 0) {
            checkActive()
            val count = minOf(remaining, buffer.size.toLong()).toInt()
            input.readFully(buffer, 0, count)
            writeAndClear(output, counter.update(buffer, 0, count))
            remaining -= count
        }
        writeAndClear(output, counter.doFinal())
    }

    private fun writeAndClear(output: OutputStream, plaintext: ByteArray?) {
        if (plaintext == null) return
        try {
            output.write(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun counterCipher(position: Long): Cipher {
        // A 96-bit GCM IV starts the payload counter at IV || 0x00000002.
        val counter = iv.copyOf(BLOCK_BYTES)
        val block = (position / BLOCK_BYTES + 2).toInt()
        for (index in 0..3) {
            counter[BLOCK_BYTES - 1 - index] = (block ushr (8 * index)).toByte()
        }
        return Cipher.getInstance(CTR).apply {
            init(Cipher.DECRYPT_MODE, key, IvParameterSpec(counter))
        }
    }

    override fun close() = input.close()
}
