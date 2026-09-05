#!/bin/sh
# Run from repository root. Production functions, mocked Android commands, no root.
set -eu
for function in installed_user_ids read_package_state wait_for_package_manager acquire_ready_package_lock; do
  eval "$(sed -n "/^$function() {/,/^}/p" app/src/main/assets/root/service.sh)"
done
URV_PACKAGE=com.example.app
URV_USER_ID=0
scenario=ready
timeout() { shift; "$@"; }
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
cmd() {
  [ "$scenario" != launcher_failure ] || return 1
  case "$scenario" in
    launcher_empty) return 0 ;;
    disabled) echo 'No activity found' ;;
    *) echo 'com.example.app/.MainActivity' ;;
  esac
}
for scenario in ready split disabled; do
  read_package_state
  [ "$current_version_code" = 42 ]
  case "$scenario" in
    ready) [ "$path_count:$current_enabled:$current_launcher" = 1:1:1 ] ;;
    split) [ "$path_count" = 2 ] ;;
    disabled) [ "$current_enabled:$current_launcher" = 0:0 ] ;;
  esac
done
for scenario in users_failure packages_failure path_failure path_empty version_failure version_empty enabled_failure launcher_failure launcher_empty; do
  if read_package_state; then echo "Unexpected success: $scenario" >&2; exit 1; fi
done
scenario=absent
read_package_state
[ -z "$installed_users" ]
# Virtual clock exercises retries without waiting five real minutes.
MODDIR="$(mktemp -d)"
transaction_dir="$MODDIR"
trap 'rm -rf "$MODDIR"' EXIT
ticks=0
attempts=0
awk() { echo "$ticks"; }
sleep() { ticks=$((ticks + $1)); }
read_package_state() { attempts=$((attempts + 1)); [ "$attempts" -ge 4 ]; }
wait_for_package_manager
[ "$attempts:$ticks" = 4:15 ]
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
    [ "$attempts:$acquisitions:$releases:$loads:$ticks:$boot_lock_held" = 4:2:1:2:5:1 ]
  else
    case "$scenario" in
      persistent)
        [ "$ticks:$acquisitions:$releases:$boot_lock_held" = 300:60:60:0 ] ;;
      interrupted)
        [ "$acquisitions:$loads" = 2:1 ]
        [ "$(cat "$transaction_dir/boot-status")" = INCOMPLETE_TRANSACTION ]
        rm "$transaction_dir/active.json" "$transaction_dir/boot-status" ;;
      *) exit 1 ;;
    esac
  fi
done
echo 'Root service readiness and locked retry tests passed'
