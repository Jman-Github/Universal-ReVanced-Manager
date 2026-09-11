package app.urv.manager.domain.installer.root

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

class RootTransactionStore(private val shell: RootShellGateway) : RootTransactionStorage {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    override suspend fun initialize() {
        runStoreCommand(
            "set -eu; " +
                "[ \"$(id -u)\" = 0 ] || { echo 'Root transaction storage requires uid 0' >&2; exit 1; }; " +
                "mkdir -p ${RootPaths.ROOT}/locks ${RootPaths.ROOT}/transactions || " +
                "{ echo 'Failed to create root transaction directories' >&2; exit 1; }; " +
                "chmod 700 ${RootPaths.ROOT} ${RootPaths.ROOT}/locks ${RootPaths.ROOT}/transactions || " +
                "{ echo 'Failed to secure root transaction directories' >&2; exit 1; }"
        ).requireSuccess("Initialize root transaction storage")
    }

    override suspend fun writeActive(journal: RootMountJournal) =
        atomicWrite(RootPaths.active(journal.packageName), json.encodeToString(journal))

    override suspend fun readActive(packageName: String): RootMountJournal? =
        readJson(RootPaths.active(packageName))

    override suspend fun activeExists(packageName: String): Boolean =
        fileExists(
            RootPaths.active(packageName),
            "Inspect root transaction journal"
        )

    override suspend fun clearActive(packageName: String) {
        runStoreCommand(
            "set -eu; rm -f ${shellQuote(RootPaths.active(packageName))}; " +
                "sync -f ${shellQuote(RootPaths.transaction(packageName))} 2>/dev/null || sync"
        )
            .requireSuccess("Clear root transaction journal")
    }

    override suspend fun clearCommitted(packageName: String) {
        runStoreCommand(
            "set -eu; rm -f ${shellQuote(RootPaths.committed(packageName))}; " +
                "sync -f ${shellQuote(RootPaths.transaction(packageName))} 2>/dev/null || sync"
        ).requireSuccess("Clear root mount committed state")
    }

    override suspend fun writeCommitted(state: RootCommittedState) =
        atomicWrite(RootPaths.committed(state.packageName), json.encodeToString(state))

    override suspend fun readCommitted(packageName: String): RootCommittedState? =
        readJson(RootPaths.committed(packageName))

    override suspend fun committedExists(packageName: String): Boolean =
        fileExists(
            RootPaths.committed(packageName),
            "Inspect root mount committed state"
        )

    override suspend fun complete(journal: RootMountJournal, committed: RootCommittedState?) {
        writeActive(
            journal.copy(
                phase = RootMountPhase.COMPLETED,
                completionStateRecorded = true,
                completionCommittedState = committed
            )
        )
        if (committed != null) writeCommitted(committed)
        // Keep the durable COMPLETED marker until the coordinator has also removed the
        // transient module snapshot. Startup recovery can then retry either cleanup step
        // after process death without rolling back the recorded result.
    }

    override suspend fun appendDiagnostic(packageName: String, diagnosticId: String, message: String) {
        val safe = "${System.currentTimeMillis()} [$diagnosticId] ${message.replace('\n', ' ')}\n"
        val encoded = Base64.getEncoder().encodeToString(safe.toByteArray())
        val path = RootPaths.diagnostics(packageName)
        runStoreCommand(
            "set -eu; mkdir -p ${shellQuote(RootPaths.transaction(packageName))}; " +
                "printf %s ${shellQuote(encoded)} | base64 -d >> ${shellQuote(path)}; " +
                "chmod 600 ${shellQuote(path)}; " +
                "sync -f ${shellQuote(path)} 2>/dev/null || sync"
        ).requireSuccess("Write root mount diagnostic")
    }

    override suspend fun markRepatchRequired(packageName: String, reason: String): RootCommittedState? {
        val existing = readCommitted(packageName) ?: return null
        val updated = existing.copy(active = false, status = "REPATCH_REQUIRED")
        writeCommitted(updated)
        appendDiagnostic(packageName, "repatch-${System.currentTimeMillis()}", reason)
        return updated
    }

    override suspend fun listIncompletePackages(): List<String> {
        return listPackagesWith("active.json")
    }

    override suspend fun listCommittedPackages(): List<String> {
        return listPackagesWith("committed.json")
    }

    private suspend fun listPackagesWith(fileName: String): List<String> {
        val result = runStoreCommand(
            "set -eu; for f in ${RootPaths.ROOT}/transactions/*/${shellQuote(fileName)}; do " +
                "[ -f \"${'$'}f\" ] || continue; " +
                "basename \"${'$'}(dirname \"${'$'}f\")\"; done"
        )
        result.requireSuccess("List root transaction state")
        return result.stdout.map(String::trim).filter(String::isNotEmpty)
    }

