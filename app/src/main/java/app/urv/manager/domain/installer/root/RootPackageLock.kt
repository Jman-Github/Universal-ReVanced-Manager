package app.urv.manager.domain.installer.root

import kotlinx.coroutines.CancellationException

class RootPackageLock(
    private val shell: RootShellGateway,
    private val ownerPid: () -> Int = android.os.Process::myPid,
    private val ownerUid: () -> Int = android.os.Process::myUid
) : RootPackageLocking {
    override suspend fun acquire(packageName: String, transactionId: String): RootLockHandle? {
        val lock = RootPaths.lock(packageName)
        val owner = RootPaths.lockOwner(packageName)
        val processId = ownerPid().also { require(it > 0) { "Invalid lock owner process" } }
        val processUid = ownerUid().also { require(it >= 0) { "Invalid lock owner UID" } }
        val command = acquireCommand(
            lock,
            owner,
            RootPaths.active(packageName),
            processId,
            processUid,
            transactionId
        )
        val result = try {
            runLockCommand(command, "root package lock acquire")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (firstFailure: Throwable) {
            try {
                runLockCommand(command, "root package lock acquire retry")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (retryFailure: Throwable) {
                retryFailure.addSuppressed(firstFailure)
                throw retryFailure
            }
        }
        return when (result.status) {
            0 -> {
                val ownerStart = result.stdout.firstOrNull()?.trim().orEmpty()
                check(ownerStart.isNotEmpty()) { "Root package lock owner identity was not returned" }
                RootLockHandle(
                    packageName = packageName,
                    lockPath = lock,
                    ownerPath = owner,
                    ownerPid = processId,
                    ownerStart = ownerStart,
                    transactionId = transactionId
                )
            }
            BUSY_EXIT_CODE -> throw IllegalStateException(
                result.output.ifBlank { "Root package lock is held by another live operation" }
            )
            else -> result.requireSuccess("Acquire root mount package lock").let { null }
        }
    }

    override suspend fun release(handle: RootLockHandle) {
        runLockCommand(
            releaseCommand(handle),
            "root package lock release"
        ).requireSuccess("Release root mount package lock")
    }

    private suspend fun runLockCommand(command: String, operation: String): RootCommandResult =
        shell.runBounded(command, LOCK_COMMAND_TIMEOUT_SECONDS, operation)

    private fun acquireCommand(
        lock: String,
        owner: String,
        activePath: String,
        processId: Int,
        processUid: Int,
        transactionId: String
    ): String = """
        set -eu
        lock_path=${shellQuote(lock)}
        owner_path=${shellQuote(owner)}
        active_path=${shellQuote(activePath)}
        owner_pid=$processId
        app_uid=$processUid
        transaction_id=${shellQuote(transactionId)}
        owner_start="${'$'}(awk '{print ${'$'}22}' /proc/${'$'}owner_pid/stat 2>/dev/null)"
        [ -n "${'$'}owner_start" ] || {
          echo "Unable to read root package lock owner identity" >&2
          exit 1
        }
        mkdir -p ${shellQuote(RootPaths.ROOT)}/locks
        chmod 700 ${shellQuote(RootPaths.ROOT)} ${shellQuote(RootPaths.ROOT)}/locks

        lock_is_old() {
          lock_mtime="${'$'}(stat -c %Y "${'$'}lock_path" 2>/dev/null || echo 0)"
          now="${'$'}(date +%s 2>/dev/null || echo 0)"
          [ "${'$'}lock_mtime" -gt 0 ] 2>/dev/null || return 1
          [ "${'$'}now" -ge "${'$'}lock_mtime" ] 2>/dev/null || return 1
          [ "${'$'}((now - lock_mtime))" -ge $STALE_OWNER_GRACE_SECONDS ]
        }

        write_owner() {
          printf '%s\n%s\n%s\n' "${'$'}owner_pid" "${'$'}owner_start" "${'$'}transaction_id" > "${'$'}owner_path" || return 1
          chmod 600 "${'$'}owner_path"
        }

        try_acquire() {
          mkdir "${'$'}lock_path" 2>/dev/null || return 1
          chmod 700 "${'$'}lock_path" || {
            rmdir "${'$'}lock_path" 2>/dev/null || true
            return 1
          }
          write_owner || {
            rm -f "${'$'}owner_path"
            rmdir "${'$'}lock_path" 2>/dev/null || true
            return 1
          }
          echo "${'$'}owner_start"
          return 0
        }

        try_acquire && exit 0
        [ -d "${'$'}lock_path" ] || {
          echo "Root package lock path is not a directory" >&2
          exit 1
        }
        saved_pid=''
        saved_start=''
        saved_transaction=''
        if [ -f "${'$'}owner_path" ]; then
          saved_pid="${'$'}(sed -n '1p' "${'$'}owner_path" 2>/dev/null)"
          saved_start="${'$'}(sed -n '2p' "${'$'}owner_path" 2>/dev/null)"
          saved_transaction="${'$'}(sed -n '3p' "${'$'}owner_path" 2>/dev/null)"
          if [ "${'$'}saved_pid" = "${'$'}owner_pid" ] &&
             [ "${'$'}saved_start" = "${'$'}owner_start" ]; then
            if [ "${'$'}saved_transaction" = "${'$'}transaction_id" ]; then
              echo "${'$'}owner_start"
              exit 0
            fi
            # RootMountTransactionCoordinator serializes each package in-process, so a
            # different transaction owned by this same process is an orphaned lock.
            rm -rf "${'$'}lock_path"
            try_acquire && exit 0
          fi
          case "${'$'}saved_pid" in
            ''|*[!0-9]*) saved_pid='' ;;
          esac
          current_start=''
          current_uid=''
          current_cmdline=''
          boot_service_owner=0
          if [ -n "${'$'}saved_pid" ]; then
            current_start="${'$'}(awk '{print ${'$'}22}' /proc/${'$'}saved_pid/stat 2>/dev/null || true)"
            current_uid="${'$'}(awk '/^Uid:/{print ${'$'}2; exit}' /proc/${'$'}saved_pid/status 2>/dev/null || true)"
            current_cmdline="${'$'}(tr '\000' ' ' < /proc/${'$'}saved_pid/cmdline 2>/dev/null || true)"
          fi
          if [ "${'$'}current_uid" = 0 ]; then
            case "${'$'}saved_transaction:${'$'}current_cmdline" in
              boot:*|*/data/adb/modules/*/service.sh*|*/data/adb/modules_update/*/service.sh*)
                boot_service_owner=1
                ;;
            esac
          fi
          if [ -f "${'$'}active_path" ] &&
             [ -n "${'$'}saved_pid" ] && [ -n "${'$'}saved_start" ] &&
             [ "${'$'}current_start" = "${'$'}saved_start" ] &&
             [ "${'$'}boot_service_owner" = 1 ]; then
            # Root module service scripts must always defer to Manager recovery when
            # an incomplete transaction journal exists.
            kill -TERM "${'$'}saved_pid" 2>/dev/null || true
            stop_wait=0
            while [ "${'$'}stop_wait" -lt 20 ]; do
              current_start="${'$'}(awk '{print ${'$'}22}' /proc/${'$'}saved_pid/stat 2>/dev/null || true)"
              [ "${'$'}current_start" = "${'$'}saved_start" ] || break
              sleep 0.1
              stop_wait=${'$'}((stop_wait + 1))
            done
            if [ "${'$'}current_start" = "${'$'}saved_start" ]; then
              kill -KILL "${'$'}saved_pid" 2>/dev/null || true
              sleep 0.1
              current_start="${'$'}(awk '{print ${'$'}22}' /proc/${'$'}saved_pid/stat 2>/dev/null || true)"
            fi
            if [ "${'$'}current_start" != "${'$'}saved_start" ]; then
              rm -rf "${'$'}lock_path"
              try_acquire && exit 0
            fi
          fi
          if [ -n "${'$'}saved_pid" ] && [ -n "${'$'}saved_start" ] &&
             { [ -z "${'$'}current_start" ] || [ "${'$'}current_start" != "${'$'}saved_start" ]; }; then
            rm -rf "${'$'}lock_path"
            try_acquire && exit 0
          fi
        fi
        if lock_is_old && { [ ! -f "${'$'}owner_path" ] || [ -z "${'$'}saved_pid" ] || [ -z "${'$'}saved_start" ]; }; then
          rm -rf "${'$'}lock_path"
          try_acquire && exit 0
        fi
        echo "Root package lock is held by pid ${'$'}{saved_pid:-unknown}, uid ${'$'}{current_uid:-unknown}, transaction ${'$'}{saved_transaction:-unknown}; Manager uid is ${'$'}app_uid" >&2
        exit $BUSY_EXIT_CODE
    """.trimIndent()

    private fun releaseCommand(handle: RootLockHandle): String = """
        set -eu
        lock_path=${shellQuote(handle.lockPath)}
        owner_path=${shellQuote(handle.ownerPath)}
        [ -d "${'$'}lock_path" ] || exit 0
        [ -f "${'$'}owner_path" ] || {
          echo "Root package lock owner record is missing" >&2
          exit 1
        }
        saved_pid="${'$'}(sed -n '1p' "${'$'}owner_path")"
        saved_start="${'$'}(sed -n '2p' "${'$'}owner_path")"
        saved_transaction="${'$'}(sed -n '3p' "${'$'}owner_path")"
        [ "${'$'}saved_pid" = ${shellQuote(handle.ownerPid.toString())} ] &&
        [ "${'$'}saved_start" = ${shellQuote(handle.ownerStart)} ] &&
        [ "${'$'}saved_transaction" = ${shellQuote(handle.transactionId)} ] || {
          echo "Root package lock ownership changed before release" >&2
          exit 1
        }
        rm -f "${'$'}owner_path"
        rmdir "${'$'}lock_path"
    """.trimIndent()

    private companion object {
        const val BUSY_EXIT_CODE = 75
        const val LOCK_COMMAND_TIMEOUT_SECONDS = 10L
        const val STALE_OWNER_GRACE_SECONDS = 5
    }
}
