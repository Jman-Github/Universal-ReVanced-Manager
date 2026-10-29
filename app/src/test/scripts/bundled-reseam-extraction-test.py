"""Exercise the real bundled Reseam extractor with overlapping first-use requests.

Uses JVM Android stubs for file publication only; does not claim device/JNI coverage.
Run before assembleRelease. No Gradle tasks are invoked.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[4]
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"
CACHE = ROOT / ".gradle-user-home/caches/modules-2/files-2.1"
def artifact(group, name, version="*"):
    found = list((CACHE / group / name).glob(version + "/*/*.jar"))
    return max(found, key=lambda p: tuple(map(int, re.findall(r"\d+", p.parent.parent.name))))
def cp(paths):
    return os.pathsep.join(map(str, paths))
kotlin = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["kotlin"]
stdlib = artifact("org.jetbrains.kotlin", "kotlin-stdlib", kotlin)
compiler = [artifact("org.jetbrains.kotlin", name, kotlin) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"), artifact("org.jetbrains", "annotations")]
fixtures = {
"Context.kt": """
package android.content
import java.io.File
import android.content.res.AssetManager
class Context(val assets: AssetManager, val codeCacheDir: File) {
    val applicationContext: Context? get() = this
    val packageName = "test"
    val packageManager = PackageManager()
}
class PackageManager {
    fun getPackageInfo(name: String, flags: Int) = PackageInfo()
}
class PackageInfo { val lastUpdateTime = 0L }
""",
"Assets.kt": """
package android.content.res
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
class AssetManager(val bytes: ByteArray, val started: CountDownLatch, val release: CountDownLatch) {
    private val readers = AtomicInteger()
    fun open(name: String): InputStream = object : ByteArrayInputStream(bytes) {
        private var gated = false
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!gated) {
                gated = true
                if (readers.incrementAndGet() == 1) {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            return super.read(buffer, offset, length)
        }
    }
}
""",
"Build.kt": """
package android.os
object Build {
    object VERSION { const val SDK_INT = 36 }
    object VERSION_CODES { const val LOLLIPOP = 21 }
}
""",
"Os.kt": """
package android.system
object Os { fun chmod(path: String, mode: Int) {} }
""",
"Storage.kt": """
package app.universal.patch.manager.util
val android.content.Context.managerStorageContext get() = this
""",
"BuildConfig.kt": """
package app.universal.patch.manager
object BuildConfig { const val VERSION_CODE = 1; const val BUILD_ID = 0L }
""",
"Probe.kt": """
import android.content.Context
import android.content.res.AssetManager
import app.universal.patch.manager.patcher.runtime.reseam.BundledReseamRuntimeAssets
import java.io.File
import java.util.concurrent.*
fun main(args: Array<String>) {
    val bytes = ByteArray(262144) { (it % 251).toByte() }
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val context = Context(AssetManager(bytes, started, release), File(args[0]))
    val executor = Executors.newFixedThreadPool(2)
    try {
        val first = executor.submit<File> { BundledReseamRuntimeAssets.ensureRuntimeApk(context) }
        check(started.await(10, TimeUnit.SECONDS))
        val second = executor.submit<File> { BundledReseamRuntimeAssets.ensureRuntimeApk(context) }
        // Let an unprotected second caller finish while the first copy is paused.
        // A serialized caller waits here until the first copy is released.
        try { second.get(500, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) {}
        finally { release.countDown() }
        val results = listOf(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
        check(results[0] == results[1])
        check(results.all { it.readBytes().contentEquals(bytes) })
        check(BundledReseamRuntimeAssets.ensureRuntimeApk(context) == results[0])
        println("PASS: concurrent first-use requests publish one intact bundled Reseam runtime")
    } finally {
        release.countDown()
        executor.shutdownNow()
        context.codeCacheDir.walkBottomUp().forEach { it.setWritable(true, true); it.delete() }
    }
}
"""
}
with tempfile.TemporaryDirectory(prefix="bundled-reseam-test-", dir=ROOT / "build") as directory:
    work = Path(directory)
    sources = []
    for name, content in fixtures.items():
        path = work / name
        path.write_text(content)
        sources.append(path)
    sources.append(ROOT / "app/src/main/java/app/universal/patch/manager/patcher/runtime/reseam/BundledReseamRuntimeAssets.kt")
    classes = work / "classes"
    subprocess.run(list(map(str, [JAVA, "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", str(stdlib), "-d", classes, *sources])),
        check=True, timeout=120)
    subprocess.run(list(map(str, [JAVA, "-cp", cp([classes, stdlib]), "ProbeKt", work / "cache"])),
                   check=True, timeout=30)
