#!/bin/sh
# Run on Android as well as the host to cover Toybox awk's multiline-input behavior.
set -eu
service="${1:-app/src/main/assets/root/service.sh}"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
for function in mountinfo_root_alias known_mount_sources target_mount_counts namespace_mount_ownership namespace_has_urv_layer; do
  sed -n "/^$function() {$/,/^}$/p" "$service"
done | sed 's@/proc/self/mountinfo@"$fixture/mountinfo"@g' >"$fixture/functions.sh"
. "$fixture/functions.sh"
if [ "${URV_TEST_REAL_NSENTER:-0}" = 0 ]; then
  nsenter() { shift 2; "$@"; }
fi
namespace_pid=$$
URV_PACKAGE=com.example.app
MODDIR=/data/adb/modules/com.example.app-revanced
URV_PATCHED_PATH="$MODDIR/$URV_PACKAGE.apk"
URV_STOCK_SHADOW_PATH="$MODDIR/$URV_PACKAGE-stock.apk"
URV_STOCK_PATH=/data/app/com.example.app/base.apk
write_layer() {
  printf '12 1 0:1 %s %s ro - ext4 /dev/block/dm-1 ro\n' "$1" "$URV_STOCK_PATH"
}
for source in "$URV_PATCHED_PATH" "${URV_PATCHED_PATH#/data}" "$URV_STOCK_SHADOW_PATH" \
    /data/adb/urv/transactions/com.example.app/backup/module/com.example.app.apk; do
  write_layer "$source" >"$fixture/mountinfo"
  [ "$(target_mount_counts)" = 1:1 ]
  [ "$(namespace_mount_ownership "$namespace_pid")" = 1:1 ]
  namespace_has_urv_layer "$namespace_pid"
done
write_layer "$URV_PATCHED_PATH" >"$fixture/mountinfo"
write_layer /data/adb/foreign/base.apk >>"$fixture/mountinfo"
[ "$(target_mount_counts)" = 2:1 ]
[ "$(namespace_mount_ownership "$namespace_pid")" = 2:1 ]
namespace_has_urv_layer "$namespace_pid"
write_layer /data/adb/foreign/base.apk >"$fixture/mountinfo"
[ "$(target_mount_counts)" = 1:0 ]
[ "$(namespace_mount_ownership "$namespace_pid")" = 1:0 ]
! namespace_has_urv_layer "$namespace_pid"
: >"$fixture/mountinfo"
[ "$(target_mount_counts)" = 0:0 ]
[ "$(namespace_mount_ownership "$namespace_pid")" = 0:0 ]
! namespace_has_urv_layer "$namespace_pid"

# Exercise the production fallback with each supported syntax and with both rejected.
sed -n '/^  mount --make-private/,/^    mount -o private/p' "$service" |
  sed 's/ || {$//' >"$fixture/private.sh"
[ -s "$fixture/private.sh" ]
mount() {
  printf '%s\n' "$*" >>"$fixture/mount-calls"
  case "$mode:$1" in
    long:--make-private|legacy:-o) return 0 ;;
    *) return 1 ;;
  esac
}
for mode in long legacy fail; do
  : >"$fixture/mount-calls"
  if ( . "$fixture/private.sh" ); then
    [ "$mode" != fail ]
  else
    [ "$mode" = fail ]
  fi
  case "$mode" in
    long) [ "$(wc -l <"$fixture/mount-calls")" -eq 1 ] ;;
    *) [ "$(wc -l <"$fixture/mount-calls")" -eq 2 ] ;;
  esac
done
echo 'Root service shell compatibility tests passed'
