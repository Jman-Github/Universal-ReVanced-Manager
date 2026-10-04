#!/system/bin/sh
# Mounting before PackageManager is ready cannot prove that the package belongs
# to exactly one Android user. Defer all bind mounts to service.sh, which performs
# that ownership check before touching the global or Zygote mount namespaces.
MODDIR=${0%/*}
log="$MODDIR/log.txt"
exec >>"$log" 2>&1

echo "$(date +%s 2>/dev/null || echo 0) [post-fs-data] Mount deferred until Android user ownership can be verified"
