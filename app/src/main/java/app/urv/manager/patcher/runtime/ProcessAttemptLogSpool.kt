package app.urv.manager.patcher.runtime

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores one child-process attempt's logs outside the manager heap so a failed attempt can be
 * discarded without exposing retry details to the user.
 */
internal class ProcessAttemptLogSpool private constructor(
    private val file: File
) : Closeable {
    private val lock = Any()
    private var writer: BufferedWriter? = file.bufferedWriter(Charsets.UTF_8)

    fun append(line: String) {
        synchronized(lock) {
            checkNotNull(writer) { "Attempt log spool is already closed" }.apply {
                write(line)
                newLine()
            }
        }
    }

    suspend fun replayTo(onLog: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            finishWriting()
            file.useLines(Charsets.UTF_8) { lines ->
                lines.forEach(onLog)
            }
        }
    }

    private fun finishWriting() {
        val activeWriter = synchronized(lock) {
            writer.also { writer = null }
        }
        activeWriter?.close()
    }

    override fun close() {
        runCatching { finishWriting() }
        runCatching { file.delete() }
    }

    companion object {
        fun create(directory: File, prefix: String): ProcessAttemptLogSpool {
            directory.mkdirs()
            return ProcessAttemptLogSpool(
                File.createTempFile(prefix, ".log", directory)
            )
        }
    }
}
