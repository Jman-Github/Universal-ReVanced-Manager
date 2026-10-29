/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.universal.patch.manager.patcher.runtime.jvm

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1202
// https://github.com/MorpheApp/morphe-manager/pull/1212
// https://github.com/MorpheApp/morphe-manager/pull/1215

import app.universal.patch.manager.util.managerStorageContext
import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import app.universal.patch.manager.BuildConfig
import app.universal.patch.manager.patcher.runtime.JvmUnavailableException
import app.universal.patch.manager.util.tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

/**
 * The JVM the app ships in its assets for patching, unpacked to storage the first time a run
 * needs it, since the JVM can only be loaded from files.
 *
 * The runtime is unpacked once per runtime the app ships. The patcher host is part of the app's
 * own code and is unpacked once per install of the app. Both ship compressed with xz, which
 * takes a few seconds to undo on a phone.
 */
class JvmInstallation(private val app: Application) {
    /**
     * The ABI of the runtime this device gets, null where the app ships none for it. Matched to
     * the process rather than the device, as a 64 bit device can run the app as a 32 bit one.
     */
    val abi: String? =
        (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS)
            .firstOrNull { it in BuildConfig.EMBEDDED_JVM_ABIS }

    val isAvailable get() = abi != null
    val feature get() = if (Process.is64Bit()) BuildConfig.EMBEDDED_JVM_64_BIT_FEATURE
        else BuildConfig.EMBEDDED_JVM_32_BIT_FEATURE

    private val root = app.managerStorageContext.noBackupFilesDir.resolve(ROOT_DIR)
    private val lock = Mutex()

    /** Warm the runtime image without unpacking an unrelated engine's host JAR. */
    suspend fun prepareRuntime() = lock.withLock {
        withContext(Dispatchers.IO) { runtimeHome() }
    }

    private fun runtimeHome(): File {
        val abi = abi ?: throw JvmUnavailableException("No patcher JVM for ${Build.SUPPORTED_ABIS.joinToString()}")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Could not create $root")
        val home = root.resolve("runtime-$abi-${BuildConfig.EMBEDDED_JVM_ID}")
        if (!home.isDirectory) unpackRuntime(abi, home)
        return home
    }

    /** The runtime and host ready to start, unpacked first if they are not yet. */
    suspend fun install(host: String, plugin: File? = null): Installed = lock.withLock {
        withContext(Dispatchers.IO) {
            val abi = abi ?: throw JvmUnavailableException(
                "No patcher JVM for ${Build.SUPPORTED_ABIS.joinToString()}"
            )

            try {
                val home = runtimeHome()

                // UPM PR APKs can share a version code, so refresh the host on every app update.
                val updatedAt = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
                val pluginId = plugin?.let { file ->
                    java.security.MessageDigest.getInstance("SHA-256").let { digest ->
                        file.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                            }
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                }.orEmpty()
                val hostJar = root.resolve("$host-$pluginId-${BuildConfig.VERSION_CODE}-${BuildConfig.BUILD_ID}-$updatedAt.jar")
                if (!hostJar.isFile) unpackHost(host, hostJar, plugin)

                // Whatever an earlier version of the app unpacked is of no use to this one
                root.listFiles()?.filter {
                    (it.name.startsWith("runtime-") && it != home) ||
                        (it.name.startsWith("$host-") && it != hostJar) ||
                        // Remove the unpacked core under its former name after an app update.
                        (host == "upm-patcher-core" && it.name.startsWith("morphe-patcher-core-"))
                }?.forEach { it.deleteRecursively() }

                Installed(home, hostJar)
            } catch (e: IOException) {
                throw JvmUnavailableException("Could not unpack the patcher JVM", e)
            }
        }
    }

    // Unpacked beside the destination and renamed into place, so a half unpacked runtime left by
    // a process that died midway is never taken for a complete one
    private fun unpackRuntime(abi: String, home: File) {
        val staging = File(home.path + STAGING_SUFFIX).apply { deleteRecursively() }

        ZipInputStream(XZInputStream(app.assets.open("$ASSETS_DIR/$abi.zip.xz").buffered())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val target = staging.resolve(entry.name)
                if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    throw IOException("Runtime entry ${entry.name} points outside of it")
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }

        unpackImage(abi, staging.resolve("lib/modules"))

        if (!staging.renameTo(home)) throw IOException("Could not move the runtime into $home")
        Log.i(tag, "Unpacked the patcher JVM for $abi")
    }

    // Ship the module image once; rebuild ABI-specific differences before publishing the runtime.
    private fun unpackImage(abi: String, image: File) {
        image.parentFile?.mkdirs()
        val delta = "$abi.modules.delta.xz"
        if (app.assets.list(ASSETS_DIR)?.contains(delta) != true) {
            unpackXz("modules.xz", image)
            return
        }
        val base = File(image.path + ".base")
        try {
            unpackXz("modules.xz", base)
            RandomAccessFile(base, "r").use { baseImage ->
                XZInputStream(app.assets.open("$ASSETS_DIR/$delta").buffered()).use { input ->
                    image.outputStream().buffered().use { JvmImageDelta.apply(baseImage, input, it) }
                }
            }
        } finally {
            base.delete()
        }
    }

    private fun unpackXz(asset: String, target: File) {
        XZInputStream(app.assets.open("$ASSETS_DIR/$asset").buffered()).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
    }

    private fun unpackHost(host: String, hostJar: File, plugin: File?) {
        val staging = File(hostJar.path + STAGING_SUFFIX)
        if (plugin != null) {
            java.util.zip.ZipFile(plugin).use { zip ->
                val entry = zip.getEntry("assets/jvm/$host.jar")
                    ?: throw JvmUnavailableException("This plugin has no JVM host; use its bound ART host")
                zip.getInputStream(entry).use { input ->
                    staging.outputStream().use { input.copyTo(it) }
                }
            }
        } else XZInputStream(app.assets.open("$ASSETS_DIR/$host.jar.xz").buffered()).use { input ->
            JvmHostArchive.restore(input, File(app.applicationInfo.sourceDir), staging)
        }
        if (!staging.renameTo(hostJar)) throw IOException("Could not move the patcher host into $hostJar")
    }

    class Installed(val home: File, val hostJar: File) {
        /** The VM the runtime ships, the server one where there is one, as the 32-bit ARM one only has the client one. */
        val libjvm
            get() = VMS.map { home.resolve("lib/$it/libjvm.so") }.firstOrNull { it.isFile }
                ?: throw JvmUnavailableException("The patcher JVM ships no VM")

        /** The libraries of the runtime that others of it link against, dependencies first. */
        val preload get() = PRELOAD.map { home.resolve("lib/$it").path }
    }

    private companion object {
        const val ROOT_DIR = "jvm"
        const val ASSETS_DIR = "jvm"
        const val STAGING_SUFFIX = ".staging"

        val VMS = listOf("server", "client")

        // The app's linker namespace does not search the runtime's directory, so a library naming
        // one of these would not find it unless it is already loaded
        val PRELOAD = listOf(
            "libjava.so", "libnet.so", "libnio.so", "libzip.so",
            // Headless AWT libraries name these dependencies without their extracted path.
            "libawt.so", "libawt_headless.so", "libfreetype.so"
        )
    }
}
