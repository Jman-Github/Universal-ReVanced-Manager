#!/bin/sh
# Exercise the boot service's completion feedback without sending real broadcasts.
set -eu
for function in wait_for_feedback_user notify_boot_result finish_boot_service; do
  eval "$(sed -n "/^$function() {/,/^}/p" app/src/main/assets/root/service.sh)"
done
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
MODDIR="$fixture"
URV_USER_ID=10
URV_PACKAGE=com.example.app
calls=0
delivery_fails=0
diagnostics=0
ticks=0
status_elapsed_ms=12340
scenario=unlocked
boot_lock_held=0
timeout() { shift; "$@"; }
awk() { echo "$ticks"; }
sleep() { ticks=$((ticks + $1)); }
am() {
  [ "$boot_lock_held" = 0 ] || return 1
  case "$1" in
    get-started-user-state)
      [ "$2" = 10 ]
      case "$scenario" in
        unsupported) return 1 ;;
        locked) echo RUNNING_LOCKED ;;
        unlocking) if [ "$ticks" -lt 2 ]; then echo RUNNING_UNLOCKING; else echo RUNNING_UNLOCKED; fi ;;
        *) echo RUNNING_UNLOCKED ;;
      esac ;;
    broadcast)
      calls=$((calls + 1))
      delivered="$*"
      [ "$delivery_fails" = 0 ] ;;
    *) return 1 ;;
  esac
}
log_status() { diagnostics=$((diagnostics + 1)); }
write_boot_status() { boot_status="$1"; }
for status in VERIFIED REPAIR_REQUIRED REPATCH_REQUIRED VERIFY_FAILED; do
  boot_status="$status"
  before="$calls"
  finish_boot_service
  [ "$calls" -eq "$((before + 1))" ]
  [ "$(cat "$MODDIR/boot-result")" = "$status" ]
  case "$delivered" in
    *"broadcast --user 10 --receiver-include-background"*"--es package com.example.app --es result $status --el completed_at 12340") ;;
    *) echo "Incorrect completion broadcast: $delivered" >&2; exit 1 ;;
  esac
done
before="$calls"
boot_status=WAITING_FOR_PACKAGE_MANAGER
finish_boot_service
[ "$boot_status" = DEFERRED ]
[ "$calls" = "$before" ]
boot_status=INCOMPLETE_TRANSACTION
finish_boot_service
[ "$calls" = "$before" ]
boot_status=UNKNOWN
finish_boot_service
[ "$calls" = "$before" ]
delivery_fails=1
boot_status=VERIFIED
finish_boot_service
[ "$diagnostics" = 1 ]
delivery_fails=0
scenario=unlocking
finish_boot_service
[ "$ticks" = 2 ]
case "$delivered" in
  *"--el completed_at 12340") ;;
  *) echo "Unlock waiting changed the completion timestamp" >&2; exit 1 ;;
esac
scenario=locked
ticks=0
before="$calls"
finish_boot_service
[ "$calls" = "$before" ]
[ "$ticks" = 300 ]
[ "$diagnostics" = 2 ]
scenario=unsupported
finish_boot_service
[ "$calls" -eq "$((before + 1))" ]
scenario=unlocked
boot_lock_held=1
release_package_lock() { boot_lock_held=0; }
exit_trap="$(sed -n '/^trap .*boot_lock_held.*finish_boot_service.*EXIT$/p' app/src/main/assets/root/service.sh)"
[ -n "$exit_trap" ]
exit_command="${exit_trap#trap \'}"
exit_command="${exit_command%\' EXIT}"
eval "$exit_command"
[ "$boot_lock_held" = 0 ]
[ "$calls" -eq "$((before + 2))" ]
echo "Root boot feedback tests passed"