    override suspend fun exportDiagnostics(packageName: String): String {
        val activePath = RootPaths.active(packageName)
        val committedPath = RootPaths.committed(packageName)
        val diagnosticsPath = RootPaths.diagnostics(packageName)
        val bootStatusPath = "${RootPaths.transaction(packageName)}/boot-status"
        val moduleStatePath = "${RootPaths.module(packageName)}/state.env"
        val moduleLogPath = "${RootPaths.module(packageName)}/log.txt"

        val command = buildString {
            append("echo '------------'; echo 'Information:'; echo '------------'; ")
            append("printf 'Package: %s\\n' ${shellQuote(packageName)}; ")
            append("printf 'Generated (UTC): '; ")
            append("date -u '+%Y-%m-%d %H:%M:%S UTC' 2>/dev/null || date; ")
            append("echo 'Event timestamps use Unix epoch milliseconds.'; ")
            append("echo 'Each source is limited to its newest 32 KiB.'; ")

            appendFileSection("Committed Mount State", committedPath)
            appendFileSection("Active Transaction", activePath)
            appendFileSection("Recent Diagnostic Events", diagnosticsPath, indentTabs = true)
            appendFileSection("Boot Status", bootStatusPath)
            appendFileSection("Root Module State", moduleStatePath)
            appendFileSection("Root Module Service Log", moduleLogPath)

            append("echo; echo '------------'; echo 'Active Mount Layers:'; echo '------------'; ")
            append("echo 'Source: /proc/self/mountinfo'; ")
            append("grep -F ${shellQuote(packageName)} /proc/self/mountinfo 2>/dev/null || ")
            append("echo 'No active mount layers found.'; ")
        }

        val result = runStoreCommand(command)
        result.requireSuccess("Read root mount diagnostics")
        return result.output.trimEnd() + "\n"
    }

    private fun StringBuilder.appendFileSection(
        title: String,
        path: String,
        indentTabs: Boolean = false
    ) {
        append("echo; echo '------------'; printf '%s:\\n' ${shellQuote(title)}; echo '------------'; ")
        append("printf 'Source: %s\\n' ${shellQuote(path)}; ")
        append("if [ -f ${shellQuote(path)} ]; then ")
        if (indentTabs) {
            append("tail -c 32768 ${shellQuote(path)} | ")
            append("awk '{ gsub(/ Caused by: /, \"\\nCaused by: \"); ")
            append("gsub(/ Suppressed: /, \"\\nSuppressed: \"); ")
            append("gsub(/\\t/, \"\\n    \"); print; print \"\" }'; ")
        } else {
            append("tail -c 32768 ${shellQuote(path)}; echo; ")
        }
        append("else echo 'Not present.'; fi; ")
    }

    private suspend fun runStoreCommand(command: String): RootCommandResult =
        shell.runIsolatedBounded(
            command,
            STORE_TIMEOUT_SECONDS,
            "root transaction storage"
        )

    private suspend inline fun <reified T> readJson(path: String): T? {
        val result = runStoreCommand(
            "set -eu; if [ -f ${shellQuote(path)} ]; then cat ${shellQuote(path)}; " +
                "else exit $MISSING_JSON_STATUS; fi"
        )
        if (result.status == MISSING_JSON_STATUS) return null
        result.requireSuccess("Read ${path.substringAfterLast('/')}")
        if (result.stdout.isEmpty()) return null
        return runCatching { json.decodeFromString<T>(result.stdout.joinToString("\n")) }.getOrNull()
    }

    private suspend fun fileExists(path: String, operation: String): Boolean {
        val result = runStoreCommand(
            "if [ -f ${shellQuote(path)} ]; then printf '1\\n'; else printf '0\\n'; fi"
        )
        result.requireSuccess(operation)
        return when (result.stdout.singleOrNull()?.trim()) {
            "1" -> true
            "0" -> false
            else -> error("$operation returned an invalid result")
        }
    }

    private suspend fun atomicWrite(path: String, content: String) {
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray())
        val temp = "$path.tmp"
        val parent = path.substringBeforeLast('/')
        runStoreCommand(
            "set -eu; mkdir -p ${shellQuote(parent)}; chmod 700 ${shellQuote(parent)}; " +
                "printf %s ${shellQuote(encoded)} | base64 -d > ${shellQuote(temp)}; " +
                "chmod 600 ${shellQuote(temp)}; " +
                "sync -f ${shellQuote(temp)} 2>/dev/null || sync; " +
                "mv -f ${shellQuote(temp)} ${shellQuote(path)}; " +
                "sync -f ${shellQuote(parent)} 2>/dev/null || sync"
        ).requireSuccess("Atomically write ${path.substringAfterLast('/')}")
    }

    private companion object {
        const val MISSING_JSON_STATUS = 44
        const val STORE_TIMEOUT_SECONDS = 30L
    }
}
