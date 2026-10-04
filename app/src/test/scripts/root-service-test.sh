#!/bin/sh
# Run from repository root. Production functions, mocked Android commands, no root.
set -eu
for function in installed_user_ids split_set_matches read_package_state wait_for_package_manager acquire_ready_package_lock write_boot_status wait_for_feedback_user notify_boot_result finish_boot_service; do
  eval "$(sed -n "/^$function() {/,/^}/p" app/src/main/assets/root/service.sh)"
done
URV_PACKAGE=com.example.app
URV_USER_ID=0
URV_TOPOLOGY=SINGLE
URV_STOCK_SPLITS=''
URV_STOCK_PATH=/data/app/example/base.apk
URV_VERSION_NAME=1.0
URV_VERSION_CODE=42
URV_ENABLED=1
boot_completed=1
getprop() { echo "$boot_completed"; }
scenario=ready
timeout() { shift; "$@"; }
am() { [ "$1" = broadcast ]; }
pm() {
  case "$*" in
    "list users")
      echo 'UserInfo{0:Owner:13} running'
      [ "$scenario" != users_failure ] || return 1 ;;
    "list packages --user "*)
      [ "$scenario" != packages_failure ] || return 1
      [ "$scenario" = absent ] || echo "package:$URV_PACKAGE" ;;
    "path "*)
      [ "$scenario" != path_failure ] || return 1
      [ "$scenario" != path_empty ] || return 0
      if [ "$scenario" = split_reordered ]; then
        echo 'package:/data/app/example/split.apk'
        echo 'package:/data/app/example/base.apk'
        return 0
      fi
      echo 'package:/data/app/example/base.apk'
      [ "$scenario" != split ] || echo 'package:/data/app/example/split.apk' ;;
    "list packages -d "*)
      [ "$scenario" != enabled_failure ] || return 1
      [ "$scenario" != disabled ] || echo "package:$URV_PACKAGE" ;;
    *) return 1 ;;
  esac
  return 0
}
dumpsys() {
  [ "$scenario" != version_failure ] || return 1
  [ "$scenario" != version_empty ] || return 0
  printf '  versionName=1.0\n  versionCode=42 minSdk=23\n'
}
cmd() { echo 'Unexpected launcher query during mount readiness' >&2; exit 1; }
for scenario in ready split disabled; do
  read_package_state
  [ "$current_version_code" = 42 ]
  case "$scenario" in
    ready) [ "$path_count:$current_enabled" = 1:1 ] ;;
    split) [ "$path_count" = 2 ] ;;
    disabled) [ "$current_enabled" = 0 ] ;;
  esac
done
for scenario in users_failure packages_failure path_failure path_empty version_failure version_empty enabled_failure; do
  if read_package_state; then echo "Unexpected success: $scenario" >&2; exit 1; fi
