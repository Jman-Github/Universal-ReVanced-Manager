#!/system/bin/sh
package_name="__PKG_NAME__"
version="__VERSION__"
base_dir="/data/adb/revanced/$package_name"
log="$base_dir/log"
base_path="$base_dir/$package_name.apk"

rm "$log"

{

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 3; done
until [ -d "/sdcard/Android" ]; do sleep 1; done

mkdir -p "$base_dir"

# Unmount any existing installation to prevent multiple mounts.
grep "$package_name" /proc/mounts | while read -r line; do
  echo "$line" | cut -d " " -f 2 | sed "s/apk.*/apk/" | xargs -r umount -l
done

stock_path="$(pm path "$package_name" | grep base | sed 's/package://g')"
stock_version="$(dumpsys package "$package_name" | grep versionName | cut -d "=" -f2)"

echo "base_path: $base_path"
echo "stock_path: $stock_path"
echo "base_version: $version"
echo "stock_version: $stock_version"

if [ "$version" != "$stock_version" ]; then
  echo "Not mounting as versions don't match"
  exit 1
fi

if [ -z "$stock_path" ]; then
  echo "Not mounting as app info could not be loaded"
  exit 1
fi

if [ ! -f "$base_path" ]; then
  echo "Not mounting as patched APK is missing: $base_path"
  exit 1
fi

chcon u:object_r:apk_data_file:s0 "$base_path"
mount -o bind "$base_path" "$stock_path"

# Kill the app to force it to restart the mounted APK in case it is already running.
am force-stop "$package_name"

} >> "$log"
