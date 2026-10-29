/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.universal.patch.manager.patcher.runtime.jvm

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1202
// https://github.com/MorpheApp/morphe-manager/pull/1212

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Launch data for a disposable embedded JVM or ART worker in [PatcherService].
 *
 * @param preload Libraries of the runtime that others of it link against, dependencies first.
 * @param mainClass The class whose main runs, in JNI form such as `a/b/MainKt`.
 */
@Parcelize
data class JvmLaunch(
    val libjvm: String,
    val preload: List<String>,
    val options: List<String>,
    val mainClass: String,
    val entryClass: String,
    val runtimeClassPath: String? = null,
    val nativeLibraryDir: String? = null
) : Parcelable
