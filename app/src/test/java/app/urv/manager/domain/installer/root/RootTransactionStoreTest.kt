package app.urv.manager.domain.installer.root

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootTransactionStoreTest {
    @Test
    fun `journal state is synchronized before atomic rename`() = runBlocking {
        val shell = RecordingStoreShell()
        val store = RootTransactionStore(shell)

        store.writeActive(journal())

        val command = shell.commands.single()
        val write = command.indexOf("active.json.tmp")
        val syncFile = command.indexOf("sync -f", write)
        val rename = command.indexOf("mv -f", syncFile)
        val syncParent = command.indexOf("sync -f", rename)
        assertTrue(write >= 0)
        assertTrue(syncFile > write)
        assertTrue(rename > syncFile)
        assertTrue(syncParent > rename)
    }

    @Test
    fun `truncated active journal remains detectable for conservative recovery`() = runBlocking {
        val shell = RecordingStoreShell(truncatedActive = true)
        val store = RootTransactionStore(shell)

        assertTrue(store.activeExists(PACKAGE))
        assertNull(store.readActive(PACKAGE))
    }

    @Test
    fun `missing journal still reads as absent`() = runBlocking {
        val store = RootTransactionStore(RecordingStoreShell())

        assertNull(store.readActive(PACKAGE))
    }

    @Test
    fun `existence check failure is not treated as file absence`(): Unit = runBlocking {
        val shell = RecordingStoreShell(failExistenceCheck = true)
        val store = RootTransactionStore(shell)

        assertFailsWith<RootCommandException> {
            store.committedExists(PACKAGE)
        }
    }

    @Test
    fun `journal read failure is not treated as missing or corrupt state`(): Unit = runBlocking {
        val shell = RecordingStoreShell(failRead = true)
        val store = RootTransactionStore(shell)

        assertFailsWith<RootCommandException> {
            store.readActive(PACKAGE)
        }
    }

    @Test
    fun `transaction listing failure is not treated as an empty store`(): Unit = runBlocking {
        val shell = RecordingStoreShell(failList = true)
        val store = RootTransactionStore(shell)

        assertFailsWith<RootCommandException> {
            store.listIncompletePackages()
        }
    }

    @Test
    fun `completed journal remains until snapshot cleanup is confirmed`() = runBlocking {
        val shell = RecordingStoreShell()
        val store = RootTransactionStore(shell)

        store.complete(journal(), null)

        assertTrue(shell.commands.none { it.startsWith("set -eu; rm -f") && it.contains("active.json") })
    }

    @Test
    fun `diagnostics export uses readable named sections`() = runBlocking {
        val shell = RecordingStoreShell()
        val store = RootTransactionStore(shell)

        store.exportDiagnostics(PACKAGE)

        val command = shell.commands.single()
        assertTrue(command.contains("Information:"))
        assertTrue(command.contains("Committed Mount State"))
        assertTrue(command.contains("Recent Diagnostic Events"))
        assertTrue(command.contains("Root Module State"))
        assertTrue(command.contains("Active Mount Layers:"))
        assertTrue(command.contains("gsub(/ Caused by: /"))
        assertTrue(command.contains("gsub(/\\t/"))
        assertTrue(command.contains("Not present."))
    }

    private class RecordingStoreShell(
        private val truncatedActive: Boolean = false,
        private val failExistenceCheck: Boolean = false,
        private val failRead: Boolean = false,
        private val failList: Boolean = false
    ) : RootShellGateway {
        val commands = mutableListOf<String>()

        override suspend fun run(command: String): RootCommandResult {
            commands += command
            return when {
                command.startsWith("set -eu; if [ -f") && failRead ->
                    RootCommandResult(-1, emptyList(), emptyList())
                command.startsWith("set -eu; if [ -f") && truncatedActive ->
                    success("{\"transactionId\":")
                command.startsWith("set -eu; if [ -f") ->
                    RootCommandResult(44, emptyList(), emptyList())
                command.startsWith("if [ -f") && failExistenceCheck ->
                    RootCommandResult(1, emptyList(), listOf("existence check failed"))
                command.startsWith("if [ -f") && truncatedActive -> success("1")
                command.startsWith("if [ -f") -> success("0")
                command.startsWith("set -eu; for f in ") && failList ->
                    RootCommandResult(-1, emptyList(), emptyList())
                else -> success()
            }
        }

        private fun success(vararg output: String) = RootCommandResult(0, output.toList(), emptyList())
    }

    private companion object {
        const val PACKAGE = "com.example.app"

        fun journal() = RootMountJournal(
            transactionId = "tx",
            packageName = PACKAGE,
            userId = 0,
            operation = RootMountOperation.SWITCH_PATCHED_BUILD,
            phase = RootMountPhase.PREPARING,
            startedAtEpochMs = 1
        )
    }
}
