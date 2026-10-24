"""Check shrunk hosts with SDKs absent from Java's system classpath.

--host-dir contains installed/restored host JARs grouped by worktree name.
Optional bundle arguments exercise the matching engine's real metadata loader.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[4]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--host-dir", type=Path, required=True)
parser.add_argument("--worktree", type=Path, action="append")
parser.add_argument("--morphe-bundle", type=Path)
parser.add_argument("--revanced-bundle", type=Path)
parser.add_argument("--v21-bundle", type=Path)
parser.add_argument("--legacy-bundle", type=Path)
args = parser.parse_args()
java_bin = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin"
suffix = ".exe" if os.name == "nt" else ""
with tempfile.TemporaryDirectory(prefix="urv-host-shrink-") as temporary:
    subprocess.run([str(java_bin / ("javac" + suffix)), "--release", "17", "-d", temporary,
                    str(ROOT / "app/src/test/fixtures/host-shrinking/ShrunkHostCheck.java")], check=True)
    for root in args.worktree or [ROOT]:
        for module, engine, bundle in (
            ("urv-patcher-core", "morphe", args.morphe_bundle),
            ("revanced22-patcher-core", "revanced", args.revanced_bundle),
            ("revanced21-patcher-core", "revanced21", args.v21_bundle),
            ("revanced-legacy-patcher-core", "revanced-legacy", args.legacy_bundle),
        ):
            host = args.host_dir / root.name / (module + ".jar")
            original = root / module / "build/libs" / (module + ".jar")
            with zipfile.ZipFile(original) as source, zipfile.ZipFile(host) as installed:
                names = set(installed.namelist())
                assert any(name.startswith("frameworks/") for name in names), host
                maps = [name for name in source.namelist() if name.endswith(".kotlin_module")]
                assert any(module in name for name in maps), original
                for name in maps:
                    if module in name:
                        assert name in names, (host, name)
                    if name in names:
                        assert source.read(name) == installed.read(name), (host, name)
                for name in source.namelist():
                    if name.startswith("frameworks/") and not name.endswith("/"):
                        assert source.read(name) == installed.read(name), (host, name)
            command = [str(java_bin / ("java" + suffix)), "-cp", temporary,
                       "ShrunkHostCheck", str(host), engine]
            if bundle is not None:
                command.append(str(bundle))
            subprocess.run(command, check=True, timeout=90)
            print("PASS shrunk host: " + root.name + " / " + module, flush=True)
