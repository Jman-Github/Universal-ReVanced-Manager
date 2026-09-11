package app.urv.manager.domain.installer.root

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootPackageLockContractTest {
    @Test
    fun `interactive and boot operations share one atomic package lock`() = runBlocking {
        val shell = LockShell()
        val lock = RootPackageLock(shell, { OWNER_PID }, { OWNER_UID })

        val held = lock.acquire(PACKAGE, "one")
        val busy = assertFailsWith<IllegalStateException> {
            lock.acquire(PACKAGE, "two")
        }

        assertEquals(RootPaths.lock(PACKAGE), "/data/adb/urv/locks/$PACKAGE.lock.d")
        assertTrue(shell.commands.first().contains(RootPaths.lock(PACKAGE)))
        assertTrue(shell.commands.first().contains("/proc/\$owner_pid/stat"))
        assertTrue(shell.commands.first().contains("owner_start"))
        assertTrue(shell.commands.first().contains("transaction_id"))
        assertTrue(shell.commands.first().contains("active_path="))
        assertTrue(shell.commands.first().contains("app_uid="))
        assertTrue(shell.commands.first().contains("current_uid"))
        assertTrue(shell.commands.first().contains("different transaction owned by this same process is an orphaned lock"))
        assertTrue(shell.commands.first().contains("current_uid\" = 0"))
        assertTrue(shell.commands.first().contains("boot_service_owner"))
        assertTrue(shell.commands.first().contains("/data/adb/modules/*/service.sh"))
        assertTrue(shell.commands.first().contains("Root module service scripts must always defer"))
        assertTrue(shell.commands.first().contains("kill -TERM"))
        assertTrue(shell.commands.first().contains("kill -KILL"))
        assertTrue(shell.commands.first().contains("mkdir \"\$lock_path\""))
        assertFalse(shell.commands.first().contains("flock"))
        assertFalse(shell.commands.first().contains("readiness"))
        assertTrue(busy.message.orEmpty().contains("pid 9001"))
        assertTrue(busy.message.orEmpty().contains("transaction legacy-boot"))
        requireNotNull(held)
        lock.release(held)

        val service = asset("service.sh").readText()
        assertTrue(service.contains("\$lock_dir/\$URV_PACKAGE.lock.d"))
        assertTrue(service.contains("try_acquire_package_lock"))
        assertTrue(service.contains("release_package_lock"))
        assertTrue(service.contains("trap '[ \"\$boot_lock_held\" = 0 ] || release_package_lock"))
        val incompleteCheck = service.indexOf("if [ -f \"\$transaction_dir/active.json\" ]; then")
        val lockAcquire = service.indexOf("if ! acquire_ready_package_lock; then")
        assertTrue(incompleteCheck >= 0 && lockAcquire > incompleteCheck)
        val incompleteBlock = service.substring(incompleteCheck, lockAcquire)
        assertTrue(incompleteBlock.contains("deferring entirely to Manager recovery"))
        assertFalse(incompleteBlock.contains("remove_target_mounts"))
        assertFalse(service.contains("flock"))

        val lockSource = rootSource("RootPackageLock.kt").readText()
        assertTrue(lockSource.contains("shell.runBounded"))
        assertTrue(lockSource.contains("LOCK_COMMAND_TIMEOUT_SECONDS = 10L"))
        assertTrue(lockSource.contains("STALE_OWNER_GRACE_SECONDS = 5"))
    }

    @Test
    fun `transient lock shell failure is retried once`() = runBlocking {
        val shell = RetryingLockShell()
        val lock = RootPackageLock(shell, { OWNER_PID }, { OWNER_UID })

        val held = requireNotNull(lock.acquire(PACKAGE, "retry"))

        assertEquals(2, shell.acquireCalls)
        lock.release(held)
        assertTrue(shell.released)
    }
    @Test
    fun `post fs data defers mounting until user ownership can be verified`() {
        val script = asset("post-fs-data.sh").readText()

        assertTrue(script.contains("Mount deferred until Android user ownership can be verified"))
        assertFalse(script.contains("flock"))
        assertFalse(script.contains("mount -o bind"))
        assertFalse(script.contains("umount"))
    }

    @Test
    fun `late boot service fails closed without covering foreign zygote mounts`() {
        val late = asset("service.sh").readText()

        assertTrue(late.contains("[ \"\$stock_hash\" != \"\$URV_STOCK_SHA256\" ]"))
        assertTrue(late.contains("pidof zygote64 2>/dev/null || true"))
        assertTrue(late.contains("pidof zygote 2>/dev/null || true"))
        assertTrue(late.contains("awk '/^[0-9]+${'$'}/ && ${'$'}0 > 1'"))
        assertTrue(late.contains("validate_zygote \"\$pid\" || continue"))
        assertTrue(late.contains("namespace_mount_ownership"))
        assertTrue(late.contains("mountinfo_root_alias"))
        assertTrue(late.contains("known_mount_sources"))
        assertTrue(late.contains("${'$'}{1#/data}"))
        assertTrue(late.contains("target_payload_layer_counts"))
        assertTrue(late.contains("root_mount_layout_valid"))
        assertTrue(late.contains("namespace_matches_shadow"))
        assertTrue(late.contains("mount -o private none \"\$URV_STOCK_PATH\""))
        assertTrue(late.contains("nsenter --mount=\"/proc/\$pid/ns/mnt\" -- mount -o private none"))
        assertTrue(late.contains("post_mount_shadow_hash"))
        assertTrue(late.contains("[ \"\$total_mounts\" -le 8 ]"))
        assertFalse(late.contains("late_mount_count\" != \"\$expected_mount_count"))
        assertTrue(late.contains("installed_user_ids"))
        assertTrue(late.contains("Package is installed for another Android user"))
        val bootReadiness = late.substringAfter("boot_waited=0")
            .substringBefore("installed_users=\"\$(installed_user_ids)\"")
        assertTrue(bootReadiness.contains("getprop sys.boot_completed"))
        assertTrue(bootReadiness.contains("[ \"\$boot_waited\" -lt 300 ]"))
        assertTrue(bootReadiness.contains("Waiting for Android boot completion before ownership verification"))
        assertTrue(bootReadiness.contains("Android boot did not complete; deferring mount verification"))
        assertFalse(bootReadiness.contains("disable_module"))
        assertTrue(late.indexOf("boot_waited=0") < late.indexOf(". \"\$state_file\""))
        assertTrue(late.indexOf("boot_waited=0") < late.indexOf("if ! acquire_ready_package_lock; then"))
        val lockedStateReload = shellFunction(late, "acquire_ready_package_lock")
        assertTrue(lockedStateReload.contains("load_state || return 1"))
        assertTrue(lockedStateReload.contains("[ \"\$URV_PACKAGE\" = \"\$locked_package\" ]"))
        assertTrue(lockedStateReload.indexOf("acquire_package_lock || return 1") <
            lockedStateReload.indexOf("load_state || return 1"))
        assertTrue(lockedStateReload.indexOf("load_state || return 1") <
            lockedStateReload.indexOf("read_package_state && return 0"))
        assertTrue(lockedStateReload.contains("[ ! -f \"\$MODDIR/disable\" ]"))
        assertTrue(lockedStateReload.contains("[ ! -f \"\$MODDIR/remove\" ]"))
        assertTrue(lockedStateReload.contains("INCOMPLETE_TRANSACTION"))
        assertTrue(lockedStateReload.contains("while wait_for_package_manager \"\$recovery_started\"; do"))
        assertTrue(lockedStateReload.contains("release_package_lock || return 1"))
        assertTrue(lockedStateReload.indexOf("release_package_lock || return 1") <
            lockedStateReload.indexOf("sleep 5"))
        val ownershipReadiness = shellFunction(late, "wait_for_package_manager")
        assertTrue(ownershipReadiness.contains("ready_now - ready_started"))
        assertTrue(ownershipReadiness.contains("-lt 300 ] || return 1"))
        assertTrue(ownershipReadiness.contains("read_package_state && return 0"))
        assertTrue(ownershipReadiness.contains("sleep 5"))
        assertFalse(ownershipReadiness.contains("disable_module"))
        val packageReadiness = shellFunction(late, "read_package_state")
        assertTrue(packageReadiness.contains("installed_users=\"\$(installed_user_ids)\" || return 1"))
        assertTrue(packageReadiness.contains("timeout 10 pm path --user \"\$URV_USER_ID\" \"\$URV_PACKAGE\""))
        assertTrue(packageReadiness.contains("[ -n \"\$current_path\" ] || return 1"))
        assertTrue(packageReadiness.contains("timeout 10 dumpsys package"))
        assertTrue(packageReadiness.contains("timeout 10 cmd package resolve-activity"))
        assertFalse(packageReadiness.contains("disable_module"))
        assertTrue(late.contains("live_zygote_pids"))
        assertTrue(late.contains("nsenter --mount=\"/proc/\$pid/ns/mnt\" -- awk"))
        assertTrue(late.contains("nsenter --mount=\"/proc/\$pid/ns/mnt\" -- mount -o bind"))
        assertTrue(late.contains("nsenter --mount=\"/proc/\$pid/ns/mnt\" -- stat -c"))
        assertTrue(late.contains("nsenter --mount=\"/proc/\$pid/ns/mnt\" -- umount"))
        assertTrue(late.contains("nsenter --mount=\"/proc/\$pid/ns/mnt\" -- umount -l"))
        assertTrue(late.contains("if ! umount \"\$URV_STOCK_PATH\"; then"))
        val rootCleanup = late.substringAfter("remove_target_mounts() {")
            .substringBefore("\n}\n\nremove_failed_urv_mounts()")
        assertEquals(
            2,
            Regex.escape("ownership=\"\$(target_mount_counts)\" || return 1")
                .toRegex()
                .findAll(rootCleanup)
                .count()
        )
        val failedCleanup = late.substringAfter("remove_failed_urv_mounts() {")
            .substringBefore("\n}\n\nstop_and_wait()")
        assertEquals(
            2,
            Regex.escape("ownership=\"\$(target_mount_counts)\" || return 1")
                .toRegex()
                .findAll(failedCleanup)
                .count()
        )
        assertEquals(
            2,
            Regex.escape("[ \"\$total_mounts\" = 1 ] && target_matches_urv_inode && urv_mounts=1")
                .toRegex()
                .findAll(late)
                .count()
        )
        assertTrue(late.contains("namespace_visible_matches_urv_inode \"\$pid\" || return 1"))
        assertTrue(late.contains("[ \"\$attempts\" -lt 16 ]"))
        assertFalse(late.contains("nsenter -t"))
        assertTrue(late.contains("/proc/\$pid/cmdline"))
        assertTrue(late.contains("zygote|zygote64|zygote\\ *|zygote64\\ *"))
        assertFalse(late.contains("/proc/\$1/comm"))
        assertTrue(late.contains("while [ \"\$preflight_attempt\" -lt 20 ]"))
        assertTrue(late.contains("while [ \"\$mount_attempt\" -lt 20 ]"))
        assertTrue(late.contains("validate_zygote \"\$pid\" || { zygote_changed=1; continue; }"))
        assertTrue(late.contains("Zygote namespace verification failed; retrying after package quiescence"))
        assertTrue(late.contains("Unable to quiesce package; existing root mount left for Manager recovery"))
        assertTrue(late.contains("Early Zygote namespace repair succeeded"))
        assertFalse(late.contains("validate_zygote \"\$pid\" || return 1"))
        assertTrue(late.contains("elif namespace_has_urv_layer \"\$pid\"; then"))
        assertTrue(late.contains("A foreign-only mount is unrelated to URV"))
        assertTrue(late.contains("Late patched bind mount failed; removing any stock-shadow layer"))
        assertTrue(late.contains("if remove_failed_urv_mounts && ! target_is_mounted; then"))
        assertTrue(late.contains("repair_module_payloads"))
        assertTrue(late.contains("\$transaction_dir/backup/payload"))
        assertTrue(late.contains("Restored altered module payloads from verified canonical copies"))
        assertTrue(late.contains("Compatibility mismatch: patched payload changed"))
        assertTrue(late.contains("Compatibility mismatch: stock-shadow payload changed"))

        val zygoteCleanup = late.substringAfter("remove_zygote_payload_mounts() {")
            .substringBefore("\n}\n\nboot_waited=0")
        assertTrue(zygoteCleanup.trimEnd().endsWith("return 0"))

        val incompleteRecovery = late.substringAfter("if [ -f \"\$transaction_dir/active.json\" ]; then")
            .substringBefore("\nfi")
        assertTrue(incompleteRecovery.contains("INCOMPLETE_TRANSACTION"))
        assertFalse(incompleteRecovery.contains("if target_is_mounted; then"))
        assertFalse(incompleteRecovery.contains("remove_target_mounts"))
        assertEquals(
            2,
            Regex("""if ! stop_and_wait \|\| ! remove_target_mounts; then""")
                .findAll(late)
                .count()
        )
    }

    @Test
    fun `root transaction commands reuse an isolated mount master shell`() {
        val gateway = rootSource("RootShellGateway.kt").readText()
        val bounded = gateway.substringAfter("override suspend fun runBounded(")
            .substringBefore("private fun")
        val installer = source("RootInstaller.kt").readText()
        val ordinary = installer.substringAfter("suspend fun execute(command: String)")
            .substringBefore("suspend fun executeSharedBounded(")
        val dedicated = installer.substringAfter("suspend fun executeBounded(")
            .substringBefore("private fun execute(")
        val rootCheck = installer.substringAfter("fun hasRootAccess(")
            .substringBefore("private fun liveRootShellAvailable(")

        assertTrue(bounded.contains("executeBounded("))
        assertFalse(bounded.contains("executeSharedBounded"))
        assertTrue(installer.contains("private val boundedShellMutex = Mutex()"))
        assertTrue(installer.contains("@Volatile private var boundedShell: Shell? = null"))
        assertTrue(ordinary.contains("boundedShellMutex.withLock"))
        assertTrue(ordinary.contains("getOrCreateBoundedShell()"))
        assertTrue(dedicated.contains("boundedShellMutex.withLock"))
        assertTrue(dedicated.contains("getOrCreateBoundedShell()"))
        assertTrue(dedicated.contains("discardBoundedShell(shell)"))
        assertTrue(installer.contains(".setFlags(Shell.FLAG_MOUNT_MASTER)"))
        assertTrue(installer.contains("if (boundedShell === shell) boundedShell = null"))
        assertTrue(rootCheck.contains("getOrCreateBoundedShell()"))
        assertFalse(rootCheck.contains("Shell.Builder.create().build().use"))
        assertFalse(dedicated.contains("finally"))
    }

    @Test
    fun `dead shared root shell is rebuilt and failed jobs are retried once`() {
        val source = source("RootInstaller.kt").readText()
        val shared = source.substringAfter("suspend fun executeSharedBounded(")
            .substringBefore("suspend fun executeBounded(")

        assertTrue(source.contains("Shell.getCachedShell()?.takeIf(Shell::isAlive)"))
        assertTrue(shared.contains("first.code != SHELL_JOB_NOT_EXECUTED"))
        assertTrue(shared.contains("runCatching { shell.close() }"))
        assertTrue(shared.contains("executeWithTimeout(getShell(), command"))
        assertTrue(source.contains("private fun isolateShellJob(command: String)"))
        assertTrue(source.contains("current_mount_ns=\"\${'$'}(readlink /proc/self/ns/mnt)\""))
        assertTrue(source.contains("[ \"\${'$'}current_mount_ns\" = \"\${'$'}init_mount_ns\" ]"))
        assertTrue(source.contains("nsenter --mount=/proc/1/ns/mnt -- /system/bin/sh -c"))
        assertFalse(source.contains("nsenter -m/proc/1/ns/mnt"))
        assertFalse(source.contains("nsenter -t 1 -m"))
        assertEquals(2, Regex("add\\(isolateShellJob\\(command\\)\\)").findAll(source).count())
        assertFalse(source.contains("newJob().add(command)"))
    }

    @Test
    fun `root package manager session commands are bounded`() {
        val source = source("RootInstaller.kt").readText()
        val session = source.substringAfter("suspend fun installPackageFiles(")
            .substringBefore("private fun parseSessionId")

        assertFalse(session.contains("execute(installShell"))
        assertTrue(session.contains("\"root shell probe\""))
        assertTrue(session.contains("\"root install session creation\""))
        assertTrue(session.contains("\"root install session write\""))
        assertTrue(session.contains("\"root install session commit\""))
    }

    @Test
    fun `root stock install skips dexopt before compatibility fallbacks`() {
        val source = source("RootInstaller.kt").readText()
        val install = source.substringAfter("suspend fun installSinglePackageFile(")
            .substringBefore("suspend fun installPackageFiles(")

        assertTrue(install.contains("\"${'$'}it --dexopt-compiler-filter skip ${'$'}apkPath\""))
        assertTrue(install.indexOf("--dexopt-compiler-filter skip") < install.indexOf("+ commandPrefixes.map"))
    }

    private fun shellFunction(script: String, name: String): String {
        val normalized = script.replace("\r\n", "\n")
        val marker = "$name() {\n"
        val start = normalized.indexOf(marker)
        require(start >= 0) { "Missing shell function: $name" }
        val end = normalized.indexOf("\n}", start + marker.length)
        require(end >= 0) { "Missing shell function end: $name" }
        return normalized.substring(start + marker.length, end)
    }

    private fun asset(name: String): File = sequenceOf(
        File("app/src/main/assets/root/$name"),
        File("src/main/assets/root/$name")
    ).first { it.isFile }

    private fun source(name: String): File = sequenceOf(
        File("app/src/main/java/app/urv/manager/domain/installer/$name"),
        File("src/main/java/app/urv/manager/domain/installer/$name")
    ).first { it.isFile }

    private fun rootSource(name: String): File = sequenceOf(
        File("app/src/main/java/app/urv/manager/domain/installer/root/$name"),
        File("src/main/java/app/urv/manager/domain/installer/root/$name")
    ).first { it.isFile }

    private class LockShell : RootShellGateway {
        val commands = mutableListOf<String>()
        private var held = false

        override suspend fun run(command: String): RootCommandResult =
            error("RootPackageLock must use bounded commands")

        override suspend fun runBounded(
            command: String,
            timeoutSeconds: Long,
            operation: String
        ): RootCommandResult {
            commands += command
            return when {
                operation == "root package lock release" -> {
                    held = false
                    RootCommandResult(0, emptyList(), emptyList())
                }
                held -> RootCommandResult(
                    75,
                    emptyList(),
                    listOf("Root package lock is held by pid 9001, uid 0, transaction legacy-boot")
                )
                else -> {
                    held = true
                    RootCommandResult(0, listOf(OWNER_START), emptyList())
                }
            }
        }
    }

    private class RetryingLockShell : RootShellGateway {
        var acquireCalls = 0
        var released = false

        override suspend fun run(command: String): RootCommandResult =
            error("RootPackageLock must use bounded commands")

        override suspend fun runBounded(
            command: String,
            timeoutSeconds: Long,
            operation: String
        ): RootCommandResult = when {
            operation == "root package lock release" -> {
                released = true
                RootCommandResult(0, emptyList(), emptyList())
            }
            ++acquireCalls == 1 -> throw IllegalStateException("transient shell failure")
            else -> RootCommandResult(0, listOf(OWNER_START), emptyList())
        }
    }
    private companion object {
        const val PACKAGE = "com.example.app"
        const val OWNER_PID = 4242
        const val OWNER_UID = 10234
        const val OWNER_START = "123456"
    }
}
