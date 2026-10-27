"""Run the updater identity, migration countdown, and PR compatibility regressions before assembleRelease."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[4]
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
caches = [ROOT / ".gradle-user-home/caches/modules-2/files-2.1",
          Path(os.environ.get("GRADLE_RO_DEP_CACHE", r"E:\Android\caches")) / "modules-2/files-2.1"]
def artifact(group, name, version="*"):
    found = [p for cache in caches for p in (cache / group / name).glob(version + "/*/*.jar")]
    if not found:
        raise RuntimeError(f"Missing cached {group}:{name}:{version}")
    return max(found, key=lambda p: tuple(map(int, re.findall(r"\d+", p.parent.parent.name))))
def cp(paths):
    return os.pathsep.join(map(str, paths))
kotlin = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["kotlin"]
compiler = [artifact("org.jetbrains.kotlin", name, kotlin) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), artifact("org.jetbrains", "annotations")]
deps = [artifact("org.jetbrains.kotlin", name, kotlin) for name in ("kotlin-stdlib", "kotlin-test", "kotlin-test-junit")]
deps += [artifact("junit", "junit", "4.13.2"), artifact("org.hamcrest", "hamcrest-core")]
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="manager-update-rebrand-", dir=ROOT / "build") as directory:
    classes = Path(directory) / "classes"
    names = ["ManagerUpdateIdentity", "ManagerUpdateCompatibility"]
    sources = [ROOT / f"app/src/main/java/app/urv/manager/util/{name}.kt" for name in names]
    sources += [ROOT / f"app/src/test/java/app/urv/manager/util/{name}Test.kt" for name in names]
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(deps), "-d", classes, *sources])),
        check=True, timeout=120)
    subprocess.run(list(map(str, [JAVA, "-cp", cp([classes] + deps), "org.junit.runner.JUnitCore",
        *[f"app.urv.manager.util.{name}Test" for name in names]])), check=True, timeout=120)
