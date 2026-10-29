package app.universal.patch.manager.patcher.runtime

import app.universal.patch.manager.patcher.RemoteError
import java.io.IOException

/** Keeps a terminal engine error comparable with its earlier progress failure. */
class BoundRuntimeFailureException(message: String, val originalStackTrace: String? = null) : IOException(message) {
    fun toRemoteError() = RemoteError(
        type = javaClass.name,
        message = if (originalStackTrace != null) message?.lineSequence()?.firstOrNull() else message,
        stackTrace = originalStackTrace ?: stackTraceToString()
    )
}
