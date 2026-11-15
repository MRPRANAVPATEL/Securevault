package com.pranav.securevault

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaRecorder
import java.io.File

/** Lets MediaPlayer play decrypted audio straight from memory, so no plain file is written. */
class BytesDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val count = minOf(size, data.size - position.toInt())
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}

/**
 * Records to the app's private cache, then the caller encrypts the file
 * (VaultRepository.addAudioFile) which deletes the plain copy.
 */
class SecureRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private val file = File(context.cacheDir, "rec_${System.currentTimeMillis()}.m4a")

    fun start() {
        val r = MediaRecorder(context)
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(128_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
    }

    /** Stops and returns the recorded (still plain) file, or null if it failed. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        return try {
            r.stop()
            r.release()
            file
        } catch (e: RuntimeException) {
            r.release()
            file.delete()
            null
        }
    }

    /** Aborts an unfinished recording and deletes the file. Does nothing after stop(). */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (e: RuntimeException) {
            // too short to stop cleanly, ignore
        }
        r.release()
        file.delete()
    }
}
