"""Run the v21 resource validator, write-workflow failures and real progress tracker without Gradle.
Run before final assembleRelease. Requires a previously built v21 host and cached compiler/test dependencies.
"""
import argparse
import os
import uuid
from pathlib import Path
import re
import subprocess
import tempfile
import tomllib
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--emulator", help="Also run resource tests through Android ART on this connected device")
args = parser.parse_args()
ROOT = Path(__file__).resolve().parents[4]
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
caches = [ROOT / ".gradle-user-home/caches/modules-2/files-2.1"]
if os.environ.get("GRADLE_RO_DEP_CACHE"):
    caches += [Path(os.environ["GRADLE_RO_DEP_CACHE"]) / "modules-2/files-2.1"]
def artifact(group, name, extension="jar", version="*"):
    matches = [file for cache in caches for file in (cache / group / name).glob(version + "/*/*." + extension)]
    if not matches:
        raise RuntimeError(f"Missing cached dependency: {group}:{name}:{version}")
    return max(matches, key=lambda file: tuple(map(int, re.findall(r"\d+", file.parent.parent.name))))
def cp(files):
    return os.pathsep.join(map(str, files))
kotlin = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["kotlin"]
compiler = [artifact("org.jetbrains.kotlin", name, version=kotlin) for name in
    ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), artifact("org.jetbrains", "annotations")]
core = ROOT / "revanced21-patcher-core/build/libs/revanced21-patcher-core.jar"
if not core.is_file():
    raise RuntimeError("Assemble the v21 runtime once before running these focused tests")
with tempfile.TemporaryDirectory(prefix="v21-resource-write-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    compose = work / "compose.jar"
    with zipfile.ZipFile(artifact("androidx.compose.runtime", "runtime-android", "aar")) as archive:
        compose.write_bytes(archive.read("classes.jar"))
    android = next((Path(os.environ["ANDROID_HOME"]) / "platforms").glob("android-37*/android.jar"))
    dependencies = [core, compose, android, artifact("androidx.collection", "collection-jvm"),
                    artifact("org.jetbrains.kotlin", "kotlin-stdlib", version=kotlin),
                    artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
                    artifact("junit", "junit", version="4.13.2"), artifact("org.hamcrest", "hamcrest-core")]
    models = (ROOT / "app/src/main/java/app/universal/patch/manager/ui/model/PatcherStep.kt").read_text()
    models = re.sub(r"import (android.os.Parcelable|androidx.annotation.StringRes|app.universal.patch.manager.R|kotlinx.parcelize.Parcelize)\n", "", models)
    models = re.sub(r"enum class StepCategory\([^}]+}", "enum class StepCategory { PREPARING, PATCHING, SAVING }", models)
    models = models.replace("@Parcelize\n", "").replace(" : Parcelable", "")
    (work / "PatcherStep.kt").write_text(models)
    sources = [
        ROOT / "revanced.v21-runtime-plugin/src/main/java/app/universal/patch/manager/patcher/Session.kt",
        ROOT / "revanced.v21-runtime-plugin/src/main/java/app/universal/patch/manager/patcher/util/DecodedResourceReferences.kt",
        ROOT / "revanced21-patcher-core/src/test/kotlin/app/universal/patch/manager/patcher/util/DecodedResourceReferencesTest.kt",
        ROOT / "revanced21-patcher-core/src/test/kotlin/app/universal/patch/manager/patcher/SessionWriteProgressTest.kt",
        ROOT / "app/src/main/java/app/universal/patch/manager/ui/model/PatcherProgressTracker.kt",
        ROOT / "app/src/main/java/app/universal/patch/manager/ui/model/ExpandableStepProgress.kt",
        ROOT / "app/src/test/java/app/universal/patch/manager/ui/model/PatcherProgressTrackerTest.kt",
        work / "PatcherStep.kt"]
    classes = work / "classes"
    subprocess.run([str(JAVA), "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-Xfriend-paths=" + str(core),
        "-classpath", cp(dependencies), "-d", str(classes), *map(str, sources)], check=True, timeout=120)
    subprocess.run([str(JAVA), "-cp", cp([classes] + dependencies), "org.junit.runner.JUnitCore",
        "app.universal.patch.manager.patcher.util.DecodedResourceReferencesTest",
        "app.universal.patch.manager.patcher.SessionWriteProgressTest",
        "app.universal.patch.manager.ui.model.PatcherProgressTrackerTest"], check=True, timeout=90)

    if args.emulator:
        resource_tests = work / "resource-tests.jar"
        with zipfile.ZipFile(resource_tests, "w") as archive:
            for file in (classes / "app/universal/patch/manager/patcher/util").glob("DecodedResource*.class"):
                archive.write(file, file.relative_to(classes).as_posix())
        dex = work / "resource-tests.zip"
        subprocess.run([str(JAVA), "-cp", str(artifact("com.android.tools", "r8")),
            "com.android.tools.r8.D8", "--min-api", "26", "--lib", str(android),
            "--output", str(dex), str(resource_tests),
            str(artifact("org.jetbrains.kotlin", "kotlin-stdlib", version=kotlin)),
            str(artifact("junit", "junit", version="4.13.2")), str(artifact("org.hamcrest", "hamcrest-core")),
            str(artifact("xpp3", "xpp3"))], check=True, timeout=120)
        adb = Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb.exe"
        remote = "/data/local/tmp/upm-resource-tests-" + uuid.uuid4().hex
        device = [str(adb), "-s", args.emulator]
        try:
            subprocess.run(device + ["shell", "mkdir", "-p", remote], check=True, timeout=15)
            subprocess.run(device + ["push", str(dex), remote + "/tests.zip"], check=True, timeout=30)
            subprocess.run(device + ["shell", "env", "CLASSPATH=" + remote + "/tests.zip",
                "app_process", "-Djava.io.tmpdir=" + remote, "/system/bin", "org.junit.runner.JUnitCore",
                "app.universal.patch.manager.patcher.util.DecodedResourceReferencesTest"], check=True, timeout=90)
        finally:
            resolved = subprocess.check_output(device + ["shell", "readlink", "-f", remote], text=True, timeout=15).strip()
            if resolved == remote and remote.startswith("/data/local/tmp/upm-resource-tests-"):
                subprocess.run(device + ["shell", "rm", "-rf", remote], check=True, timeout=15)
