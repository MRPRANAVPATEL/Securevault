package com.pranav.securevault

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException

internal class BytesDataSource(private val source: VerifiedBlob) : MediaDataSource() {
    private val buffer = ByteArray(64 * 1024)
    private var bufferStart = -1L
    private var buffered = 0
    private var closed = false

    @Synchronized
    override fun readAt(position: Long, destination: ByteArray, offset: Int, size: Int): Int {
        if (closed) throw IOException("Audio source is closed")
        require(position >= 0 && offset >= 0 && size >= 0 && size <= destination.size - offset)
        if (size == 0) return 0
        if (position >= source.size) return -1
        var copied = 0
        while (copied < size && position + copied < source.size) {
            val cursor = position + copied
            if (cursor < bufferStart || cursor - bufferStart >= buffered) {
                buffer.fill(0)
                val block = cursor - cursor % buffer.size
                buffered = source.readAt(block, buffer, 0, buffer.size)
                bufferStart = block
            }
            val start = (cursor - bufferStart).toInt()
            val count = minOf(size - copied, buffered - start)
            buffer.copyInto(destination, offset + copied, start, start + count)
            copied += count
        }
        return copied
    }

    @Synchronized
    override fun getSize(): Long {
        if (closed) throw IOException("Audio source is closed")
        return source.size
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        buffer.fill(0)
        source.close()
    }
}

internal class SecureRecorder(private val context: Context, private val onError: () -> Unit) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    fun start() {
        check(recorder == null)
        val target = File.createTempFile(PREFIX, ".m4a", context.cacheDir)
        file = target
        try {
            val media = createRecorder()
            recorder = media
            media.setOnErrorListener { current, _, _ ->
                if (recorder === current) onError()
            }
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioEncodingBitRate(128_000)
            media.setOutputFile(target.path)
            media.prepare()
            media.start()
        } catch (failure: Exception) {
            release()
            throw failure
        }
    }

    fun finish(): File {
        val media = checkNotNull(recorder)
        val target = checkNotNull(file)
        try {
            media.stop()
            if (target.length() == 0L) throw IOException("No audio was recorded")
            file = null
            return target
        } finally {
            release()
        }
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        release()
    }

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
        file?.delete()
        file = null
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) {
        MediaRecorder(context)
    } else {
        MediaRecorder()
    }

    companion object {
        private const val PREFIX = "securevault-recording-"
        private var cleaned = false

        @Synchronized
        fun removeAbandonedRecordings(cache: File) {
            if (cleaned) return
            cache.listFiles { file -> file.name.startsWith(PREFIX) && file.extension == "m4a" }
                ?.forEach { it.delete() }
            cleaned = true
        }
    }
}
