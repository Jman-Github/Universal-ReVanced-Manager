"""Check that all runtime copies preserve ZIP errors instead of reporting absent APK files."""
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
fixture = """
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.zip.*
fun main(args: Array<String>) {
    val type = Class.forName("app.universal.patch.manager.patcher.split.SplitApkPreparer")
    val validate = type.getDeclaredMethod("validatePreparedApk", File::class.java).apply { isAccessible = true }
    val instance = type.getField("INSTANCE").get(null)
    val corrupt = File(args[0], "truncated.apk").apply { writeText("PK truncated archive") }
    try {
        validate.invoke(instance, corrupt)
        error("An unreadable ZIP was treated as a readable APK with absent entries")
    } catch (error: InvocationTargetException) {
        val cause = error.targetException
        check(cause is IOException && cause.cause is ZipException)
        check(cause.message!!.contains("Could not read merged APK"))
        check(cause.message!!.contains("MB free"))
    }
    val valid = File(args[0], "valid.apk")
    ZipOutputStream(valid.outputStream()).use { zip ->
        for (name in listOf("AndroidManifest.xml", "resources.arsc")) {
            zip.putNextEntry(ZipEntry(name)); zip.write(byteArrayOf(1)); zip.closeEntry()
        }
    }
    val result = validate.invoke(instance, valid)
    val usable = result.javaClass.getDeclaredMethod("isUsable").apply { isAccessible = true }
    check(usable.invoke(result) == true)
    println("PASS: unreadable ZIP cause retained; valid APK accepted")
}
"""
with tempfile.TemporaryDirectory(prefix="merged-apk-test-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    main = work / "Main.kt"
    main.write_text(fixture)
    for name, module in (("app", "revanced22-patcher-core"),
                         ("morphe-runtime", "upm-patcher-core"),
                         ("shared/runtime-plugin", "revanced21-patcher-core")):
        core = ROOT / module / "build/libs" / (module + ".jar")
        source = ROOT / name / "src/main/java/app/universal/patch/manager/patcher/split/SplitApkPreparer.kt"
        classes = work / name.replace("/", "-")
        subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, compiler)),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-jvm-target", "17", "-Xfriend-paths=" + str(core), "-classpath", str(core),
            "-d", str(classes), str(source), str(main)], check=True, timeout=90)
        subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, [classes, core])),
                        "MainKt", str(work)], check=True, timeout=10)
