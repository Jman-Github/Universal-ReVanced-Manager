import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*

/** Package the matching JVM host inside the signed runtime plugin. */
@CacheableTask
abstract class PluginJvmHostTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val hostJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val directory = outputDirectory.get().dir("jvm").asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        hostJars.forEach { it.copyTo(directory.resolve(it.name)) }
    }
}
