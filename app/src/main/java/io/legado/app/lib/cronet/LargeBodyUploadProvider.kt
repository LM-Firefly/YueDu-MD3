package io.legado.app.lib.cronet

import androidx.annotation.Keep
import okhttp3.RequestBody
import okio.BufferedSource
import okio.Pipe
import okio.buffer
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService

/**
 * 用于上传大型文件
 *
 * @property body
 * @property executorService
 */
@Keep
class LargeBodyUploadProvider(
    private val body: RequestBody,
    private val executorService: ExecutorService
) : UploadDataProvider(), AutoCloseable {
    private val pipe = Pipe(BUFFER_SIZE.toLong())
    private var source: BufferedSource = pipe.source.buffer()

    @Volatile
    private var writeSubmitted: Boolean = false
    @Volatile
    private var writeFailed: Boolean = false
    override fun getLength(): Long {
        return body.contentLength()
    }

    override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        check(byteBuffer.hasRemaining()) { "Cronet passed a buffer with no bytes remaining" }
        if (!writeSubmitted) {
            fillBuffer()
        }
        if (writeFailed) {
            throw IOException("Upload body write failed")
        }
        val read = source.read(byteBuffer)
        if (read == -1) {
            uploadDataSink.onReadSucceeded(true)
        } else {
            uploadDataSink.onReadSucceeded(false)
        }
    }

    @Synchronized
    private fun fillBuffer() {
        writeSubmitted = true
        executorService.submit {
            try {
                pipe.sink.buffer().use { writeSink ->
                    body.writeTo(writeSink)
                }
            } catch (e: Exception) {
                writeFailed = true
                e.printStackTrace()
            }
        }
    }

    override fun rewind(p0: UploadDataSink?) {
        check(!body.isOneShot()) { "Cannot rewind one-shot RequestBody" }
        writeSubmitted = false
        writeFailed = false
        fillBuffer()
    }

    override fun close() {
        source.close()
        super.close()
    }
}
