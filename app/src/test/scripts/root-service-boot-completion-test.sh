#!/bin/sh
# Verify early mounting can release its lock and safely recheck at boot completion.
set -eu
for function in zygote_mounts_verified finish_verified_mount complete_verified_mount; do
  eval "$(sed -n "/^$function() {/,/^}/p" app/src/main/assets/root/service.sh)"
done
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
MODDIR="$fixture"
transaction_dir="$fixture"
state_file="$fixture/state.env"
URV_PACKAGE=com.example.app
locked_package="$URV_PACKAGE"
URV_USER_ID=0
URV_STOCK_PATH=/data/app/base.apk
URV_VERSION_NAME=1.0
URV_VERSION_CODE=42
URV_ENABLED=1
URV_PRESERVE_STOCK=1
URV_PATCHED_SHA256=patched
URV_STOCK_SHADOW_SHA256=stock
URV_STOCK_SHADOW_PATH=/data/adb/module/stock.apk
log_status() { :; }
write_boot_status() { status="$1"; }
getprop() {
  if [ "$scenario" = already_booted ] ||
     { [ "$ticks" -ge 2 ] && [ "$scenario" != boot_timeout ]; }; then echo 1; else echo 0; fi
}
awk() {
  if [ "${2:-}" = /proc/uptime ]; then echo "$ticks"; else command awk "$@"; fi
}
sha256sum() {
  case "$1" in
    "$state_file") command sha256sum "$@" ;;
    "$URV_STOCK_SHADOW_PATH") echo stock ;;
    *) echo patched ;;
  esac
}
release_package_lock() { releases=$((releases + 1)); }
acquire_package_lock() {
  acquisitions=$((acquisitions + 1))
  [ "$scenario" != lock_busy ] || return 1
  [ "$scenario" != locked_active ] || : >"$transaction_dir/active.json"
}
load_state() { [ "$scenario" != locked_transaction ] || URV_TRANSACTION_ID=replacement; return 0; }
read_package_state() {
  installed_users=0
  current_path="$URV_STOCK_PATH"
  current_version_name=1.0
  current_version_code=42
  current_enabled=1
  [ "$scenario" != identity_changed ] || current_version_code=43
}
root_mount_layout_valid() { [ "$scenario" != foreign ]; }
split_set_matches() { return 0; }
live_zygote_pids() { echo 123; }
validate_zygote() { return 0; }
namespace_splits_match() { return 0; }
namespace_matches_payload() { [ "$scenario" = healthy ] || [ "$namespace_repaired" = 1 ]; }
stop_and_wait() { echo stop >>"$fixture/calls"; [ "$scenario" != stop_failure ]; }
mount_and_verify_zygotes() {
  echo zygote >>"$fixture/calls"
  [ "$scenario" != namespace_failure ] || return 1
  namespace_repaired=1
}
sleep() {
  [ "$boot_lock_held" = 0 ] || { echo 'Boot completion wait held the package lock'; exit 1; }
  ticks=$((ticks + 1))
  case "$scenario" in
    superseded) echo replacement >"$state_file" ;;
    active) : >"$transaction_dir/active.json" ;;
    disable|remove) : >"$MODDIR/$scenario" ;;
  esac
}
for scenario in completed healthy already_booted superseded active disable remove lock_busy locked_active locked_transaction foreign identity_changed stop_failure namespace_failure boot_timeout; do
  rm -f "$fixture/active.json" "$fixture/disable" "$fixture/remove"
  echo original >"$state_file"
  : >"$fixture/calls"
  ticks=0
  acquisitions=0
  releases=0
  namespace_repaired=0
  boot_lock_held=1
  URV_TRANSACTION_ID=original
  complete_verified_mount
  case "$scenario" in
    completed)
      [ "$status:$releases:$acquisitions:$namespace_repaired" = VERIFIED:1:1:1 ]
      [ "$(cat "$fixture/calls")" = "$(printf 'stop\nzygote\nstop')" ] ;;
    healthy) [ "$status:$releases:$acquisitions" = VERIFIED:1:1 ]; [ ! -s "$fixture/calls" ] ;;
    already_booted) [ "$status:$releases:$acquisitions" = VERIFIED:0:0 ] ;;
    foreign|identity_changed)
      [ "$status" = REPAIR_REQUIRED ]
      [ ! -s "$fixture/calls" ] ;;
    stop_failure|namespace_failure) [ "$status" = REPAIR_REQUIRED ] ;;
    boot_timeout) [ "$ticks:$acquisitions" = 300:0 ] ;;
    lock_busy) [ "$status:$boot_lock_held" = VERIFIED:0 ]; [ ! -s "$fixture/calls" ] ;;
    *) [ ! -s "$fixture/calls" ] ;;
  esac
done
echo 'Root service boot-completion recheck tests passed'
