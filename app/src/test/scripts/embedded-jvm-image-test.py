"""Test packaged headless image support on an existing Android emulator before assembleRelease.
Uses cached JRE build intermediates; invokes no Gradle tasks and modifies no installed apps.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import uuid

root = Path(__file__).resolve().parents[4]
jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17"))
sdk = Path(os.environ["ANDROID_HOME"])
adb = sdk / "platform-tools/adb.exe"
source = root / "app/build/tmp/prepareEmbeddedJvmX8664"
config = (root / "app/build.gradle.kts").read_text()
def strings(name):
    block = config.split("val " + name + " = listOf(", 1)[1].split("\n)", 1)[0]
    return re.findall(r'"([^"]+)"', block)
def run(*args, **kw):
    return subprocess.run([str(a) for a in args], check=True, **kw)
with tempfile.TemporaryDirectory(prefix="jvm-image-", dir=root / "build") as directory:
    work = Path(directory)
    linked = work / "linked"
    run(jdk / "bin/jlink.exe", "--module-path", source / "jmods",
        "--add-modules", ",".join(strings("embeddedJvmModules")),
        "--disable-plugin", "generate-jli-classes", "--strip-debug", "--no-header-files",
        "--no-man-pages", "--output", linked)
    for pattern in strings("embeddedJvmFiles"):
        for file in (source / "build").glob(pattern):
            target = linked / file.relative_to(source / "build")
            target.parent.mkdir(parents=True, exist_ok=True)
            if file.is_dir():
                shutil.copytree(file, target, dirs_exist_ok=True)
            else:
                shutil.copyfile(file, target)
    run(jdk / "bin/javac.exe", "-d", work, root / "app/src/test/fixtures/jvm-image/ImageProbe.java")
    ndk = sdk / "ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64"
    launcher = work / "image-launcher"
    run(ndk / "bin/clang++.exe", "--target=x86_64-linux-android26",
        "-static-libstdc++", "-std=c++17",
        root / "app/src/test/fixtures/jvm-image/JvmImageLauncher.cpp", "-ldl", "-o", launcher)
    installation = (root / "app/src/main/java/app/universal/patch/manager/patcher/runtime/jvm/JvmInstallation.kt").read_text()
    preloads = re.findall(r'"([^"]+)"', installation.split("val PRELOAD = listOf(", 1)[1].split(")", 1)[0])
    host = root / "revanced21-patcher-core/build/shrunk-host/revanced21-patcher-core.jar"
    if not host.is_file():
        raise RuntimeError("Assemble the v21 runtime once to supply the Apktool classpath")
    shutil.copyfile(host, work / "host.jar")
    remote = "/data/local/tmp/upm-jvm-image-" + uuid.uuid4().hex
    device = [adb, "-s", "emulator-5554"]
    try:
        run(*device, "shell", "mkdir", "-p", remote)
        run(*device, "push", linked, remote + "/jre")
        run(*device, "push", work / "ImageProbe.class", remote + "/ImageProbe.class")
        run(*device, "push", work / "host.jar", remote + "/host.jar")
        run(*device, "push", launcher, remote + "/image-launcher")
        run(*device, "shell", "chmod", "755", remote + "/image-launcher")
        command = device + ["shell", "env", "JAVA_HOME=" + remote + "/jre", "LD_LIBRARY_PATH=",
                            remote + "/image-launcher", remote + "/jre", remote + ":" + remote + "/host.jar"]
        baseline = subprocess.run([str(a) for a in command + preloads[:4]], capture_output=True, text=True, timeout=90)
        if baseline.returncode == 0 or "libfreetype.so" not in baseline.stderr:
            raise AssertionError("Expected missing freetype dependency without image preloads:\n" + baseline.stdout + baseline.stderr)
        print("PASS: reproduced missing freetype dependency with original app preload list")
        run(*command, *preloads, timeout=90)
    finally:
        resolved = subprocess.check_output([str(a) for a in device + ["shell", "readlink", "-f", remote]], text=True).strip()
        if resolved == remote and remote.startswith("/data/local/tmp/upm-jvm-image-"):
            run(*device, "shell", "rm", "-rf", remote)
