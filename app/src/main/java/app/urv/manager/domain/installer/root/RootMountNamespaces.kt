package app.urv.manager.domain.installer.root

/** Mirrors and verifies URV-owned binds, and removes them from Zygote and Manager namespaces. */
class RootMountNamespaces(
    private val shell: RootShellGateway,
    private val managerPid: Int? = null
) {
    init {
        require(managerPid == null || managerPid > 0) { "Invalid Manager process ID" }
    }

    suspend fun mount(expected: RootCommittedState) {
        shell.runIsolatedBounded(
            namespaceHelpers(expected) + """
                set -eu
                preflight_stable=0
                preflight_attempt=0
                while [ "${'$'}preflight_attempt" -lt $ZYGOTE_STABILITY_ATTEMPTS ]; do
                  before="${'$'}(live_zygote_pids)"
                  if [ -z "${'$'}before" ]; then
                    preflight_attempt=${'$'}((preflight_attempt + 1))
                    sleep 0.1
                    continue
                  fi
                  zygote_changed=0
                  for pid in ${'$'}before; do
                    validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                    if ! namespace_matches "${'$'}pid"; then
                      if [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 1 ] &&
                          namespace_matches_shadow "${'$'}pid"; then
                        :
                      elif ! namespace_target_clear "${'$'}pid"; then
                        validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                        echo "Foreign mount conflict in Zygote namespace ${'$'}pid" >&2
                        exit 1
                      fi
                    fi
                  done
                  after="${'$'}(live_zygote_pids)"
                  if [ "${'$'}zygote_changed" = 0 ] && [ "${'$'}before" = "${'$'}after" ]; then
                    preflight_stable=1
                    break
                  fi
                  preflight_attempt=${'$'}((preflight_attempt + 1))
                  sleep 0.1
                done
                [ "${'$'}preflight_stable" = 1 ] || {
                  echo "Zygote namespaces did not stabilize during mount preflight" >&2
                  exit 1
                }
                if [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 1 ]; then
                  chcon u:object_r:apk_data_file:s0 ${shellQuote(expected.patchedPath)} ${shellQuote(expected.stockShadowPath.orEmpty())}
                else
                  chcon u:object_r:apk_data_file:s0 ${shellQuote(expected.patchedPath)}
                fi
                validate_shadow_hash
                self_pid="${'$'}${'$'}"
                if ! namespace_matches "${'$'}self_pid"; then
                  if ! namespace_target_clear "${'$'}self_pid"; then
                    echo "Root APK target is occupied by a non-matching mount" >&2
                    exit 1
                  fi
                  if [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 1 ]; then
                    mount -o bind ${shellQuote(expected.stockShadowPath.orEmpty())} ${shellQuote(expected.stockPath)}
                    mount -o private none ${shellQuote(expected.stockPath)} || {
                      echo "Failed to isolate the stock shadow bind mount" >&2
                      exit 1
                    }
                  fi
                  mount -o bind ${shellQuote(expected.patchedPath)} ${shellQuote(expected.stockPath)}
                fi
                mount_stable=0
                mount_attempt=0
                while [ "${'$'}mount_attempt" -lt $ZYGOTE_STABILITY_ATTEMPTS ]; do
                  pids="${'$'}(live_zygote_pids)"
                  if [ -z "${'$'}pids" ]; then
                    mount_attempt=${'$'}((mount_attempt + 1))
                    sleep 0.1
                    continue
                  fi
                  zygote_changed=0
                  for pid in ${'$'}pids; do
                    validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                    if ! namespace_matches "${'$'}pid"; then
                      shadow_ready=0
                      if [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 1 ] && namespace_matches_shadow "${'$'}pid"; then
                        shadow_ready=1
                      elif namespace_target_clear "${'$'}pid"; then
                        if [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 1 ]; then
                          if ! nsenter -t "${'$'}pid" -m -- mount -o bind ${shellQuote(expected.stockShadowPath.orEmpty())} ${shellQuote(expected.stockPath)}; then
                            validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                            echo "Failed to mount the stock shadow in Zygote namespace ${'$'}pid" >&2
                            exit 1
                          fi
                          shadow_ready=1
                        fi
                      else
                        validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                        echo "Foreign mount conflict in Zygote namespace ${'$'}pid" >&2
                        exit 1
                      fi
                      if [ "${'$'}shadow_ready" = 1 ] &&
                          ! nsenter -t "${'$'}pid" -m -- mount -o private none ${shellQuote(expected.stockPath)}; then
                        validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                        echo "Failed to isolate the stock shadow in Zygote namespace ${'$'}pid" >&2
                        exit 1
                      fi
                      if ! nsenter -t "${'$'}pid" -m -- mount -o bind ${shellQuote(expected.patchedPath)} ${shellQuote(expected.stockPath)}; then
                        validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                        echo "Failed to mount the patched APK in Zygote namespace ${'$'}pid" >&2
                        exit 1
                      fi
                    fi
                    if ! namespace_matches "${'$'}pid"; then
                      validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                      echo "Failed to verify the patched APK in Zygote namespace ${'$'}pid" >&2
                      exit 1
                    fi
                  done
                  after="${'$'}(live_zygote_pids)"
                  if [ "${'$'}zygote_changed" = 0 ] && [ "${'$'}pids" = "${'$'}after" ]; then
                    mount_stable=1
                    break
                  fi
                  mount_attempt=${'$'}((mount_attempt + 1))
                  sleep 0.1
                done
                [ "${'$'}mount_stable" = 1 ] || {
                  echo "Zygote namespaces did not stabilize while applying mounts" >&2
                  exit 1
                }
            """.trimIndent(),
            MOUNT_TIMEOUT_SECONDS,
            "root and Zygote namespace mount"
        ).requireSuccess("Mount patched APK in root and Zygote namespaces")
    }

    suspend fun verify(expected: RootCommittedState) {
        shell.runIsolatedBounded(
            namespaceHelpers(expected) + """
                set -eu
                # RootMountVerifier validates the stock-shadow hash after this namespace check,
                # so only verify that the source file still exists while inspecting namespaces.
                validate_shadow_file
                verify_stable=0
                verify_attempt=0
                while [ "${'$'}verify_attempt" -lt $ZYGOTE_STABILITY_ATTEMPTS ]; do
                  pids="${'$'}(live_zygote_pids)"
                  if [ -z "${'$'}pids" ]; then
                    verify_attempt=${'$'}((verify_attempt + 1))
                    sleep 0.1
                    continue
                  fi
                  zygote_changed=0
                  for pid in ${'$'}pids; do
                    validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                    if ! namespace_matches "${'$'}pid"; then
                      validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                      echo "Patched APK is not mounted in Zygote namespace ${'$'}pid" >&2
                      exit 1
                    fi
                  done
                  after="${'$'}(live_zygote_pids)"
                  if [ "${'$'}zygote_changed" = 0 ] && [ "${'$'}pids" = "${'$'}after" ]; then
                    verify_stable=1
                    break
                  fi
                  verify_attempt=${'$'}((verify_attempt + 1))
                  sleep 0.1
                done
                [ "${'$'}verify_stable" = 1 ] || {
                  echo "Zygote namespaces did not stabilize during verification" >&2
                  exit 1
                }
            """.trimIndent(),
            VERIFY_TIMEOUT_SECONDS,
            "Zygote namespace verification"
        ).requireSuccess("Verify patched APK in every Zygote namespace")
    }

    suspend fun removeOwned(
        packageName: String,
        targets: Set<String>,
        allowLazyRecovery: Boolean = false
    ): List<String> {
        if (targets.isEmpty()) return emptyList()
        val allowedPaths = mountInfoAliases(
            listOf(
                RootPaths.moduleApk(packageName),
                RootPaths.moduleStockApk(packageName),
                "${RootPaths.rollbackModule(packageName)}/$packageName.apk",
                "${RootPaths.rollbackModule(packageName)}/$packageName-stock.apk",
                "${RootPaths.backup(packageName)}/module/$packageName.apk",
                "${RootPaths.backup(packageName)}/module/$packageName-stock.apk",
                "${RootPaths.legacyPackage(packageName)}/$packageName.apk"
            )
        )
        val allowed = allowedPaths.joinToString(" ") { shellQuote(it) }
        val allowedSourceList = shellQuote(allowedPaths.joinToString("\n"))
        val targetWords = targets.joinToString(" ") { shellQuote(it) }
        val cleanup = shell.runIsolatedBounded(
            discoveryHelpers() + """
                set -eu
                allowed_sources=$allowedSourceList
                namespace_has_owned_layer() {
                  pid="${'$'}1"
                  target="${'$'}2"
                  nsenter -t "${'$'}pid" -m -- awk -v target="${'$'}target" -v allowed="${'$'}allowed_sources" '
                    BEGIN { count=split(allowed, candidates, "\n") }
                    ${'$'}5 == target {
                      separator=0
                      for (i=1; i<=NF; i++) if (${'$'}i == "-") { separator=i; break }
                      root=${'$'}4
                      source=(separator > 0 ? ${'$'}(separator+2) : "")
                      sub(/\\040\(deleted\)${'$'}/, "", root)
                      sub(/\\040\(deleted\)${'$'}/, "", source)
                      sub(/ \(deleted\)${'$'}/, "", root)
                      sub(/ \(deleted\)${'$'}/, "", source)
                      for (candidate=1; candidate<=count; candidate++) {
                        if (root == candidates[candidate] || source == candidates[candidate]) owned=1
                      }
                    }
                    END { exit !owned }
                  ' /proc/self/mountinfo
                }
                cleanup_namespace() {
                  pid="${'$'}1"
                  namespace_label="${'$'}2"
                  self_namespace="${'$'}(readlink /proc/self/ns/mnt)" || {
                    echo "Failed to inspect the root mount namespace" >&2
                    exit 1
                  }
                  target_namespace="${'$'}(readlink /proc/${'$'}pid/ns/mnt)" || return 0
                  [ "${'$'}self_namespace" != "${'$'}target_namespace" ] || return 0
                  for target in $targetWords; do
                    attempts=0
                    max_attempts=${if (allowLazyRecovery) "16" else "8"}
                    while nsenter -t "${'$'}pid" -m -- awk -v target="${'$'}target" '${'$'}5 == target { found=1 } END { exit !found }' /proc/self/mountinfo; do
                      target_inode="${'$'}(nsenter -t "${'$'}pid" -m -- stat -c '%d:%i' "${'$'}target" 2>/dev/null)" || {
                        echo "Failed to inspect ${'$'}target in ${'$'}namespace_label namespace ${'$'}pid" >&2
                        exit 1
                      }
                      owned=0
                      for candidate in $allowed; do
                        [ -f "${'$'}candidate" ] || continue
                        [ "${'$'}(stat -c '%d:%i' "${'$'}candidate" 2>/dev/null)" = "${'$'}target_inode" ] && owned=1 && break
                      done
                      if [ "${'$'}owned" != 1 ]; then
                        if namespace_has_owned_layer "${'$'}pid" "${'$'}target"; then
                          echo "Foreign ${'$'}namespace_label mount covers a URV layer at ${'$'}target" >&2
                          exit 1
                        fi
                        break
                      fi
                      [ "${'$'}attempts" -lt "${'$'}max_attempts" ] || {
                        echo "Too many URV mount layers remained at ${'$'}target in ${'$'}namespace_label namespace ${'$'}pid" >&2
                        exit 1
                      }
                      if ! nsenter -t "${'$'}pid" -m -- umount "${'$'}target"; then
                        if [ ${if (allowLazyRecovery) "1" else "0"} = 1 ]; then
                          if nsenter -t "${'$'}pid" -m -- awk -v target="${'$'}target" '${'$'}5 == target { found=1 } END { exit !found }' /proc/self/mountinfo; then
                            # Re-prove visible ownership after the failed normal unmount. A
                            # foreign layer can appear between the first check and lazy detach.
                            target_inode="${'$'}(nsenter -t "${'$'}pid" -m -- stat -c '%d:%i' "${'$'}target" 2>/dev/null)" || {
                              echo "Failed to re-inspect ${'$'}target in ${'$'}namespace_label namespace ${'$'}pid" >&2
                              exit 1
                            }
                            owned=0
                            for candidate in $allowed; do
                              [ -f "${'$'}candidate" ] || continue
                              [ "${'$'}(stat -c '%d:%i' "${'$'}candidate" 2>/dev/null)" = "${'$'}target_inode" ] && owned=1 && break
                            done
                            [ "${'$'}owned" = 1 ] || {
                              echo "${'$'}namespace_label mount ownership changed before lazy unmount at ${'$'}target" >&2
                              exit 1
                            }
                            nsenter -t "${'$'}pid" -m -- umount -l "${'$'}target" || {
                              echo "Failed to lazily unmount ${'$'}target in ${'$'}namespace_label namespace ${'$'}pid" >&2
                              exit 1
                            }
                            printf 'URV_LAZY_UNMOUNT:%s\n' "${'$'}target"
                          fi
                        else
                          echo "Failed to unmount ${'$'}target in ${'$'}namespace_label namespace ${'$'}pid" >&2
                          exit 1
                        fi
                      fi
                      attempts=${'$'}((attempts + 1))
                    done
                  done
                }
                ${managerPid?.let { pid ->
                    "if [ -r /proc/$pid/ns/mnt ]; then cleanup_namespace $pid Manager; fi"
                }.orEmpty()}
                for pid in ${'$'}(zygote_pids); do
                  validate_zygote "${'$'}pid" || continue
                  cleanup_namespace "${'$'}pid" Zygote
                done
            """.trimIndent(),
            REMOVE_TIMEOUT_SECONDS,
            "Zygote namespace cleanup"
        ).requireSuccess("Remove URV mounts from Zygote namespaces")
        return cleanup.stdout.mapNotNull { line ->
            line.substringAfter(LAZY_UNMOUNT_MARKER, "")
                .takeIf(String::isNotEmpty)
        }.distinct()
    }

    private fun namespaceHelpers(expected: RootCommittedState): String {
        val patchedRoot = mountInfoRootAlias(expected.patchedPath)
        val shadowRoot = mountInfoRootAlias(expected.stockShadowPath.orEmpty())
        return discoveryHelpers() + """
            validate_shadow_file() {
              [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 0 ] && return 0
              [ -f ${shellQuote(expected.stockShadowPath.orEmpty())} ]
            }
            validate_shadow_hash() {
              validate_shadow_file || return 1
              [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 0 ] && return 0
              shadow_hash="${'$'}(sha256sum ${shellQuote(expected.stockShadowPath.orEmpty())} 2>/dev/null | awk '{print ${'$'}1}')"
              [ "${'$'}shadow_hash" = ${shellQuote(expected.stockShadowSha256.orEmpty())} ]
            }
            namespace_target_clear() {
              pid="${'$'}1"
              ! nsenter -t "${'$'}pid" -m -- awk -v target=${shellQuote(expected.stockPath)} \
                '${'$'}5 == target { found=1 } END { exit !found }' /proc/self/mountinfo
            }
            namespace_matches_shadow() {
              [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 1 ] || return 1
              pid="${'$'}1"
              ownership="${'$'}(nsenter -t "${'$'}pid" -m -- awk \
                -v target=${shellQuote(expected.stockPath)} \
                -v shadow=${shellQuote(expected.stockShadowPath.orEmpty())} \
                -v shadow_root=${shellQuote(shadowRoot)} '
                  ${'$'}5 == target {
                    total++
                    separator=0
                    for (i=1; i<=NF; i++) if (${'$'}i == "-") { separator=i; break }
                    root=${'$'}4
                    source=(separator > 0 ? ${'$'}(separator+2) : "")
                    if (root == shadow || root == shadow_root || source == shadow || source == shadow_root) owned++
                  }
                  END { print total+0 ":" owned+0 }
                ' /proc/self/mountinfo)" || return 1
              [ "${'$'}ownership" = "1:1" ] || return 1
              source_inode="${'$'}(stat -c '%d:%i' ${shellQuote(expected.stockShadowPath.orEmpty())} 2>/dev/null)" || return 1
              target_inode="${'$'}(nsenter -t "${'$'}pid" -m -- stat -c '%d:%i' ${shellQuote(expected.stockPath)} 2>/dev/null)" || return 1
              [ "${'$'}source_inode" = "${'$'}target_inode" ]
            }
            namespace_matches() {
              pid="${'$'}1"
              validate_shadow_file || return 1
              ownership="${'$'}(nsenter -t "${'$'}pid" -m -- awk \
                -v target=${shellQuote(expected.stockPath)} \
                -v patched=${shellQuote(expected.patchedPath)} \
                -v patched_root=${shellQuote(patchedRoot)} \
                -v shadow=${shellQuote(expected.stockShadowPath.orEmpty())} \
                -v shadow_root=${shellQuote(shadowRoot)} '
                  ${'$'}5 == target {
                    total++
                    separator=0
                    for (i=1; i<=NF; i++) if (${'$'}i == "-") { separator=i; break }
                    root=${'$'}4
                    source=(separator > 0 ? ${'$'}(separator+2) : "")
                    if (root == patched || root == patched_root || source == patched || source == patched_root ||
                        (shadow != "" && (root == shadow || root == shadow_root ||
                            source == shadow || source == shadow_root))) owned++
                  }
                  END { print total+0 ":" owned+0 }
                ' /proc/self/mountinfo)" || return 1
              [ "${'$'}ownership" = "${if (expected.preserveStockAcrossBoot) "2:2" else "1:1"}" ] || return 1
              source_inode="${'$'}(stat -c '%d:%i' ${shellQuote(expected.patchedPath)} 2>/dev/null)" || return 1
              target_inode="${'$'}(nsenter -t "${'$'}pid" -m -- stat -c '%d:%i' ${shellQuote(expected.stockPath)} 2>/dev/null)" || return 1
              [ "${'$'}source_inode" = "${'$'}target_inode" ]
            }
        """.trimIndent() + "\n"
    }

    private fun discoveryHelpers(): String = """
        zygote_pids() {
          { pidof zygote64 2>/dev/null || true; pidof zygote 2>/dev/null || true; } |
            tr ' ' '\n' | awk '/^[0-9]+${'$'}/' | sort -n -u | paste -sd ' ' -
        }
        validate_zygote() {
          pid="${'$'}1"
          cmdline="${'$'}(cat "/proc/${'$'}pid/cmdline" 2>/dev/null)" || return 1
          case "${'$'}cmdline" in
            zygote|zygote64|zygote\ *|zygote64\ *|*--zygote*|*--nice-name=zygote*) return 0 ;;
          esac
          return 1
        }
        live_zygote_pids() {
          for pid in ${'$'}(zygote_pids); do
            validate_zygote "${'$'}pid" && printf '%s ' "${'$'}pid"
          done | sed 's/[[:space:]]*${'$'}//'
        }
    """.trimIndent() + "\n"

    private fun mountInfoAliases(paths: List<String>): List<String> =
        paths.flatMap { path -> listOf(path, mountInfoRootAlias(path)) }.distinct()

    private fun mountInfoRootAlias(path: String): String =
        if (path.startsWith("/data/")) path.removePrefix("/data") else path

    private companion object {
        const val LAZY_UNMOUNT_MARKER = "URV_LAZY_UNMOUNT:"
        const val ZYGOTE_STABILITY_ATTEMPTS = 20
        const val MOUNT_TIMEOUT_SECONDS = 60L
        const val VERIFY_TIMEOUT_SECONDS = 60L
        const val REMOVE_TIMEOUT_SECONDS = 60L
    }
}
