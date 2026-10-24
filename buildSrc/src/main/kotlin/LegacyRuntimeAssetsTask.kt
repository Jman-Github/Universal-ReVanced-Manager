import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** The v21 plugin carries an isolated legacy SDK for both ART and the shared JVM. */
@CacheableTask
abstract class LegacyRuntimeAssetsTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val hostJars: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val androidJar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val d8Jar: RegularFileProperty
    @get:Nested abstract val jdk: Property<JavaLauncher>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction fun prepare() {
        val host = hostJars.singleFile
        val dex = temporaryDir.resolve("legacy-dex.jar")
        dex.delete()
        exec.exec {
            commandLine(jdk.get().executablePath.asFile.path, "-Xmx2g", "-cp", d8Jar.get().asFile.path,
                "com.android.tools.r8.D8", "--release", "--min-api", "26",
                "--lib", androidJar.get().asFile.path, "--output", dex.path, host.path)
        }
        val output = outputDirectory.get().file("runtimes/revanced-legacy-runtime.jar").asFile
        output.parentFile.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            ZipFile(dex).use { input ->
                input.entries().asSequence().sortedBy { it.name }.forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    input.getInputStream(entry).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            // D8 emits code only. Apktool and the other libraries also need their data resources on ART.
            ZipFile(host).use { input ->
                input.entries().asSequence().filter {
                    !it.isDirectory && !it.name.endsWith(".class") && !it.name.endsWith(".dex") &&
                        it.name != "META-INF/MANIFEST.MF"
                }.sortedBy { it.name }.forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    input.getInputStream(entry).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry("assets/jvm/" + host.name).apply { time = 0 })
            host.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}
