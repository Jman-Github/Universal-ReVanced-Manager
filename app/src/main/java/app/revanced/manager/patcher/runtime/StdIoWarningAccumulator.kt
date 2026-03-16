package app.revanced.manager.patcher.runtime

internal class StdIoWarningAccumulator(
    private val emit: (String) -> Unit
) {
    private val pendingLines = mutableListOf<String>()
    private var collectingStackTrace = false

    fun onLine(rawLine: String) {
        val line = rawLine.trimEnd()
        if (line.isBlank()) {
            flush()
            return
        }

        if (collectingStackTrace && isStackTraceContinuation(line)) {
            pendingLines += line
            return
        }

        flush()

        pendingLines += line
        collectingStackTrace = isStackTraceStart(line)
        if (!collectingStackTrace) {
            flush()
        }
    }

    fun flush() {
        if (pendingLines.isEmpty()) {
            collectingStackTrace = false
            return
        }

        emit(pendingLines.joinToString(separator = "\n"))
        pendingLines.clear()
        collectingStackTrace = false
    }

    private fun isStackTraceStart(line: String): Boolean {
        val trimmed = line.trimStart()
        return stackTraceHeader.matches(trimmed) || trimmed.startsWith("Exception in thread ")
    }

    private fun isStackTraceContinuation(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("at ") ||
            trimmed.startsWith("... ") ||
            trimmed.startsWith("Caused by:") ||
            trimmed.startsWith("Suppressed:")
    }

    private companion object {
        val stackTraceHeader = Regex(
            pattern = """^(?:[\w.$]+(?:Exception|Error|Throwable)(?::|$)|Caused by:.*)$"""
        )
    }
}