done
scenario=absent
read_package_state
[ -z "$installed_users" ]
# An incomplete boot registry must not prove removal or compatibility changes.
boot_completed=0
if read_package_state; then echo 'Early absence was accepted' >&2; exit 1; fi
scenario=ready
read_package_state
URV_VERSION_CODE=43
if read_package_state; then echo 'Early identity mismatch was accepted' >&2; exit 1; fi
URV_VERSION_CODE=42
boot_completed=1
# Verify the complete recorded split set, including same-version content changes.
scenario=split
URV_TOPOLOGY=SPLIT
fixture_hash=$(printf '%064d' 0)
URV_STOCK_SPLITS="split.apk:$fixture_hash"
sha256sum() { printf '%s  %s\n' "$fixture_hash" "$1"; }
read_package_state
[ "$split_compatible" = 1 ]
scenario=split_reordered
read_package_state
[ "$current_path" = /data/app/example/base.apk ]
[ "$split_compatible" = 1 ]
scenario=ready
if split_set_matches refresh; then echo 'Missing split was accepted' >&2; exit 1; fi
scenario=split
fixture_hash=$(printf '%064d' 1)
read_package_state
[ "$split_compatible" = 0 ]
URV_STOCK_SPLITS="split.apk:$fixture_hash split.apk:$fixture_hash"
read_package_state
[ "$split_compatible" = 0 ]
URV_STOCK_SPLITS="../split.apk:$fixture_hash"
read_package_state
[ "$split_compatible" = 0 ]
URV_STOCK_SPLITS="missing.apk:$fixture_hash"
read_package_state
[ "$split_compatible" = 0 ]
URV_STOCK_SPLITS=''
URV_TOPOLOGY=SINGLE
# A foreign split in a Zygote must fail preflight before any bind mount is attempted.
eval "$(sed -n '/^mount_and_verify_zygotes() {/,/^}/p' app/src/main/assets/root/service.sh)"
live_zygote_pids() { echo 123; }
validate_zygote() { return 0; }
namespace_splits_match() { return 1; }
nsenter() { echo 'Unexpected namespace mutation' >&2; exit 1; }
if mount_and_verify_zygotes; then echo 'Foreign split was accepted' >&2; exit 1; fi
# Virtual clock exercises retries without waiting five real minutes.
MODDIR="$(mktemp -d)"
transaction_dir="$MODDIR"
trap 'rm -rf "$MODDIR"' EXIT
ticks=0
attempts=0
awk() {
  case "$1" in
    *'printf "%.0f"'*) echo "$((ticks * 1000))" ;;
    *) echo "$ticks" ;;
  esac
}
sleep() { ticks=$((ticks + $1)); }
boot_id=test-boot
URV_TRANSACTION_ID=test-transaction
boot_started_epoch=1000
boot_started_uptime=0
log_status() { :; }
# An old checkpoint is replaced by a record for this boot.
printf 'INCOMPLETE_TRANSACTION\nboot_id=old-boot\n' >"$transaction_dir/boot-status"
write_boot_status WAITING_FOR_PACKAGE_MANAGER
[ "$(head -n 1 "$transaction_dir/boot-status")" = WAITING_FOR_PACKAGE_MANAGER ]
grep -Fx 'boot_id=test-boot' "$transaction_dir/boot-status" >/dev/null
ticks=15
write_boot_status VERIFIED
grep -Fx 'elapsed_seconds=15' "$transaction_dir/boot-status" >/dev/null
[ "$status_elapsed_ms" = 15000 ]
finish_boot_service
[ "$(head -n 1 "$transaction_dir/boot-status")" = VERIFIED ]
write_boot_status MOUNTING
finish_boot_service
[ "$(head -n 1 "$transaction_dir/boot-status")" = DEFERRED ]
# Readiness allows an immediate attempt, with no startup settling delay.
ticks=0
read_package_state() { attempts=$((attempts + 1)); return 0; }
wait_for_package_manager
[ "$attempts:$ticks" = 1:0 ]
attempts=0
read_package_state() { attempts=$((attempts + 1)); [ "$attempts" -ge 4 ]; }
wait_for_package_manager
[ "$attempts:$ticks" = 4:3 ]
read_package_state() { return 1; }
ticks=0
if wait_for_package_manager; then exit 1; fi
[ "$ticks" = 300 ]
for marker in disable remove active.json; do
  : >"$MODDIR/$marker"
  ticks=0
  if wait_for_package_manager; then exit 1; fi
  [ "$ticks" = 0 ]
  rm "$MODDIR/$marker"
done
# The locked query can fail after the unlocked readiness probe succeeds.
locked_package="$URV_PACKAGE"
log_status() { :; }
load_state() { loads=$((loads + 1)); }
acquire_package_lock() {
  acquisitions=$((acquisitions + 1))
  [ "$scenario" != interrupted ] || [ "$acquisitions" != 2 ] ||
    : >"$transaction_dir/active.json"
}
release_package_lock() { releases=$((releases + 1)); }
sleep() {
  [ "$boot_lock_held" = 0 ] || { echo 'Retry slept with lock held' >&2; exit 1; }
  ticks=$((ticks + $1))
}
for scenario in transient persistent interrupted; do
  ticks=0
  attempts=0
  acquisitions=0
  releases=0
  loads=0
  boot_lock_held=0
  read_package_state() {
    attempts=$((attempts + 1))
    if [ "$scenario" = persistent ]; then
      [ "$boot_lock_held" = 0 ]
    else
      [ "$attempts" != 2 ]
    fi
  }
  if acquire_ready_package_lock; then
    [ "$scenario" = transient ]
    [ "$attempts:$acquisitions:$releases:$loads:$ticks:$boot_lock_held" = 4:2:1:2:1:1 ]
  else
    case "$scenario" in
      persistent)
        [ "$ticks:$acquisitions:$releases:$boot_lock_held" = 300:300:300:0 ] ;;
      interrupted)
        [ "$acquisitions:$loads" = 2:1 ]
        [ "$(head -n 1 "$transaction_dir/boot-status")" = INCOMPLETE_TRANSACTION ]
        rm "$transaction_dir/active.json" "$transaction_dir/boot-status" ;;
      *) exit 1 ;;
    esac
  fi
done
echo 'Root service readiness and locked retry tests passed'
