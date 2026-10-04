#!/bin/sh
# Exercise the production process-stop loop without Android or root privileges.
set -eu
eval "$(sed -n '/^stop_and_wait() {/,/^}/p' app/src/main/assets/root/service.sh)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
URV_PACKAGE=com.example.app
installed_user_ids() { [ "$scenario" != ownership_failure ] || return 1; printf '0\n10\n'; }
timeout() {
  shift
  if [ "$1" = /system/bin/ps ]; then shift; ps "$@"; else "$@"; fi
}
log_status() { :; }
awk() {
  if [ "${2:-}" = /proc/uptime ]; then cat "$fixture/time"; else command awk "$@"; fi
}
pm() {
  [ "$scenario" != uid_failure ] || return 1
  [ "$scenario" != uid_missing ] || return 0
  case "$*" in
    "list packages --user 0 -U $URV_PACKAGE") echo "package:$URV_PACKAGE uid:10123" ;;
    "list packages --user 10 -U $URV_PACKAGE") echo "package:$URV_PACKAGE uid:1010123" ;;
    *) return 1 ;;
  esac
}
sleep() { ticks="$(cat "$fixture/time")"; echo "$((ticks + 1))" >"$fixture/time"; }
ps() {
  [ "$*" = '-A -o UID,ARGS' ] && [ "$scenario" != ps_failure ] || return 1
  cat "$fixture/processes"
}
am() {
  echo "$*" >>"$fixture/calls"
  case "$scenario" in
    transient)
      [ "$(wc -l <"$fixture/calls")" -ge 3 ] || return 1
      : >"$fixture/processes" ;;
    stopped_despite_failure) : >"$fixture/processes"; return 1 ;;
    persistent|persistent_custom|idle_binder_failure|lookalike) return 1 ;;
    *) : >"$fixture/processes" ;;
  esac
}
for scenario in idle idle_binder_failure lookalike child isolated custom transient stopped_despite_failure persistent persistent_custom ps_failure ownership_failure uid_failure uid_missing; do
  echo 0 >"$fixture/time"
  : >"$fixture/calls"
  case "$scenario" in
    idle|idle_binder_failure) : >"$fixture/processes" ;;
    lookalike) echo '10124 com.example.app.other' >"$fixture/processes" ;;
    child) echo '1010123 com.example.app:worker' >"$fixture/processes" ;;
    isolated) echo '99000 com.example.app:isolated' >"$fixture/processes" ;;
    custom|persistent_custom) echo '10123 org.example.worker' >"$fixture/processes" ;;
    *) echo "10123 $URV_PACKAGE" >"$fixture/processes" ;;
  esac
  if stop_and_wait; then
    case "$scenario" in persistent|persistent_custom|ps_failure|ownership_failure|uid_failure|uid_missing) echo "Unexpected success: $scenario"; exit 1 ;; esac
  else
    case "$scenario" in persistent|persistent_custom|ps_failure|ownership_failure|uid_failure|uid_missing) : ;; *) echo "Unexpected failure: $scenario"; exit 1 ;; esac
  fi
  case "$scenario" in
    transient) [ "$(wc -l <"$fixture/calls")" = 4 ] ;;
    idle|idle_binder_failure|lookalike|child|isolated|custom|stopped_despite_failure)
      [ "$(wc -l <"$fixture/calls")" = 2 ]
      grep -Fx "force-stop --user 10 $URV_PACKAGE" "$fixture/calls" >/dev/null ;;
    persistent|persistent_custom) [ "$(cat "$fixture/time")" = 15 ] ;;
    ps_failure|ownership_failure|uid_failure|uid_missing) [ ! -s "$fixture/calls" ] ;;
  esac
done
echo 'Root service process-stop tests passed'
