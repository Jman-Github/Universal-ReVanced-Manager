/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.runtime.jvm

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1223

/** One HotSpot VM lives alongside ART until the app process ends. */
internal object EmbeddedJvm {
    init { System.loadLibrary("jvm_launcher") }

    fun start(launch: JvmLaunch, args: List<String>) = startJvm(
        launch.libjvm, launch.preload.toTypedArray(), launch.options.toTypedArray(),
        launch.mainClass, args.toTypedArray()
    )

    external fun largestReservationMb(limitMb: Int): Int

    private external fun startJvm(
        libjvm: String, preload: Array<String>, options: Array<String>,
        mainClass: String, args: Array<String>
    ): Int
}
