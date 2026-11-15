package com.pranav.securevault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption. The key is generated inside the Android Keystore
 * (hardware-backed on Samsung phones) and never leaves the device.
 * File format: [12-byte IV][ciphertext + GCM tag]
 */
object Crypto {
    private const val KEY_ALIAS = "securevault_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Wraps [out] so everything written to it is encrypted. Close it to finish. */
    fun encryptStream(out: OutputStream): OutputStream {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        out.write(cipher.iv)
        return CipherOutputStream(out, cipher)
    }

    /** Wraps [input] so everything read from it is decrypted. */
    fun decryptStream(input: InputStream): InputStream {
        val iv = ByteArray(IV_LENGTH)
        var read = 0
        while (read < IV_LENGTH) {
            val n = input.read(iv, read, IV_LENGTH - read)
            if (n < 0) throw IllegalStateException("Vault file is corrupted")
            read += n
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return CipherInputStream(input, cipher)
    }

    fun encryptBytes(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(data)
    }

    fun decryptBytes(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, blob, 0, IV_LENGTH)
        )
        return cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH)
    }
}
