package app.universal.patch.manager.patcher.runtime

import java.util.logging.Level
import java.util.logging.Logger

/** Logging supported by both Android and HotSpot, relayed by the runtime's JUL handler. */
object RuntimeLog {
    fun d(tag: String, message: String) = Logger.getLogger(tag).fine(message)
    fun i(tag: String, message: String) = Logger.getLogger(tag).info(message)
    fun v(tag: String, message: String) = Logger.getLogger(tag).fine(message)
    fun e(tag: String, message: String, error: Throwable?) =
        Logger.getLogger(tag).log(Level.SEVERE, message, error)
}
