#!/system/bin/sh
# Late verification and fail-safe reconciliation for a committed URV mount.
MODDIR=${0%/*}
state_file="$MODDIR/state.env"
log="$MODDIR/log.txt"
exec >>"$log" 2>&1

log_status() {
  echo "$(date +%s 2>/dev/null || echo 0) [service] $*"
}

disable_module() {
  : >"$MODDIR/disable" || return 1
  sync
}

mountinfo_root_alias() {
  case "$1" in
    /data/*) printf '%s\n' "${1#/data}" ;;
    *) printf '%s\n' "$1" ;;
  esac
}

known_mount_sources() {
  rollback_path="${MODDIR%/*}/.$URV_PACKAGE-revanced.urv-rollback/$URV_PACKAGE.apk"
  backup_path="/data/adb/urv/transactions/$URV_PACKAGE/backup/module/$URV_PACKAGE.apk"
  rollback_shadow_path="${MODDIR%/*}/.$URV_PACKAGE-revanced.urv-rollback/$URV_PACKAGE-stock.apk"
  backup_shadow_path="/data/adb/urv/transactions/$URV_PACKAGE/backup/module/$URV_PACKAGE-stock.apk"
  legacy_path="/data/adb/revanced/$URV_PACKAGE/$URV_PACKAGE.apk"
  for source in "$URV_PATCHED_PATH" "$URV_STOCK_SHADOW_PATH" "$rollback_path" "$backup_path" \
      "$rollback_shadow_path" "$backup_shadow_path" "$legacy_path"; do
    [ -n "$source" ] || continue
    printf '%s\n' "$source"
    root_alias="$(mountinfo_root_alias "$source")" || return 1
    [ "$root_alias" = "$source" ] || printf '%s\n' "$root_alias"
  done
}

installed_user_ids() {
  users="$(pm list users 2>/dev/null |
    sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p')" || return 1
  [ -n "$users" ] || return 1
  for user in $users; do
    packages="$(pm list packages --user "$user" "$URV_PACKAGE" 2>/dev/null)" || return 1
    if printf '%s\n' "$packages" | grep -Fx "package:$URV_PACKAGE" >/dev/null; then
      echo "$user"
    fi
  done
}

target_is_mounted() {
  awk -v target="$URV_STOCK_PATH" '$5 == target { found=1 } END { exit !found }' /proc/self/mountinfo
}

target_mount_counts() {
  allowed_sources="$(known_mount_sources)" || return 1
  awk -v target="$URV_STOCK_PATH" -v allowed="$allowed_sources" '
    BEGIN { count=split(allowed, candidates, "\n") }
    $5 == target {
      total++
      separator=0
      for (i=6; i<=NF; i++) if ($i == "-") { separator=i; break }
      root=$4
      source=(separator ? $(separator+2) : "")
      sub(/\\040\(deleted\)$/, "", root)
      sub(/\\040\(deleted\)$/, "", source)
      sub(/ \(deleted\)$/, "", root)
      sub(/ \(deleted\)$/, "", source)
      for (candidate=1; candidate<=count; candidate++) {
        if (root == candidates[candidate] || source == candidates[candidate]) owned=1
      }
      if (owned) urv++
      owned=0
    }
    END { print total+0 ":" urv+0 }
  ' /proc/self/mountinfo
}

target_matches_urv_inode() {
  target_inode="$(stat -c '%d:%i' "$URV_STOCK_PATH" 2>/dev/null)"
  [ -n "$target_inode" ] || return 1
  rollback_path="${MODDIR%/*}/.$URV_PACKAGE-revanced.urv-rollback/$URV_PACKAGE.apk"
  backup_path="/data/adb/urv/transactions/$URV_PACKAGE/backup/module/$URV_PACKAGE.apk"
  rollback_shadow_path="${MODDIR%/*}/.$URV_PACKAGE-revanced.urv-rollback/$URV_PACKAGE-stock.apk"
  backup_shadow_path="/data/adb/urv/transactions/$URV_PACKAGE/backup/module/$URV_PACKAGE-stock.apk"
  legacy_path="/data/adb/revanced/$URV_PACKAGE/$URV_PACKAGE.apk"
  for source in "$URV_PATCHED_PATH" "$URV_STOCK_SHADOW_PATH" "$rollback_path" "$backup_path" \
      "$rollback_shadow_path" "$backup_shadow_path" "$legacy_path"; do
    [ -f "$source" ] || continue
    [ "$(stat -c '%d:%i' "$source" 2>/dev/null)" = "$target_inode" ] && return 0
  done
  return 1
}

target_matches_patched_inode() {
  patched_inode="$(stat -c '%d:%i' "$URV_PATCHED_PATH" 2>/dev/null)" || return 1
  target_inode="$(stat -c '%d:%i' "$URV_STOCK_PATH" 2>/dev/null)" || return 1
  [ -n "$patched_inode" ] && [ "$patched_inode" = "$target_inode" ]
}

target_payload_layer_counts() {
  patched_root="$(mountinfo_root_alias "$URV_PATCHED_PATH")" || return 1
  shadow_root="$(mountinfo_root_alias "$URV_STOCK_SHADOW_PATH")" || return 1
  awk -v target="$URV_STOCK_PATH" \
    -v patched="$URV_PATCHED_PATH" -v patched_root="$patched_root" \
    -v shadow="$URV_STOCK_SHADOW_PATH" -v shadow_root="$shadow_root" '
      $5 == target {
        separator=0
        for (i=6; i<=NF; i++) if ($i == "-") { separator=i; break }
        root=$4
        source=(separator ? $(separator+2) : "")
        sub(/\\040\(deleted\)$/, "", root)
        sub(/\\040\(deleted\)$/, "", source)
        sub(/ \(deleted\)$/, "", root)
        sub(/ \(deleted\)$/, "", source)
        if (root == patched || root == patched_root || source == patched || source == patched_root) patched_count++
        if (shadow != "" && (root == shadow || root == shadow_root || source == shadow || source == shadow_root)) shadow_count++
      }
      END { print patched_count+0 ":" shadow_count+0 }
    ' /proc/self/mountinfo
}

root_mount_layout_valid() {
  ownership="$(target_mount_counts)" || return 1
  total_mounts="${ownership%%:*}"
  urv_mounts="${ownership##*:}"
  required_mounts=1
  [ "$URV_PRESERVE_STOCK" = 1 ] && required_mounts=2
  [ "$total_mounts" -ge "$required_mounts" ] || return 1
  [ "$total_mounts" -le 8 ] || return 1
  [ "$total_mounts" = "$urv_mounts" ] || return 1
  payload_counts="$(target_payload_layer_counts)" || return 1
  patched_layers="${payload_counts%%:*}"
  shadow_layers="${payload_counts##*:}"
  [ "$patched_layers" -ge 1 ] || return 1
  if [ "$URV_PRESERVE_STOCK" = 1 ]; then
    [ "$shadow_layers" -ge 1 ] || return 1
  fi
  target_matches_patched_inode
}

remove_target_mounts() {
  remove_zygote_payload_mounts || return 1
  attempts=0
  while target_is_mounted; do
    ownership="$(target_mount_counts)" || return 1
    total_mounts="${ownership%%:*}"
    urv_mounts="${ownership##*:}"
    [ "$total_mounts" = 1 ] && target_matches_urv_inode && urv_mounts=1
    [ "$total_mounts" = "$urv_mounts" ] || return 1
    [ "$attempts" -lt 16 ] || return 1
    if ! umount "$URV_STOCK_PATH"; then
      # Re-check the visible stack before lazy detach. Another root tool can race
      # URV even while URV's own package lock is held.
      ownership="$(target_mount_counts)" || return 1
      total_mounts="${ownership%%:*}"
      urv_mounts="${ownership##*:}"
      [ "$total_mounts" = 1 ] && target_matches_urv_inode && urv_mounts=1
      if [ "$total_mounts" -gt 0 ]; then
        [ "$total_mounts" = "$urv_mounts" ] || return 1
        umount -l "$URV_STOCK_PATH" || return 1
      fi
    fi
    attempts=$((attempts + 1))
  done
}

remove_failed_urv_mounts() {
  remove_zygote_payload_mounts || return 1
  ownership="$(target_mount_counts)" || return 1
  total_mounts="${ownership%%:*}"
  urv_mounts="${ownership##*:}"
  [ "$total_mounts" -gt 0 ] || return 0
  if [ "$total_mounts" = "$urv_mounts" ]; then
    remove_target_mounts || return 1
  elif target_matches_urv_inode; then
    # The URV layer is currently on top, so peeling exactly one layer cannot remove a foreign mount.
    if ! umount "$URV_STOCK_PATH" && target_is_mounted; then
      target_matches_urv_inode || return 1
      umount -l "$URV_STOCK_PATH" || return 1
    fi
  fi
  ownership="$(target_mount_counts)" || return 1
  [ "${ownership##*:}" = 0 ]
}

stop_and_wait() {
  installed_users="$(installed_user_ids)" || return 1
  for installed_user in $installed_users; do
    am force-stop --user "$installed_user" "$URV_PACKAGE" || return 1
  done
  stop_checks=0
  while [ "$stop_checks" -lt 50 ]; do
    process_list="$(ps -A -o args 2>/dev/null)" || return 1
    if ! echo "$process_list" | awk -v pkg="$URV_PACKAGE" \
        '$1 == pkg || index($1, pkg ":") == 1 { found=1 } END { exit !found }'; then
      return 0
    fi
    sleep 0.1
    stop_checks=$((stop_checks + 1))
  done
  return 1
}

zygote_pids() {
  { pidof zygote64 2>/dev/null || true; pidof zygote 2>/dev/null || true; } |
    tr ' ' '\n' | awk '/^[0-9]+$/ && $0 > 1' | sort -n -u
}

validate_zygote() {
  pid="$1"
  cmdline="$(cat "/proc/$pid/cmdline" 2>/dev/null)" || return 1
  case "$cmdline" in
    zygote|zygote64|zygote\ *|zygote64\ *|*--zygote*|*--nice-name=zygote*) return 0 ;;
  esac
  return 1
}

live_zygote_pids() {
  for pid in $(zygote_pids); do
    validate_zygote "$pid" && echo "$pid"
  done | paste -sd ' ' -
}

namespace_mount_ownership() {
  pid="$1"
  allowed_sources="$(known_mount_sources)" || return 1
  nsenter --mount="/proc/$pid/ns/mnt" -- awk \
    -v target="$URV_STOCK_PATH" \
    -v allowed="$allowed_sources" '
      BEGIN { count=split(allowed, candidates, "\n") }
      $5 == target {
        total++
        separator=0
        for (i=1; i<=NF; i++) if ($i == "-") { separator=i; break }
        root=$4
        source=(separator > 0 ? $(separator+2) : "")
        matched=0
        for (candidate=1; candidate<=count; candidate++) {
          if (root == candidates[candidate] || source == candidates[candidate]) {
            matched=1
            break
          }
        }
        if (matched) owned++
      }
      END { print total+0 ":" owned+0 }
    ' /proc/self/mountinfo
}

namespace_matches_payload() {
  pid="$1"
  ownership="$(namespace_mount_ownership "$pid")" || return 1
  expected_ownership="1:1"
  [ "$URV_PRESERVE_STOCK" = 1 ] && expected_ownership="2:2"
  [ "$ownership" = "$expected_ownership" ] || return 1
  source_inode="$(stat -c '%d:%i' "$URV_PATCHED_PATH" 2>/dev/null)" || return 1
  target_inode="$(nsenter --mount="/proc/$pid/ns/mnt" -- stat -c '%d:%i' "$URV_STOCK_PATH" 2>/dev/null)" || return 1
  [ "$source_inode" = "$target_inode" ] || return 1
  mounted_hash="$(nsenter --mount="/proc/$pid/ns/mnt" -- sha256sum "$URV_STOCK_PATH" 2>/dev/null | awk '{print $1}')" || return 1
  [ "$mounted_hash" = "$URV_PATCHED_SHA256" ]
}

namespace_matches_shadow() {
  pid="$1"
  [ "$URV_PRESERVE_STOCK" = 1 ] || return 1
  ownership="$(namespace_mount_ownership "$pid")" || return 1
  [ "$ownership" = "1:1" ] || return 1
  source_inode="$(stat -c '%d:%i' "$URV_STOCK_SHADOW_PATH" 2>/dev/null)" || return 1
  target_inode="$(nsenter --mount="/proc/$pid/ns/mnt" -- stat -c '%d:%i' "$URV_STOCK_PATH" 2>/dev/null)" || return 1
  [ "$source_inode" = "$target_inode" ]
}

namespace_target_is_mounted() {
  pid="$1"
  nsenter --mount="/proc/$pid/ns/mnt" -- awk -v target="$URV_STOCK_PATH" \
    '$5 == target { found=1 } END { exit !found }' /proc/self/mountinfo
}

namespace_target_clear() {
  ! namespace_target_is_mounted "$1"
}

namespace_visible_matches_urv_inode() {
  pid="$1"
  target_inode="$(nsenter --mount="/proc/$pid/ns/mnt" -- stat -c '%d:%i' "$URV_STOCK_PATH" 2>/dev/null)" || return 1
  rollback_path="${MODDIR%/*}/.$URV_PACKAGE-revanced.urv-rollback/$URV_PACKAGE.apk"
  backup_path="/data/adb/urv/transactions/$URV_PACKAGE/backup/module/$URV_PACKAGE.apk"
  rollback_shadow_path="${MODDIR%/*}/.$URV_PACKAGE-revanced.urv-rollback/$URV_PACKAGE-stock.apk"
  backup_shadow_path="/data/adb/urv/transactions/$URV_PACKAGE/backup/module/$URV_PACKAGE-stock.apk"
  legacy_path="/data/adb/revanced/$URV_PACKAGE/$URV_PACKAGE.apk"
  for source in "$URV_PATCHED_PATH" "$URV_STOCK_SHADOW_PATH" "$rollback_path" "$backup_path" \
      "$rollback_shadow_path" "$backup_shadow_path" "$legacy_path"; do
    [ -f "$source" ] || continue
    [ "$(stat -c '%d:%i' "$source" 2>/dev/null)" = "$target_inode" ] && return 0
  done
  return 1
}

namespace_has_urv_layer() {
  pid="$1"
  allowed_sources="$(known_mount_sources)" || return 1
  nsenter --mount="/proc/$pid/ns/mnt" -- awk -v target="$URV_STOCK_PATH" -v allowed="$allowed_sources" '
    BEGIN { count=split(allowed, candidates, "\n") }
    $5 == target {
      separator=0
      for (i=1; i<=NF; i++) if ($i == "-") { separator=i; break }
      root=$4
      source=(separator > 0 ? $(separator+2) : "")
      sub(/\\040\(deleted\)$/, "", root)
      sub(/\\040\(deleted\)$/, "", source)
      sub(/ \(deleted\)$/, "", root)
      sub(/ \(deleted\)$/, "", source)
      for (candidate=1; candidate<=count; candidate++) {
        if (root == candidates[candidate] || source == candidates[candidate]) owned=1
      }
    }
    END { exit !owned }
  ' /proc/self/mountinfo
}

mount_and_verify_zygotes() {
  preflight_stable=0
  preflight_attempt=0
  while [ "$preflight_attempt" -lt 20 ]; do
    before="$(live_zygote_pids)"
    if [ -z "$before" ]; then
      preflight_attempt=$((preflight_attempt + 1))
      sleep 0.1
      continue
    fi
    zygote_changed=0
    # Check every namespace before changing any of them. This prevents URV from
    # temporarily covering a mount owned by another root tool.
    for pid in $before; do
      validate_zygote "$pid" || { zygote_changed=1; continue; }
      if ! namespace_matches_payload "$pid" &&
         ! namespace_matches_shadow "$pid" &&
         ! namespace_target_clear "$pid"; then
        validate_zygote "$pid" || { zygote_changed=1; continue; }
        return 1
      fi
    done
    after="$(live_zygote_pids)"
    if [ "$zygote_changed" = 0 ] && [ "$before" = "$after" ]; then
      preflight_stable=1
      break
    fi
    preflight_attempt=$((preflight_attempt + 1))
    sleep 0.1
  done
  [ "$preflight_stable" = 1 ] || return 1

  mount_stable=0
  mount_attempt=0
  while [ "$mount_attempt" -lt 20 ]; do
    pids="$(live_zygote_pids)"
    if [ -z "$pids" ]; then
      mount_attempt=$((mount_attempt + 1))
      sleep 0.1
      continue
    fi
    zygote_changed=0
    for pid in $pids; do
      validate_zygote "$pid" || { zygote_changed=1; continue; }
      if ! namespace_matches_payload "$pid"; then
        shadow_ready=0
        if [ "$URV_PRESERVE_STOCK" = 1 ] && namespace_matches_shadow "$pid"; then
          shadow_ready=1
        elif namespace_target_clear "$pid"; then
          if [ "$URV_PRESERVE_STOCK" = 1 ]; then
            if ! nsenter --mount="/proc/$pid/ns/mnt" -- mount -o bind "$URV_STOCK_SHADOW_PATH" "$URV_STOCK_PATH"; then
              validate_zygote "$pid" || { zygote_changed=1; continue; }
              return 1
            fi
            shadow_ready=1
          fi
        else
          validate_zygote "$pid" || { zygote_changed=1; continue; }
          return 1
        fi
        if [ "$shadow_ready" = 1 ] &&
           ! nsenter --mount="/proc/$pid/ns/mnt" -- mount -o private none "$URV_STOCK_PATH"; then
          validate_zygote "$pid" || { zygote_changed=1; continue; }
          return 1
        fi
        if ! nsenter --mount="/proc/$pid/ns/mnt" -- mount -o bind "$URV_PATCHED_PATH" "$URV_STOCK_PATH"; then
          validate_zygote "$pid" || { zygote_changed=1; continue; }
          return 1
        fi
      fi
      if ! namespace_matches_payload "$pid"; then
        validate_zygote "$pid" || { zygote_changed=1; continue; }
        return 1
      fi
    done
    after="$(live_zygote_pids)"
    if [ "$zygote_changed" = 0 ] && [ "$pids" = "$after" ]; then
      mount_stable=1
      break
    fi
    mount_attempt=$((mount_attempt + 1))
    sleep 0.1
  done
  [ "$mount_stable" = 1 ]
}

remove_zygote_payload_mounts() {
  for pid in $(zygote_pids); do
    validate_zygote "$pid" || continue
    [ "$(readlink /proc/self/ns/mnt)" != "$(readlink /proc/$pid/ns/mnt)" ] || continue
    attempts=0
    while namespace_target_is_mounted "$pid"; do
      if namespace_visible_matches_urv_inode "$pid"; then
        [ "$attempts" -lt 16 ] || return 1
        if ! nsenter --mount="/proc/$pid/ns/mnt" -- umount "$URV_STOCK_PATH" &&
           namespace_target_is_mounted "$pid"; then
          # Match Manager cleanup semantics: prove the visible layer is still URV-owned
          # immediately before using lazy detach.
          namespace_visible_matches_urv_inode "$pid" || return 1
          nsenter --mount="/proc/$pid/ns/mnt" -- umount -l "$URV_STOCK_PATH" || return 1
        fi
        attempts=$((attempts + 1))
      elif namespace_has_urv_layer "$pid"; then
        # A foreign layer hides a URV layer. Do not peel another owner's mount.
        return 1
      else
        # A foreign-only mount is unrelated to URV and must remain untouched.
        break
      fi
    done
    namespace_has_urv_layer "$pid" && return 1
  done
  return 0
}

boot_waited=0
log_status "Waiting for Android boot completion before ownership verification"
while [ "$boot_waited" -lt 300 ]; do
  [ "$(getprop sys.boot_completed 2>/dev/null)" = 1 ] && break
  sleep 1
  boot_waited=$((boot_waited + 1))
done
[ "$(getprop sys.boot_completed 2>/dev/null)" = 1 ] || {
  log_status "Android boot did not complete; deferring mount verification"
  exit 0
}

load_state() {
  [ -f "$state_file" ] || { log_status "Missing committed state; leaving stock active"; return 1; }
  [ "$(stat -c %a "$state_file" 2>/dev/null)" = 600 ] || {
    log_status "Unsafe state permissions; leaving stock active"
    return 1
  }
  [ "$(stat -c %u:%g "$state_file" 2>/dev/null)" = 0:0 ] || {
    log_status "Unsafe state ownership; leaving stock active"
    return 1
  }
  # state.env is generated by URV with single-quoted values and mode 0600.
  . "$state_file"
  [ "$URV_STATE_VERSION" = 1 ] || { log_status "Unsupported state version"; return 1; }
}

load_state || exit 0
locked_package="$URV_PACKAGE"

lock_dir="/data/adb/urv/locks"
lock_path="$lock_dir/$URV_PACKAGE.lock.d"
lock_owner="$lock_path/owner"
transaction_dir="/data/adb/urv/transactions/$URV_PACKAGE"
canonical_dir="$transaction_dir/backup/payload"
canonical_patched="$canonical_dir/patched"
canonical_stock="$canonical_dir/stock"
boot_pid=$$
boot_start="$(awk '{print $22}' /proc/$boot_pid/stat 2>/dev/null)"
[ -n "$boot_start" ] || {
  log_status "Unable to read boot lock owner identity"
  exit 0
}
mkdir -p "/data/adb/urv" "$lock_dir" || {
  log_status "Unable to initialize root lock storage"
  exit 0
}
chmod 700 "/data/adb/urv" "$lock_dir" || {
  log_status "Unable to secure root lock storage"
  exit 0
}

lock_is_old() {
  lock_mtime="$(stat -c %Y "$lock_path" 2>/dev/null || echo 0)"
  now="$(date +%s 2>/dev/null || echo 0)"
  [ "$lock_mtime" -gt 0 ] 2>/dev/null || return 1
  [ "$now" -ge "$lock_mtime" ] 2>/dev/null || return 1
  [ "$((now - lock_mtime))" -ge 5 ]
}

write_lock_owner() {
  printf '%s\n%s\n%s\n' "$boot_pid" "$boot_start" boot >"$lock_owner" || return 1
  chmod 600 "$lock_owner"
}

try_acquire_package_lock() {
  mkdir "$lock_path" 2>/dev/null || return 1
  chmod 700 "$lock_path" || {
    rmdir "$lock_path" 2>/dev/null || true
    return 1
  }
  write_lock_owner || {
    rm -f "$lock_owner"
    rmdir "$lock_path" 2>/dev/null || true
    return 1
  }
}

remove_stale_package_lock() {
  saved_pid=''
  saved_start=''
  if [ -f "$lock_owner" ]; then
    saved_pid="$(sed -n '1p' "$lock_owner" 2>/dev/null)"
    saved_start="$(sed -n '2p' "$lock_owner" 2>/dev/null)"
    case "$saved_pid" in
      ''|*[!0-9]*) saved_pid='' ;;
    esac
    current_start=''
    [ -z "$saved_pid" ] || current_start="$(awk '{print $22}' /proc/$saved_pid/stat 2>/dev/null || true)"
    if [ -n "$saved_pid" ] && [ -n "$saved_start" ]; then
      if [ -z "$current_start" ] || [ "$current_start" != "$saved_start" ]; then
        rm -rf "$lock_path"
        return 0
      fi
      return 1
    fi
  fi
  lock_is_old || return 1
  rm -rf "$lock_path"
}

acquire_package_lock() {
  lock_waited=0
  while [ "$lock_waited" -lt 5 ]; do
    try_acquire_package_lock && return 0
    [ -d "$lock_path" ] || return 1
    remove_stale_package_lock && continue
    sleep 1
    lock_waited=$((lock_waited + 1))
  done
  return 1
}

release_package_lock() {
  [ -d "$lock_path" ] || return 0
  [ -f "$lock_owner" ] || return 1
  saved_pid="$(sed -n '1p' "$lock_owner")"
  saved_start="$(sed -n '2p' "$lock_owner")"
  saved_transaction="$(sed -n '3p' "$lock_owner")"
  [ "$saved_pid" = "$boot_pid" ] &&
    [ "$saved_start" = "$boot_start" ] &&
    [ "$saved_transaction" = boot ] || return 1
  rm -f "$lock_owner"
  rmdir "$lock_path"
}

if [ -f "$transaction_dir/active.json" ]; then
  log_status "Incomplete transaction present; deferring entirely to Manager recovery"
  echo INCOMPLETE_TRANSACTION >"$transaction_dir/boot-status"
  exit 0
fi

if ! acquire_package_lock; then
  log_status "Transaction lock busy; deferring verification"
  exit 0
fi
trap 'release_package_lock || log_status "Unable to release transaction lock cleanly"' EXIT
trap 'exit 0' HUP INT TERM

load_state || exit 0
[ "$URV_PACKAGE" = "$locked_package" ] || {
  log_status "Committed package changed before the boot lock was acquired; deferring mount verification"
  exit 0
}

ownership_waited=0
ownership_ready=0
installed_users=''
while [ "$ownership_waited" -lt 30 ]; do
  if installed_users="$(installed_user_ids)"; then
    ownership_ready=1
    break
  fi
  sleep 1
  ownership_waited=$((ownership_waited + 1))
done
[ "$ownership_ready" = 1 ] || {
  log_status "PackageManager state is still unavailable; deferring ownership verification"
  exit 0
}
if ! echo "$installed_users" | grep -Fx "$URV_USER_ID" >/dev/null; then
  log_status "Committed Android user no longer owns the package; removing URV mounts"
  if ! stop_and_wait || ! remove_target_mounts; then
    log_status "Unable to remove the stale user mount safely"
    echo REPAIR_REQUIRED >"$transaction_dir/boot-status"
  else
    echo REPATCH_REQUIRED >"$transaction_dir/boot-status"
  fi
  disable_module || log_status "Unable to persist module disable marker"
  exit 0
fi
other_users="$(echo "$installed_users" | awk -v committed="$URV_USER_ID" '$1 != committed')"
if [ -n "$other_users" ]; then
  log_status "Package is installed for another Android user; removing URV mounts"
  if ! stop_and_wait || ! remove_target_mounts; then
    log_status "Unable to remove the cross-user mount safely"
    echo REPAIR_REQUIRED >"$transaction_dir/boot-status"
  else
    echo REPATCH_REQUIRED >"$transaction_dir/boot-status"
  fi
  disable_module || log_status "Unable to persist module disable marker"
  exit 0
fi

path_waited=0
path_dump=''
while [ "$path_waited" -lt 30 ]; do
  path_dump="$(pm path --user "$URV_USER_ID" "$URV_PACKAGE" 2>/dev/null)"
  [ -n "$path_dump" ] && break
  sleep 1
  path_waited=$((path_waited + 1))
done
[ -n "$path_dump" ] || {
  log_status "PackageManager state is still unavailable; deferring mount verification"
  exit 0
}
current_path="$(echo "$path_dump" | sed -n 's/^package://p' | head -n 1)"

version_dump="$(dumpsys package "$URV_PACKAGE" 2>/dev/null)"
current_version_name="$(echo "$version_dump" | sed -n 's/^[[:space:]]*versionName=//p' | head -n 1)"
current_version_code="$(echo "$version_dump" | sed -n 's/^[[:space:]]*versionCode=\([0-9]*\).*/\1/p' | head -n 1)"
path_count="$(pm path --user "$URV_USER_ID" "$URV_PACKAGE" 2>/dev/null | wc -l | tr -d ' ')"
if [ "$URV_TOPOLOGY" = SINGLE ]; then expected_path_count=1; else expected_path_count=0; fi
if pm list packages -d --user "$URV_USER_ID" "$URV_PACKAGE" 2>/dev/null |
   grep -Fx "package:$URV_PACKAGE" >/dev/null; then
  current_enabled=0
else
  current_enabled=1
fi
launcher_line="$(cmd package resolve-activity --brief --user "$URV_USER_ID" \
  -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$URV_PACKAGE" 2>/dev/null)"
