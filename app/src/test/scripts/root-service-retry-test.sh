#!/bin/sh
# Exercise retries without mounting files or starting Manager.
set -eu
eval "$(sed -n '/^run_boot_recovery() {/,/^}/p' app/src/main/assets/root/service.sh)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
MODDIR="$fixture"
awk() { echo "$ticks"; }
sleep() { ticks=$((ticks + $1)); }
log_status() { :; }
run_boot_recovery_attempt() {
  attempts=$((attempts + 1))
  case "$scenario" in
    transient) if [ "$attempts" -lt 3 ]; then status=DEFERRED; else status=VERIFIED; fi ;;
    repair) if [ "$attempts" -lt 2 ]; then status=REPAIR_REQUIRED; else status=VERIFIED; fi ;;
    deadline) status=DEFERRED ;;
    disabled) status=DEFERRED; : >"$MODDIR/disable" ;;
    removed) status=DEFERRED; : >"$MODDIR/remove" ;;
    invalid) return 0 ;;
    *) status="$scenario" ;;
  esac
  printf '%s\n' "$status" >"$MODDIR/boot-result"
}
for scenario in transient repair deadline disabled removed invalid VERIFIED REPATCH_REQUIRED VERIFY_FAILED INCOMPLETE_TRANSACTION; do
  ticks=0
  attempts=0
  rm -f "$MODDIR/disable" "$MODDIR/remove"
  if run_boot_recovery; then
    [ "$scenario" != invalid ]
  else
    [ "$scenario" = invalid ]
  fi
  case "$scenario" in
    transient) [ "$attempts:$ticks" = 3:10 ] ;;
    repair) [ "$attempts:$ticks" = 2:5 ] ;;
    deadline) [ "$attempts:$ticks" = 120:600 ] ;;
    *) [ "$attempts:$ticks" = 1:0 ] ;;
  esac
done
# A child readiness loop shares the outer deadline instead of starting a new budget.
eval "$(sed -n '/^wait_for_package_manager() {/,/^}/p' app/src/main/assets/root/service.sh)"
ticks=0
boot_recovery_deadline=3
transaction_dir="$fixture"
readiness_attempts=0
read_package_state() { readiness_attempts=$((readiness_attempts + 1)); return 1; }
if wait_for_package_manager; then exit 1; fi
[ "$readiness_attempts:$ticks" = 3:3 ]
# Enter the global namespace before any boot pass, preserving the script arguments.
entry="$(sed -n '/^if \[ "$(readlink \/proc\/self\/ns\/mnt)"/,/^fi$/p' app/src/main/assets/root/service.sh | sed 's/exec nsenter/nsenter/')"
[ -n "$entry" ]
(
  readlink() { case "$1" in /proc/self/ns/mnt) echo private ;; *) echo global ;; esac; }
  nsenter() {
    [ "$*" = "--mount=/proc/1/ns/mnt -- /system/bin/sh $0 --urv-recovery-attempt" ]
  }
  set -- --urv-recovery-attempt
  eval "$entry"
)
echo 'Root boot retry and global namespace tests passed'
