"""Validate assembled runtime boundaries without installing or executing APKs."""
from pathlib import Path
import argparse
import io
import lzma
import struct
import time
import zipfile

ROOT = Path(__file__).resolve().parents[4]

def dex_classes(data):
    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]
    strings = u32(60)
    types = u32(68)
    count, definitions = u32(96), u32(100)
    names = set()
    for index in range(count):
        string_index = u32(types + 4 * u32(definitions + index * 32))
        offset = u32(strings + 4 * string_index)
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        end = data.index(0, offset)
        names.add(data[offset:end].decode("utf-8"))
    return names

def apk_classes(archive):
    result = set()
    for name in archive.namelist():
        if name.startswith("classes") and name.endswith(".dex"):
            result.update(dex_classes(archive.read(name)))
    return result

def validate():
    app = max((ROOT / "app/build/outputs/apk/release").glob("*universal.apk"),
              key=lambda path: path.stat().st_mtime_ns)
    plugin = ROOT / "revanced.v22-runtime-plugin/build/outputs/apk/release/revanced.v22-plugin.apk"
    with zipfile.ZipFile(app) as archive:
        entries = set(archive.namelist())
        assert "assets/reseam-runtime.apk" in entries
        assert "assets/morphe-runtime.apk" in entries
        assert "assets/jvm/tools-patcher-core.jar.xz" in entries
        assert not any("revanced22-patcher-core" in name for name in entries)
        assert not any("revanced.v22" in name and name.endswith(".apk") for name in entries)
        classes = apk_classes(archive)
        assert not any(name.startswith("Lapp/revanced/patcher/") for name in classes)
        assert "Lapp/universal/patch/manager/revanced/runtime/RevancedRuntimeEntry;" not in classes
        with zipfile.ZipFile(io.BytesIO(archive.read("assets/reseam-runtime.apk"))) as reseam:
            assert any(name.endswith("/libreseam-sdk-native.so") for name in reseam.namelist())
            assert "Lapp/universal/patch/manager/reseam/runtime/ReseamRuntimeEntry;" in apk_classes(reseam)
        with zipfile.ZipFile(io.BytesIO(lzma.decompress(archive.read("assets/jvm/tools-patcher-core.jar.xz")))) as tools:
            assert not any(name.startswith("app/revanced/patcher/") for name in tools.namelist())
    with zipfile.ZipFile(plugin) as archive:
        classes = apk_classes(archive)
        assert "Lapp/universal/patch/manager/revanced/runtime/RevancedRuntimeEntry;" in classes
        assert any(name.startswith("Lapp/revanced/patcher/") for name in classes)
        with zipfile.ZipFile(io.BytesIO(archive.read("assets/jvm/revanced22-patcher-core.jar"))) as host:
            assert "app/universal/patch/manager/revanced/runtime/RevancedRuntimeEntry.class" in host.namelist()
            frameworks = [name for name in host.namelist() if name.startswith("frameworks/") and not name.endswith("/")]
            assert frameworks, "Standalone v22 JVM host has no frameworks"
    print("PASS: UPM includes Morphe/Reseam, excludes the ReVanced engine, and retains SDK-free tools")
    print("PASS: standalone ReVanced v22 includes its ART engine, JVM host and framework data")

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--wait", type=int, default=0, help="Wait for an in-progress assembly to publish valid APKs")
args = parser.parse_args()
deadline = time.monotonic() + args.wait
while True:
    try:
        validate()
        break
    except (AssertionError, FileNotFoundError, zipfile.BadZipFile):
        if time.monotonic() >= deadline:
            raise
        time.sleep(1)
