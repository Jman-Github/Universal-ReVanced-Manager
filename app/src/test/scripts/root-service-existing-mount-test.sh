#!/bin/sh
# Test the real existing-mount branch: failed app quiescence must preserve verified mounts.
set -eu
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
sed -n '/^if \[ "$mount_count" -gt 0 \]; then$/,/^stock_hash=/p' app/src/main/assets/root/service.sh |
  sed '$d' >"$fixture/branch.sh"
[ -s "$fixture/branch.sh" ]
URV_STOCK_PATH=/data/app/base.apk
URV_PATCHED_SHA256=patched
URV_VERSION_NAME=1.0
URV_VERSION_CODE=42
URV_ENABLED=1
current_path="$URV_STOCK_PATH"
current_version_name=1.0
current_version_code=42
current_enabled=1
expected_path_count=1
path_count=1
split_compatible=1
mounted_hash=patched
target_mount_counts() { echo 2:2; }
target_matches_urv_inode() { return 0; }
root_mount_layout_valid() { return 0; }
split_set_matches() { return 0; }
sha256sum() { echo patched; }
log_status() { :; }
write_boot_status() { echo "$1" >"$fixture/status"; }
disable_module() { echo 'Unexpected disable' >&2; exit 1; }
remove_target_mounts() { echo 'Unexpected unmount of a busy app' >&2; exit 1; }
complete_verified_mount() { write_boot_status VERIFIED; [ "$stop_checks" = 0 ]; }
zygote_mounts_verified() { [ "$scenario" = healthy ]; }
for scenario in healthy repaired; do
  mount_count=2
  namespace_checks=0
  stop_checks=0
  mount_and_verify_zygotes() {
    namespace_checks=$((namespace_checks + 1))
    [ "$scenario" = repaired ]
  }
  stop_and_wait() {
    stop_checks=$((stop_checks + 1))
    [ "$scenario" = repaired ] && [ "$stop_checks" = 1 ]
  }
  # Production exit statements terminate only this subshell.
  ( . "$fixture/branch.sh" )
  case "$scenario" in
    healthy) [ "$(cat "$fixture/status")" = VERIFIED ] ;;
    repaired) [ "$(cat "$fixture/status")" = REPAIR_REQUIRED ] ;;
  esac
done
echo 'Root service busy existing-mount tests passed'
