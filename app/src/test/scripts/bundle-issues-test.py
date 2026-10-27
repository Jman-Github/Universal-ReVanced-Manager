"""Run bundle notification regressions on Android and runtime release-selection tests on the JVM.
Uses cached dependencies and an existing emulator; run before the final assembleRelease.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import tomllib
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[4]
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
SDK = Path(os.environ["ANDROID_HOME"])
caches = [ROOT / ".gradle-user-home/caches/modules-2/files-2.1",
          Path(os.environ.get("GRADLE_RO_DEP_CACHE", r"E:\Android\caches")) / "modules-2/files-2.1"]
def artifact(group, name, version="*"):
    found = [p for cache in caches for p in (cache / group / name).glob(version + "/*/*.jar")]
    if not found:
        raise RuntimeError(f"Missing cached {group}:{name}:{version}")
    return max(found, key=lambda p: tuple(map(int, re.findall(r"\d+", p.parent.parent.name))))
def cp(paths):
    return os.pathsep.join(map(str, paths))
def run(*command, **kwargs):
    return subprocess.run(list(map(str, command)), check=True, timeout=120, **kwargs)
kotlin = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["kotlin"]
compiler = [artifact("org.jetbrains.kotlin", name, kotlin) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), artifact("org.jetbrains", "annotations")]
android = next((SDK / "platforms").glob("android-37*/android.jar"))
resources = ROOT / "app/build/intermediates/compile_and_runtime_r_class_jar/release/processReleaseResources/R.jar"
deps = [android, resources, artifact("org.jetbrains.kotlin", "kotlin-stdlib", kotlin),
        artifact("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm"),
        artifact("junit", "junit", "4.13.2"), artifact("org.hamcrest", "hamcrest-core")]
with tempfile.TemporaryDirectory(prefix="bundle-issues-", dir=ROOT / "build") as directory:
    work = Path(directory)
    # Compile the real deep-link implementation without unrelated announcement models.
    deep = (ROOT / "app/src/main/java/app/urv/manager/util/BundleDeepLink.kt").read_text()
    deep = deep.split("object ManagerUpdateDeepLinkIntent", 1)[0]
    deep = deep.replace("import app.urv.manager.ui.model.navigation.Announcement", "")
    (work / "BundleDeepLink.kt").write_text(deep)
    (work / "MainActivity.kt").write_text("package app.urv.manager\nclass MainActivity : android.app.Activity()")
    sources = [
        ROOT / "app/src/main/java/app/urv/manager/util/BundleUpdateNotificationCleanup.kt",
        ROOT / "app/src/main/java/app/urv/manager/receiver/BundleUpdateNotificationDismissReceiver.kt",
        ROOT / "app/src/test/fixtures/bundle-issues/BundleNotificationProbe.kt",
        ROOT / "app/src/main/java/app/urv/manager/domain/repository/RuntimePluginReleaseSelection.kt",
        ROOT / "app/src/main/java/app/urv/manager/network/dto/GitHubRelease.kt",
        ROOT / "app/src/test/java/app/urv/manager/domain/repository/RuntimePluginReleaseSelectionTest.kt",
        work / "BundleDeepLink.kt", work / "MainActivity.kt"]
    classes = work / "classes"
    run(JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps),
        "-d", classes, *sources)
    run(JAVA, "-cp", cp([classes] + deps), "org.junit.runner.JUnitCore",
        "app.urv.manager.domain.repository.RuntimePluginReleaseSelectionTest")
    probe = work / "probe.jar"
    with zipfile.ZipFile(probe, "w") as archive:
        for file in classes.rglob("*.class"):
            relative = file.relative_to(classes).as_posix()
            if relative.startswith(("app/urv/manager/util/", "app/urv/manager/receiver/", "app/urv/manager/MainActivity")):
                archive.write(file, relative)
    dex = work / "tests.zip"
    run(JAVA, "-cp", artifact("com.android.tools", "r8"), "com.android.tools.r8.D8",
        "--min-api", "26", "--lib", android, "--output", dex, probe, resources, deps[2])
    device = [SDK / "platform-tools/adb.exe", "-s", os.environ.get("ANDROID_SERIAL", "emulator-5554")]
    remote = "/data/local/tmp/urv-bundle-issues-" + uuid.uuid4().hex
    try:
        run(*device, "shell", "mkdir", "-p", remote)
        run(*device, "push", dex, remote + "/tests.zip")
        run(*device, "shell", "env", "CLASSPATH=" + remote + "/tests.zip",
            "app_process", "/system/bin", "app.urv.manager.util.BundleNotificationProbe")
    finally:
        resolved = subprocess.check_output(list(map(str, device + ["shell", "readlink", "-f", remote])), text=True, timeout=15).strip()
        if resolved == remote and remote.startswith("/data/local/tmp/urv-bundle-issues-"):
            run(*device, "shell", "rm", "-rf", remote)
