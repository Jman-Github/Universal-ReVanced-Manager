"""Run root install-write and transaction regressions without Gradle, before assembleRelease.
With --device SERIAL, also verify Android pipe transport and write/abandon real PackageInstaller sessions.
No package install is committed.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile
import tomllib
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--device")
args = parser.parse_args()
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
def run(command, **kwargs):
    return subprocess.run(list(map(str, command)), check=True, timeout=120, **kwargs)
def quote(value):
    return "'" + str(value).replace("'", "'\\''") + "'"
kotlin = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["kotlin"]
compiler = [artifact("org.jetbrains.kotlin", name, kotlin) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), artifact("org.jetbrains", "annotations")]
deps = [artifact("org.jetbrains.kotlin", name, kotlin) for name in ("kotlin-stdlib", "kotlin-test", "kotlin-test-junit")]
deps += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
         artifact("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm"),
         artifact("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm"),
         artifact("junit", "junit", "4.13.2"), artifact("org.hamcrest", "hamcrest-core")]
base = ROOT / "app/src/main/java/app/universal/patch/manager/domain/installer/root"
tests = ROOT / "app/src/test/java/app/universal/patch/manager/domain/installer/root"
main_names = ["RootInstallCommands", "RootPaths", "RootMountModels", "RootMountBoundaries",
              "RootSplitApks", "RootMountPolicy", "RootMountTransactionCoordinator", "MountTableReader"]
test_names = ["RootInstallCommandsTest", "RootSplitApksTest", "RootMountPolicyTest", "RootMountTransactionCoordinatorTest"]
with tempfile.TemporaryDirectory(prefix="root-install-write-", dir=ROOT / "build") as directory:
    work = Path(directory)
    classes = work / "classes"
    sources = [base / (name + ".kt") for name in main_names]
    sources += [tests / (name + ".kt") for name in test_names]
    sources += [ROOT / "app/src/test/fixtures/root-install/RootInstallCommandEmitter.kt"]
    run([JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
         "-jvm-target", "17", "-Xplugin=" + str(artifact("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", kotlin)),
         "-classpath", cp(deps), "-d", classes, *sources])
    run([JAVA, "-cp", cp([classes] + deps), "org.junit.runner.JUnitCore",
         *["app.universal.patch.manager.domain.installer.root." + name for name in test_names]])
    if args.device:
        device = [Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb.exe", "-s", args.device]
        def shell(command, **kwargs):
            return run(device + ["shell", command], capture_output=True, text=True, **kwargs)
        remote = "/data/local/tmp/upm-root-write-" + uuid.uuid4().hex
        payload = work / "payload.apk"
        payload.write_bytes(bytes(i * 31 % 256 for i in range(8193)))
        path = remote + "/stock ' quoted.apk"
        def command(backend, session="42", source=path):
            return subprocess.check_output(list(map(str, [JAVA, "-cp", cp([classes] + deps),
                "app.universal.patch.manager.domain.installer.root.RootInstallCommandEmitter",
                backend, session, "0.apk", source, payload.stat().st_size])), text=True, timeout=15).strip()
        try:
            shell("mkdir -p " + quote(remote))
            run(device + ["push", payload, path])
            stub = """
pm() {
    [ "$1" = install-write ] && [ "$2" = -S ] && [ "$6" = - ] || return 8
    [ -p /proc/self/fd/0 ] || { echo 'rejected regular input descriptor' >&2; return 2; }
    cat > CAPTURE
}
cmd() { [ "$1" = package ] || return 9; shift; pm "$@"; }
""".replace("CAPTURE", quote(remote + "/received.apk"))
            for backend in ("pm", "cmd package"):
                shell(stub + command(backend) + "; result=$?; [ $result = 0 ] || exit $result; cmp " +
                      quote(path) + " " + quote(remote + "/received.apk"))
            failed = subprocess.run(list(map(str, device + ["shell",
                "pm() { cat > /dev/null; return 0; }; " + command("pm", source=remote + "/missing.apk")])),
                capture_output=True, text=True, timeout=15)
            assert failed.returncode != 0, "Android must propagate the APK reader failure"
            print("PASS: Android shell passes a pipe, streams exact bytes and propagates read failures")
            for backend in ("pm", "cmd package"):
                created = shell(backend + " install-create -r --user 0 -S " + str(payload.stat().st_size))
                match = re.search(r"\[(\d+)]", created.stdout)
                assert match, created.stdout + created.stderr
                session = match.group(1)
                try:
                    result = shell(command(backend, session))
                    assert "Success" in result.stdout, result.stdout + result.stderr
                    print("PASS: real Android " + backend + " session accepted streamed bytes")
                finally:
                    shell(backend + " install-abandon " + session)
        finally:
            resolved = shell("readlink -f " + quote(remote)).stdout.strip()
            if resolved == remote and remote.startswith("/data/local/tmp/upm-root-write-"):
                shell("rm -rf " + quote(remote))
