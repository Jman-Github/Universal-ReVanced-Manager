package app.urv.manager.domain.installer.root

import app.urv.manager.domain.installer.RootInstaller

class LibsuRootShellGateway(private val rootInstaller: RootInstaller) : RootShellGateway {
    override suspend fun run(command: String): RootCommandResult =
        rootInstaller.execute(command).toRootCommandResult()

    override suspend fun runBounded(
        command: String,
        timeoutSeconds: Long,
        operation: String
    ): RootCommandResult = rootInstaller.executeBounded(
        command,
        timeoutSeconds,
        operation
    ).toRootCommandResult()

    // A dedicated shell keeps command timeouts from closing the app-wide libsu shell
    // and interrupting unrelated package transactions.
    override suspend fun runIsolatedBounded(
        command: String,
        timeoutSeconds: Long,
        operation: String
    ): RootCommandResult = runBounded(command, timeoutSeconds, operation)

    private fun com.topjohnwu.superuser.Shell.Result.toRootCommandResult() =
        RootCommandResult(code, out.toList(), err.toList())
}