case "$launcher_line" in
  */*) current_launcher=1 ;;
  *) current_launcher=0 ;;
esac

mount_count="$(awk -v target="$URV_STOCK_PATH" '$5 == target { count++ } END { print count+0 }' /proc/self/mountinfo)"
mounted_hash=""
[ "$mount_count" -gt 0 ] && mounted_hash="$(sha256sum "$URV_STOCK_PATH" 2>/dev/null | awk '{print $1}')"

if [ "$mount_count" -gt 0 ]; then
  ownership="$(target_mount_counts)"
  urv_mount_count="${ownership##*:}"
  [ "$mount_count" = 1 ] && target_matches_urv_inode && urv_mount_count=1
  if [ "$mount_count" != "$urv_mount_count" ]; then
    log_status "Non-URV mount conflict at stock target; leaving it unchanged"
    echo REPAIR_REQUIRED >"$transaction_dir/boot-status"
    disable_module || log_status "Unable to persist module disable marker"
    exit 0
  fi
  if root_mount_layout_valid && [ "$mounted_hash" = "$URV_PATCHED_SHA256" ]; then
    if [ "$current_path" = "$URV_STOCK_PATH" ] &&
       [ "$current_version_name" = "$URV_VERSION_NAME" ] &&
       [ "$current_version_code" = "$URV_VERSION_CODE" ] &&
       [ "$path_count" = "$expected_path_count" ] &&
       [ "$current_enabled" = "$URV_ENABLED" ] &&
       [ "$current_launcher" = "$URV_LAUNCHER_RESOLVABLE" ]; then
      if mount_and_verify_zygotes && root_mount_layout_valid &&
         [ "$(sha256sum "$URV_STOCK_PATH" 2>/dev/null | awk '{print $1}')" = "$URV_PATCHED_SHA256" ]; then
        log_status "Early root and Zygote mounts verified"
        echo VERIFIED >"$transaction_dir/boot-status"
        exit 0
      fi
      log_status "Zygote namespace verification failed; retrying after package quiescence"
      stop_and_wait || {
        log_status "Unable to quiesce package; existing root mount left for Manager recovery"
        exit 0
      }
      if mount_and_verify_zygotes && root_mount_layout_valid &&
         [ "$(sha256sum "$URV_STOCK_PATH" 2>/dev/null | awk '{print $1}')" = "$URV_PATCHED_SHA256" ]; then
        log_status "Early Zygote namespace repair succeeded"
        echo VERIFIED >"$transaction_dir/boot-status"
        exit 0
      fi
      log_status "Zygote namespace repair failed; falling back to verified stock"
      remove_target_mounts || { log_status "Failed to remove every stale mount"; exit 0; }
      mount_count=0
    fi
  fi
  if [ "$mount_count" -gt 0 ]; then
    log_status "Early mount metadata mismatch; removing stale mount"
    stop_and_wait || { log_status "Unable to quiesce package; stale mount left for Manager recovery"; exit 0; }
    remove_target_mounts || { log_status "Failed to remove every stale mount"; exit 0; }
    mount_count=0
  fi
fi

stock_hash="$(sha256sum "$URV_STOCK_PATH" 2>/dev/null | awk '{print $1}')"
shadow_hash="$(sha256sum "$URV_STOCK_SHADOW_PATH" 2>/dev/null | awk '{print $1}')"
payload_hash="$(sha256sum "$URV_PATCHED_PATH" 2>/dev/null | awk '{print $1}')"

repair_payload_file() {
  source="$1"
  target="$2"
  expected_hash="$3"
  [ -f "$source" ] && [ ! -L "$source" ] || return 1
  [ "$(sha256sum "$source" 2>/dev/null | awk '{print $1}')" = "$expected_hash" ] || return 1
  next="$target.urv-repair"
  rm -f "$next"
  cp "$source" "$next" || return 1
  chmod 644 "$next" || return 1
  chown 0:0 "$next" || return 1
  chcon u:object_r:apk_data_file:s0 "$next" || return 1
  [ "$(sha256sum "$next" 2>/dev/null | awk '{print $1}')" = "$expected_hash" ] || return 1
  sync -f "$next" 2>/dev/null || sync
  mv -f "$next" "$target" || return 1
}

repair_module_payloads() {
  [ "$payload_hash" = "$URV_PATCHED_SHA256" ] ||
    repair_payload_file "$canonical_patched" "$URV_PATCHED_PATH" "$URV_PATCHED_SHA256" || return 1
  if [ "$URV_PRESERVE_STOCK" = 1 ] && [ "$shadow_hash" != "$URV_STOCK_SHADOW_SHA256" ]; then
    repair_payload_file "$canonical_stock" "$URV_STOCK_SHADOW_PATH" "$URV_STOCK_SHADOW_SHA256" || return 1
  fi
}

if [ "$payload_hash" != "$URV_PATCHED_SHA256" ] ||
   { [ "$URV_PRESERVE_STOCK" = 1 ] && [ "$shadow_hash" != "$URV_STOCK_SHADOW_SHA256" ]; }; then
  if repair_module_payloads; then
    log_status "Restored altered module payloads from verified canonical copies"
    payload_hash="$(sha256sum "$URV_PATCHED_PATH" 2>/dev/null | awk '{print $1}')"
    shadow_hash="$(sha256sum "$URV_STOCK_SHADOW_PATH" 2>/dev/null | awk '{print $1}')"
  else
    log_status "Module payload integrity changed and no verified canonical copy could restore it"
  fi
fi

if [ "$current_path" != "$URV_STOCK_PATH" ] ||
   [ "$current_version_name" != "$URV_VERSION_NAME" ] ||
   [ "$current_version_code" != "$URV_VERSION_CODE" ] ||
   [ "$path_count" != "$expected_path_count" ] ||
   [ "$current_enabled" != "$URV_ENABLED" ] ||
   [ "$current_launcher" != "$URV_LAUNCHER_RESOLVABLE" ] ||
   [ "$stock_hash" != "$URV_STOCK_SHA256" ] ||
   { [ "$URV_PRESERVE_STOCK" = 1 ] && [ "$shadow_hash" != "$URV_STOCK_SHADOW_SHA256" ]; } ||
   [ "$payload_hash" != "$URV_PATCHED_SHA256" ]; then
  [ "$current_path" = "$URV_STOCK_PATH" ] || log_status "Compatibility mismatch: installed base path changed"
  [ "$current_version_name" = "$URV_VERSION_NAME" ] || log_status "Compatibility mismatch: version name changed"
  [ "$current_version_code" = "$URV_VERSION_CODE" ] || log_status "Compatibility mismatch: version code changed"
  [ "$path_count" = "$expected_path_count" ] || log_status "Compatibility mismatch: APK topology changed"
  [ "$current_enabled" = "$URV_ENABLED" ] || log_status "Compatibility mismatch: enabled state changed"
  [ "$current_launcher" = "$URV_LAUNCHER_RESOLVABLE" ] || log_status "Compatibility mismatch: launcher resolution changed"
  [ "$stock_hash" = "$URV_STOCK_SHA256" ] || log_status "Compatibility mismatch: installed stock APK changed"
  [ "$URV_PRESERVE_STOCK" = 0 ] || [ "$shadow_hash" = "$URV_STOCK_SHADOW_SHA256" ] ||
    log_status "Compatibility mismatch: stock-shadow payload changed"
  [ "$payload_hash" = "$URV_PATCHED_SHA256" ] || log_status "Compatibility mismatch: patched payload changed"
  log_status "Compatibility changed; stock left active and repatching is required"
  echo REPATCH_REQUIRED >"$transaction_dir/boot-status"
  disable_module || log_status "Unable to persist module disable marker"
  exit 0
fi

stop_and_wait || {
  log_status "Package processes did not exit; leaving stock active"
  exit 0
}
if [ "$URV_PRESERVE_STOCK" = 1 ]; then
  chcon u:object_r:apk_data_file:s0 "$URV_PATCHED_PATH" "$URV_STOCK_SHADOW_PATH" || {
    log_status "Failed to set payload or stock shadow context; leaving real stock active"
    exit 0
  }
  mount -o bind "$URV_STOCK_SHADOW_PATH" "$URV_STOCK_PATH" || {
    log_status "Stock shadow bind mount failed; leaving real stock active"
    exit 0
  }
  mount -o private none "$URV_STOCK_PATH" || {
    log_status "Failed to isolate the stock shadow bind; restoring stock"
    remove_failed_urv_mounts || log_status "Unable to remove the partial stock-shadow mount"
    echo VERIFY_FAILED >"$transaction_dir/boot-status"
    disable_module || log_status "Unable to persist module disable marker"
    exit 0
  }
else
  chcon u:object_r:apk_data_file:s0 "$URV_PATCHED_PATH" || {
    log_status "Failed to set payload context; leaving stock active"
    exit 0
  }
fi
mount -o bind "$URV_PATCHED_PATH" "$URV_STOCK_PATH" || {
  log_status "Late patched bind mount failed; removing any stock-shadow layer"
  if remove_failed_urv_mounts && ! target_is_mounted; then
    echo VERIFY_FAILED >"$transaction_dir/boot-status"
  else
    log_status "Unable to remove the partial root mount; repair is required"
    echo REPAIR_REQUIRED >"$transaction_dir/boot-status"
  fi
  disable_module || log_status "Unable to persist module disable marker"
  exit 0
}

mounted_hash="$(sha256sum "$URV_STOCK_PATH" 2>/dev/null | awk '{print $1}')"
post_mount_shadow_hash=""
[ "$URV_PRESERVE_STOCK" = 0 ] ||
  post_mount_shadow_hash="$(sha256sum "$URV_STOCK_SHADOW_PATH" 2>/dev/null | awk '{print $1}')"
if { [ "$URV_PRESERVE_STOCK" = 1 ] && [ "$post_mount_shadow_hash" != "$URV_STOCK_SHADOW_SHA256" ]; } ||
   [ "$mounted_hash" != "$URV_PATCHED_SHA256" ] || ! mount_and_verify_zygotes ||
   ! root_mount_layout_valid; then
  log_status "Late root or Zygote mount verification failed; restoring stock"
  remove_zygote_payload_mounts || log_status "Unable to remove every Zygote mount"
  if remove_failed_urv_mounts && ! target_is_mounted; then
    echo VERIFY_FAILED >"$transaction_dir/boot-status"
  else
    log_status "Unable to prove an unmounted stock target; repair is required"
    echo REPAIR_REQUIRED >"$transaction_dir/boot-status"
  fi
  disable_module || log_status "Unable to persist module disable marker"
  exit 0
fi

log_status "Late root and Zygote mounts verified; app remains stopped"
echo VERIFIED >"$transaction_dir/boot-status"
