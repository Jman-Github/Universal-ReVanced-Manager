"""Run memory-policy and disposable-host regressions before assembleRelease."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", ROOT / ".gradle-user-home")) / "caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"


def artifact(group, name, version):
    return next((CACHE / group / name / version).glob("*/*.jar"))


compiler = [artifact("org.jetbrains.kotlin", name, "2.4.10") for name in (
    "kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
             artifact("org.jetbrains", "annotations", "13.0")]
core = ROOT / "upm-patcher-core/build/libs/upm-patcher-core.jar"
sources = list((ROOT / "jvm-host/src/main/kotlin").rglob("*.kt"))
sources += [ROOT / "app/src/main/java/app/universal/patch/manager/patcher/runtime/JvmHeapPolicy.kt",
            ROOT / "app/src/test/fixtures/jvm-memory/MemoryRuntimeChecks.kt"]

with tempfile.TemporaryDirectory(prefix="jvm-memory-test-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    classes = work / "classes"
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-jvm-target", "17", "-classpath", str(core), "-d", str(classes),
        *map(str, sources)], check=True, timeout=90)
    cp = os.pathsep.join(map(str, [classes, core]))
    subprocess.run([str(JAVA), "-cp", cp,
        "app.universal.patch.manager.patcher.host.MemoryRuntimeChecksKt"], check=True, timeout=10)
    for fail in (False, True):
        request = work / "request.json"
        events = work / "events.jsonl"
        request.write_text(json.dumps({"device": {"sdk": 37, "fingerprint": "fixture",
            "abis": ["arm64-v8a"], "locales": ["en-US"], "density": 420}, "fail": fail}))
        subprocess.run([str(JAVA), "-Xmx128m", "-cp", cp,
            "app.universal.patch.manager.patcher.host.HostMainKt", str(request), str(events),
            "app.universal.patch.manager.patcher.host.MemoryRuntimeFixtureEntry"], check=True, timeout=15)
        messages = [json.loads(line) for line in events.read_text().splitlines()]
        assert messages[0]["type"] == "started"
        assert messages[-1]["type"] == "finished"
        finished = [m for m in messages if m["type"] == "finished"]
        assert len(finished) == 1
        error = finished[0].get("error")
        assert ("OutOfMemoryError" in error) if fail else error is None, error
        print("PASS: disposable worker exits with one terminal event, fail=" + str(fail))
