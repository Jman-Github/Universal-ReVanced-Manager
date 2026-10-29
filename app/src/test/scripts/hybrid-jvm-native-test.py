"""Smoke-test the pinned 64-bit Android JDK 25 through JNI before assembleRelease.

Uses the cached pinned runtime and a disposable emulator directory. No apps are installed.
The release build separately links the production module image.
"""
from pathlib import Path
import hashlib
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[4]
JDK = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17"))
SDK = Path(os.environ["ANDROID_HOME"])
ADB = SDK / "platform-tools/adb.exe"
config = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
feature, digest = re.search(r'"x86_64" to EmbeddedJvm\((\d+), "[^"]+", "([^"]+)"', config).groups()
assert feature == "25"
archive = ROOT / ".gradle-user-home/caches/urv-embedded-jvm" / (digest + ".tar.xz")
assert hashlib.sha256(archive.read_bytes()).hexdigest() == digest

def run(*args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, **kwargs)

with tempfile.TemporaryDirectory(prefix="hybrid-jvm-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    extracted = work / "runtime"
    extracted.mkdir()
    with tarfile.open(archive, "r:xz") as tar:
        tar.extractall(extracted, filter="data")
    candidates = list(extracted.rglob("lib/server/libjvm.so"))
    assert len(candidates) == 1
    runtime = candidates[0].parent.parent.parent
    release = (runtime / "release").read_text()
    assert 'JAVA_VERSION="25' in release
    # Match the production file set and dereference archive links before adb push.
    staged = work / "staged"
    patterns = re.findall(r'"([^"]+)"', config.split("val embeddedJvmFiles = listOf(", 1)[1].split("\n)", 1)[0])
    for pattern in ["release", "lib/modules"] + patterns:
        for file in runtime.glob(pattern):
            target = staged / file.relative_to(runtime)
            target.parent.mkdir(parents=True, exist_ok=True)
            if file.is_dir():
                shutil.copytree(file, target, dirs_exist_ok=True)
            else:
                shutil.copyfile(file, target)
    runtime = staged
    # System.loadLibrary(jvm_launcher) loads this app dependency before libjvm.
    cpp_runtime = ROOT / "app/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib/x86_64/libc++_shared.so"
    assert cpp_runtime.is_file()
    shutil.copyfile(cpp_runtime, runtime / "lib/libc++_shared.so")
    run(JDK / "bin/javac.exe", "--release", "17", "-d", work,
        ROOT / "app/src/test/fixtures/jvm-image/ImageProbe.java")
    fixture = (ROOT / "app/src/test/fixtures/jvm-image/JvmImageLauncher.cpp").read_text()
    fixture = fixture.replace('{const_cast<char*>("-Djava.awt.headless=true"), nullptr}};',
        '{const_cast<char*>("-Djava.awt.headless=true"), nullptr},'
        '{const_cast<char*>("-XX:+UseCompactObjectHeaders"), nullptr}};')
    fixture = fixture.replace("args.nOptions = 2;", "args.nOptions = 3;")
    fixture = fixture.replace('std::string home = argv[1];', 'std::string home = argv[1]; dlopen((home + "/lib/libc++_shared.so").c_str(), RTLD_NOW | RTLD_GLOBAL);')
    source = work / "Launcher.cpp"
    source.write_text(fixture)
    launcher = work / "hybrid-launcher"
    clang = SDK / "ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
    run(clang, "--target=x86_64-linux-android26", "-static-libstdc++", "-std=c++17",
        source, "-ldl", "-o", launcher)
    installation = (ROOT / "app/src/main/java/app/universal/patch/manager/patcher/runtime/jvm/JvmInstallation.kt").read_text()
    preloads = re.findall(r'"([^"]+)"', installation.split("val PRELOAD = listOf(", 1)[1].split(")", 1)[0])
    host = ROOT / "revanced21-patcher-core/build/shrunk-host/revanced21-patcher-core.jar"
    remote = "/data/local/tmp/upm-hybrid-jvm-" + uuid.uuid4().hex
    device = [ADB, "-s", "emulator-5554"]
    try:
        run(*device, "shell", "mkdir", "-p", remote)
        run(*device, "push", runtime, remote + "/jre")
        run(*device, "push", work / "ImageProbe.class", remote + "/ImageProbe.class")
        run(*device, "push", host, remote + "/host.jar")
        run(*device, "push", launcher, remote + "/launcher")
        run(*device, "shell", "chmod", "755", remote + "/launcher")
        run(*device, "shell", "env", "JAVA_HOME=" + remote + "/jre", "LD_LIBRARY_PATH=",
            remote + "/launcher", remote + "/jre", remote + ":" + remote + "/host.jar", *preloads,
            timeout=120)
        print("PASS: pinned Android JDK 25 starts through JNI with compact headers and headless image support")
    finally:
        resolved = subprocess.check_output(list(map(str, device + ["shell", "readlink", "-f", remote])), text=True).strip()
        if resolved == remote and remote.startswith("/data/local/tmp/upm-hybrid-jvm-"):
            run(*device, "shell", "rm", "-rf", remote)
