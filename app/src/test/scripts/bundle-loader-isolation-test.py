"""Exercise production bundle loaders with SDKs absent from Java's system classpath.

Requires JDK 17, cached Kotlin compiler dependencies and the assembled core JARs.
Run before the final release build; current loader sources are compiled into overrides.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[4]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--gradle-user-home", type=Path, default=Path(os.environ.get("GRADLE_USER_HOME", ROOT / ".gradle-user-home")))
parser.add_argument("--worktree", type=Path, action="append", help="Worktree to check; defaults to this checkout")
parser.add_argument("--core-dir", type=Path, help="Prepared host directory, grouped by worktree name")
args = parser.parse_args()
CACHE = args.gradle_user_home / "caches/modules-2/files-2.1"
JAVA_BIN = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin"
JAVA = JAVA_BIN / ("java.exe" if os.name == "nt" else "java")
JAVAC = JAVA_BIN / ("javac.exe" if os.name == "nt" else "javac")


def artifact(group, name, version="*"):
    jars = sorted((CACHE / group / name).glob(version + "/*/*.jar"))
    if not jars:
        raise RuntimeError(f"Missing cached dependency: {group}:{name}:{version}")
    return jars[-1]


def classpath(paths):
    return os.pathsep.join(map(str, paths))


compiler = [artifact("org.jetbrains.kotlin", name, "2.4.10") for name in (
    "kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect",
    "kotlin-script-runtime", "kotlin-daemon-embeddable")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), artifact("org.jetbrains", "annotations")]


def compile_loader(source, core, output):
    subprocess.run([
        str(JAVA), "-cp", classpath(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17",
        "-Xskip-metadata-version-check", "-Xskip-prerelease-check",
        "-classpath", str(core), "-d", str(output), str(source),
    ], check=True, timeout=90)


def fixture_source(sdk, morphe):
    patch = "Patch<?>" if morphe else "Patch"
    return f"""package fixture;
import {sdk}.*;
import kotlin.Unit;
public class Fixture {{
    private static final {patch} dependency = PatchKt.resourcePatch(null, null, false, b -> Unit.INSTANCE);
    public static final {patch} field = patch("Field fixture");
    public static {patch} getSample() {{ return patch("Getter fixture"); }}
    private static {patch} patch(String name) {{
        if ("true".equals(System.getProperty("upm.fixture.failOnce"))) {{
            System.clearProperty("upm.fixture.failOnce");
            throw new IllegalStateException("fixture transient preload failure");
        }}
        System.getProperties().put("upm.fixture.bundleLoader", Fixture.class.getClassLoader());
        return PatchKt.resourcePatch(name, null, Boolean.getBoolean("upm.fixture.strict"), builder -> {{
            builder.dependsOn(dependency);
            return Unit.INSTANCE;
        }});
    }}
    public static String readLate() throws Exception {{
        try (var stream = Fixture.class.getResourceAsStream("/fixture/payload.txt")) {{
            if (stream == null) throw new AssertionError("Missing lazy bundle resource");
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }}
    }}
}}
"""


with tempfile.TemporaryDirectory(prefix="upm-bundle-isolation-", dir=args.gradle_user_home) as temporary:
    work = Path(temporary)
    harness = ROOT / "app/src/test/fixtures/bundle-loader/IsolatedBundles.java"
    subprocess.run([str(JAVAC), "--release", "17", "-d", str(work), str(harness)], check=True, timeout=30)
    for root in args.worktree or [ROOT]:
        for name, module, source, loader, sdk in (
            ("morphe", "upm-patcher-core",
             "morphe-runtime/src/main/java/app/universal/patch/manager/patcher/morphe/MorphePatchBundleLoader.kt",
             "app.universal.patch.manager.patcher.morphe.MorphePatchBundleLoader", "app.morphe.patcher.patch"),
            ("revanced", "revanced22-patcher-core",
             "shared/revanced22/src/main/java/app/universal/patch/manager/patcher/revanced/RevancedPatchBundleLoader.kt",
             "app.universal.patch.manager.patcher.revanced.RevancedPatchBundleLoader", "app.revanced.patcher.patch"),
        ):
            core = (args.core_dir / root.name / f"{module}.jar") if args.core_dir else root / module / "build/libs" / f"{module}.jar"
            if not core.is_file():
                raise RuntimeError(f"Missing assembled core: {core}")
            current = work / root.name / name
            current.mkdir(parents=True)
            fixture = current / "Fixture.java"
            fixture.write_text(fixture_source(sdk, name == "morphe"), encoding="utf-8")
            subprocess.run([str(JAVAC), "--release", "17", "-cp", str(core), "-d", str(current), str(fixture)],
                           check=True, timeout=30)
            bundle = current / "patches.jar"
            with zipfile.ZipFile(bundle, "w") as jar:
                jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nName: Isolation fixture\r\nPatcher-Version: 2.0.0\r\n\r\n")
                for file in (current / "fixture").glob("*.class"):
                    jar.write(file, "fixture/" + file.name)
                jar.writestr("fixture/payload.txt", "late resource")
                jar.writestr("META-INF/versions/99/broken/Invalid.class", b"not a class")
            broken = current / "broken.jar"
            with zipfile.ZipFile(broken, "w") as jar:
                jar.writestr("broken/Invalid.class", b"not a class")
            output = current / "overrides"
            compile_loader(root / source, core, output)
            print(f"Checking {root.name}/{name}", flush=True)
            # Only the harness is on -cp. The SDK must be reached through the isolated engine loader.
            subprocess.run([str(JAVA), "-cp", str(work), "IsolatedBundles",
                            str(output), str(core), loader, sdk + ".Patch", str(bundle), str(broken)],
                           check=True, timeout=45)
