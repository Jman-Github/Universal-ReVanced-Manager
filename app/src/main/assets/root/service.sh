#!/system/bin/sh
package_name="__PKG_NAME__"
version="__VERSION__"
base_dir="/data/adb/revanced/$package_name"
log="$base_dir/log"
base_path="$base_dir/$package_name.apk"

rm "$log"

{

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 5

mkdir -p "$base_dir"

stock_path="$(pm path "$package_name" | grep base | sed 's/package://g')"
stock_version="$(dumpsys package "$package_name" | grep versionName | cut -d "=" -f2)"

echo "base_path: $base_path"
echo "stock_path: $stock_path"
echo "base_version: $version"
echo "stock_version: $stock_version"

if mount | grep -q "$stock_path" ; then
  echo "Not mounting as stock path is already mounted"
  exit 1
fi

if [ "$version" != "$stock_version" ]; then
  echo "Not mounting as versions don't match"
  exit 1
fi

if [ -z "$stock_path" ]; then
  echo "Not mounting as app info could not be loaded"
  exit 1
fi

mount -o bind "$base_path" "$stock_path"

} >> "$log"
