package app.universal.patch.manager.patcher.reseam

import java.io.File

/** JNI ownership lasts as long as its loader. Retain owners even when the active plugin changes. */
internal class ReseamRuntimeLoaderCache<T : Any> {
    private val loaders = mutableMapOf<String, T>()

    @Synchronized
    fun getOrCreate(nativeLibraryDirectory: File, create: () -> T): T =
        loaders.getOrPut(nativeLibraryDirectory.canonicalPath, create)
}
