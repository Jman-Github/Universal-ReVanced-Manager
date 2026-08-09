package app.urv.manager.patcher.logger

import androidx.annotation.StringRes
import app.universal.revanced.manager.R
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

abstract class Logger {
    abstract fun log(level: LogLevel, message: String)

    fun trace(msg: String) = log(LogLevel.TRACE, msg)
    fun info(msg: String) = log(LogLevel.INFO, msg)
    fun warn(msg: String) = log(LogLevel.WARN, msg)
    fun error(msg: String) = log(LogLevel.ERROR, msg)

    val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            val msg = record.message

            when (record.level) {
                Level.INFO -> info(msg)
                Level.SEVERE -> error(msg)
                Level.WARNING -> warn(msg)
                else -> trace(msg)
            }
        }

        override fun flush() = Unit
        override fun close() = Unit
    }
}

enum class LogLevel {
    TRACE,
    INFO,
    WARN,
    ERROR,
}

private val apkEditorFileWriteLogPattern = Regex("""^Write\s+\[[^]]+]\s+.+""")

fun isVerbosePatcherExportLog(level: LogLevel, message: String): Boolean {
    if (level != LogLevel.TRACE && level != LogLevel.INFO) return false

    val trimmed = message.trimStart()
    return trimmed.startsWith("Added:") ||
        trimmed.startsWith("Added [") ||
        trimmed.startsWith("Loading:") ||
        trimmed.startsWith("ORDER:") ||
        apkEditorFileWriteLogPattern.matches(trimmed)
}

fun isVerbosePatcherExportLog(line: String): Boolean {
    val level = LogLevel.entries.firstOrNull { line.startsWith("[${it.name}]: ") }
        ?: return false
    return isVerbosePatcherExportLog(level, line.substringAfter("]: ", line))
}

enum class PatcherLogMode(
    @get:StringRes val displayName: Int,
    val minLogLevel: LogLevel,
    val javaLogLevel: Level
) {
    DEFAULT(R.string.patcher_log_mode_default, LogLevel.INFO, Level.INFO),
    VERBOSE(R.string.patcher_log_mode_verbose, LogLevel.TRACE, Level.ALL),
}

fun PatcherLogMode.allows(level: LogLevel): Boolean = level.ordinal >= minLogLevel.ordinal

fun Logger.filtered(mode: PatcherLogMode) = object : Logger() {
    override fun log(level: LogLevel, message: String) {
        if (!mode.allows(level)) return
        this@filtered.log(level, message)
    }
}

inline fun <T> Logger.withJavaLogging(mode: PatcherLogMode, block: () -> T): T {
    val rootLogger = java.util.logging.Logger.getLogger("")
    val previousLevel = rootLogger.level
    val oldHandlers = rootLogger.handlers.toList()
    rootLogger.level = mode.javaLogLevel

    oldHandlers.forEach {
        rootLogger.removeHandler(it)
    }
    rootLogger.addHandler(handler)

    return try {
        block()
    } finally {
        rootLogger.removeHandler(handler)
        oldHandlers.forEach(rootLogger::addHandler)
        rootLogger.level = previousLevel
    }
}
