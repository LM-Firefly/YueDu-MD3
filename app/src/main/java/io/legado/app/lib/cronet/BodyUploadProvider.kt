package io.legado.app.lib.cronet

import androidx.annotation.Keep
import io.legado.app.utils.DebugLog
import okhttp3.RequestBody
import okio.Buffer
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import java.io.IOException
import java.nio.ByteBuffer

@Keep
class BodyUploadProvider(private val body: RequestBody) : UploadDataProvider(), AutoCloseable {

    private val buffer = Buffer()

    @Volatile
    private var filled: Boolean = false

    init {
        fillBuffer()
    }

    private fun fillBuffer() {
        try {
            buffer.clear()
            filled = true
            body.writeTo(buffer)
            buffer.flush()
        } catch (e: Exception) {
            DebugLog.e("BodyUploadProvider", "fillBuffer failed", e)
        }
    }

    @Throws(IOException::class)
    override fun getLength(): Long {
        return body.contentLength()
    }

    @Throws(IOException::class)
    override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        if (!filled) {
            fillBuffer()
        }
        check(byteBuffer.hasRemaining()) { "Cronet passed a buffer with no bytes remaining" }
        val read = buffer.read(byteBuffer)
        if (read == -1) {
            uploadDataSink.onReadSucceeded(true)
        } else {
            uploadDataSink.onReadSucceeded(false)
        }
    }

    @Throws(IOException::class)
    override fun rewind(uploadDataSink: UploadDataSink) {
        check(!body.isOneShot()) { "Cannot rewind one-shot RequestBody" }
        filled = false
        fillBuffer()
        uploadDataSink.onRewindSucceeded()
    }

    @Throws(IOException::class)
    override fun close() {
        buffer.close()
        super.close()
    }
}
