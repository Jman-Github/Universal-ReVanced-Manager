package app.urv.manager.reseam.runtime

import app.reseam.sdk.InspectRequest
import app.reseam.sdk.InspectResponse
import app.reseam.sdk.PatchOutput
import app.reseam.sdk.PatchPreset
import app.reseam.sdk.PatchRequest
import app.reseam.sdk.PatchSelection
import app.reseam.sdk.PatchSpec
import app.reseam.sdk.Problem
import app.reseam.sdk.ReseamAndroidHost
import app.reseam.sdk.SdkError
import app.reseam.sdk.Trust
import app.reseam.sdk.inspect
import app.reseam.sdk.patch
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.RemoteError
import app.urv.manager.patcher.StepId
import app.urv.manager.patcher.logger.LogLevel
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.runStep
import app.urv.manager.patcher.toSafeStackTraceString
import app.urv.manager.patcher.split.SplitApkPreparer
import app.urv.manager.patcher.split.SplitApkFiles
import app.urv.manager.patcher.util.NativeLibStripper
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

// SDK integration follows Reseam Manager's host-owned trust and Android loader setup.
// https://git.reseam.app/reseam/manager/src/commit/3cab4b1fad/composeApp/src/androidMain/kotlin/app/reseam/manager/ReseamApplication.kt
// https://git.reseam.app/reseam/reseam/src/tag/v0.18.3/sdk/README.md
object ReseamRuntimeEntry {
    @JvmStatic
    fun engineVersion(): String = app.universal.revanced.manager.reseam.runtime.BuildConfig.RESEAM_SDK_VERSION

    @JvmStatic
    @Synchronized
    fun loadMetadata(bundlePaths: List<String>): Map<String, List<Map<String, Any?>>> =
        bundlePaths.associateWith(::loadMetadataForBundle)

    @JvmStatic
    @Synchronized
    fun loadMetadataForBundle(bundlePath: String): List<Map<String, Any?>> =
        inspectBundle(bundlePath).patches.map { it.spec }.filterNot { it.hidden }.map(ReseamMetadata::toMap)

    @JvmStatic
    @Synchronized
    fun loadBundleName(bundlePath: String): String = inspectBundle(bundlePath).bundles.single().name

    private fun inspectBundle(path: String): InspectResponse {
        ReseamAndroidHost.setClassLoader(ReseamRuntimeEntry::class.java.classLoader!!)
        val response = inspect(
            InspectRequest(splitPaths = emptyList(), bundlePaths = listOf(path), trust = Trust(emptyList()))
        )
        val bundle = response.bundles.single()
        bundle.problem?.let { throw IllegalArgumentException(reseamBundleProblemMessage(it)) }
        require(bundle.publicKey.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid Reseam signer key." }
        // Importing a bundle approves its source, as with other URV bundle formats.
        // Pin its signer on first catalog load so automatic updates cannot silently change it.
        val signerFile = requireNotNull(File(path).absoluteFile.parentFile).resolve("reseam-signer")
        if (signerFile.exists()) {
            require(signerFile.readText().trim().equals(bundle.publicKey, ignoreCase = true)) {
                "Reseam bundle signer changed. Remove and import the bundle again if you trust its new signer."
            }
        } else signerFile.writeText(bundle.publicKey)
        return response
    }

    @JvmStatic
    @Synchronized
    fun runPatcher(params: Map<String, Any?>, callback: ReseamRuntimeCallback): String? {
        var activeStep: StepId = StepId.LoadPatches
        var progress: ReseamProgress? = null
        fun checkCancelled() {
            if (callback.isCancelled()) throw CancellationException("Patching cancelled")
        }
        fun event(value: ProgressEvent) = callback.event(value.toMap())
        val logger = object : Logger() {
            override fun log(level: LogLevel, message: String) = callback.log(level.name, message)
        }
        var workspace: File? = null
        return try {
            val input = File(params["inputFile"] as String)
            val output = File(params["outputFile"] as String)
            val cache = File(params["cacheDir"] as String).also { it.mkdirs() }
            val stripNativeLibs = params["stripNativeLibs"] == true
            val configurations = (params["configurations"] as List<*>).map { it as Map<*, *> }
            // Loading diagnostics follow Morphe; see third-party/NOTICE.txt.
            // https://github.com/MorpheApp/morphe-manager/pull/1246
            val loaded = runStep(StepId.LoadPatches, ::event, ::checkCancelled) {
                configurations.map { config ->
                    val path = config["bundlePath"] as String
                    logger.info("Loading patches from ${File(path).name}")
                    val startedAt = System.nanoTime()
                    LoadedBundle(config, inspectBundle(path)).also {
                        logger.info("Loaded ${it.response.patches.size} patches of ${File(path).name} in ${(System.nanoTime() - startedAt) / 1_000_000}ms")
                    }
                }
            }
            val selectedSources = loaded.indices.filter { index ->
                (loaded[index].config["patches"] as Collection<*>).isNotEmpty()
            }.toSet()
            val sourceIndexes = ReseamBundleSelection.select(
                loaded.map { bundle -> bundle.response.patches.map { it.spec } }, selectedSources
            )
            val runtimeBundles = sourceIndexes.map(loaded::get)
            val selected = runtimeBundles.flatMap { bundle ->
                val visible = bundle.response.patches.map { it.spec }.filterNot { it.hidden }
                    .associateBy(ReseamMetadata::label)
                (bundle.config["patches"] as Iterable<*>).map { name ->
                    SelectedPatch(requireNotNull(visible[name]) {
                        "Selected Reseam patch is unavailable: " + name + ". Re-open patch selection."
                    }, bundle.config)
                }
            }.sortedBy { ReseamMetadata.label(it.spec) }
            require(selected.isNotEmpty()) { "No Reseam patches selected." }
            require(selected.map { it.reference }.distinct().size == selected.size) {
                "The same Reseam bundle/patch ID is present in multiple selected sources."
            }
            val selectionOptions = selected.associate { item ->
                val rawOptions = (item.config["options"] as? Map<*, *>)
                    ?.get(ReseamMetadata.label(item.spec)) as? Map<*, *> ?: emptyMap<Any, Any>()
                val declarations = item.spec.options.associateBy { it.key }
                item.reference to rawOptions.mapNotNull { (key, value) ->
                    if (value == null) return@mapNotNull null
                    val declaration = requireNotNull(declarations[key]) { "Unknown Reseam option: " + key }
                    declaration.key to ReseamMetadata.optionValue(declaration, value)
                }.toMap()
            }
            workspace = Files.createTempDirectory(cache.toPath(), "reseam-").toFile()
            val components = if (SplitApkPreparer.isSplitArchive(input)) {
                activeStep = StepId.PrepareSplitApk
                val splitProgress = ReseamSplitProgress(callback)
                splitProgress.start()
                val componentDir = workspace!!.resolve("components")
                val extractionStarted = System.nanoTime()
                val extracted = SplitApkFiles.extract(input, componentDir, SplitApkPreparer.splitApkEntryNames(input)) { copied, total ->
                    checkCancelled()
                    splitProgress.extracting(copied, total)
                }
                logger.info("Reseam split extraction took ${(System.nanoTime() - extractionStarted) / 1_000_000}ms.")
                splitProgress.inspecting()
                val inspectionStarted = System.nanoTime()
                // Only the manifests are needed to select the base and recommend components.
                val inspection = runBlocking { SplitApkPreparer.inspectDirectory(componentDir) }
                logger.info("Reseam split inspection took ${(System.nanoTime() - inspectionStarted) / 1_000_000}ms.")
                val baseName = requireNotNull(inspection.baseModuleName) { "Split input is missing its base APK." }
                logger.info("Found ${extracted.size} split modules: ${extracted.joinToString { it.name }}")
                logger.info("Module sizes: ${extracted.joinToString { "${it.name}=${it.length()} bytes" }}")
                val manualSelection = (params["includedSplitModules"] as? Collection<*>)?.map {
                    require(it is String) { "Invalid split selection." }
                    it
                }?.toSet()
                val included = manualSelection ?: inspection.modules.map { it.name }.toSet().let { all ->
                    var keep = all
                    if (stripNativeLibs) keep = keep intersect inspection.abiTrimmedModules
                    if (params["skipUnneededSplits"] == true) {
                        keep = keep intersect inspection.languageTrimmedModules intersect inspection.densityTrimmedModules
                    }
                    keep
                }
                require(baseName in included) { "Split selection must include the base APK." }
                val retained = extracted.filter { it.name in included }
                val excluded = extracted.filterNot { it.name in included }
                logger.info("Included splits: ${retained.joinToString { it.name }}")
                logger.info("Excluded splits: ${excluded.joinToString { it.name }}")
                excluded.forEach { it.delete() }
                require(included.all { name -> retained.any { it.name == name } }) { "Selected split APK is unavailable." }
                val base = retained.single { it.name == baseName }
                val canonicalBase = base.parentFile!!.resolve("base.apk")
                if (base != canonicalBase) {
                    require(!canonicalBase.exists()) { "Conflicting base APK name." }
                    Files.move(base.toPath(), canonicalBase.toPath())
                }
                val result = listOf(canonicalBase) + retained.filterNot { it == base }
                splitProgress.components(result.map { it.name })
                runBlocking {
                    result.forEach { component ->
                        checkCancelled()
                        splitProgress.preparing(component.name, finished = false)
                        if (stripNativeLibs) NativeLibStripper.strip(component)
                        splitProgress.preparing(component.name, finished = true)
                    }
                }
                logger.info("Reseam: patching ${result.size} separate APK components.")
                splitProgress.completed()
                result
            } else listOf(if (stripNativeLibs) {
                input.copyTo(workspace!!.resolve("base.apk")).also { runBlocking { NativeLibStripper.strip(it) } }
            } else input)
            val signedOutput = workspace!!.resolve(if (components.size > 1) "engine-output" else "engine-output.apk")
            val request = PatchRequest(
                apkPath = components.first().absolutePath, splitPaths = components.drop(1).map { it.absolutePath },
                bundlePaths = runtimeBundles.map { it.config["bundlePath"] as String },
                trust = Trust(runtimeBundles.map { it.response.bundles.single().publicKey }.distinct()),
                selection = PatchSelection(
                    preset = PatchPreset.NONE, enable = selected.map { it.reference },
                    disable = emptyList(), options = selectionOptions,
                    ignoreVersions = params["ignorePatchVersions"] == true
                ),
                output = if (components.size > 1) PatchOutput.SplitDir(signedOutput.absolutePath)
                    else PatchOutput.SingleFile(signedOutput.absolutePath)
            )
            val events = ReseamProgress(
                selected.map { it.reference }, selected.map { it.spec.name },
                params["continueOnPatchError"] == true, callback
            )
            progress = events
            logger.info("Reseam storage: input components=${components.sumOf { it.length() }} bytes, " +
                "available=${workspace!!.usableSpace} bytes.")
            events.start()
            // SDK callbacks run synchronously inside JNI. Never re-enter the SDK or throw for
            // cancellation in callbacks; the host terminates this worker process on cancellation.
            val outcome = try {
                patch(request, events)
            } catch (error: SdkError) {
                if (params["continueOnPatchError"] != true || error.problem !is Problem.PatchesFailed) throw error
                val successful = events.successfulReferences()
                logger.warn("Reseam: rebuilding output with successful patches after patch failures.")
                // The SDK refuses output on any failure. Rebuild from clean input so failed patches
                // cannot leave partial edits in a continue-on-error result.
                patch(request.copy(selection = request.selection.copy(
                    enable = successful, options = selectionOptions.filterKeys { it in successful }
                )), events)
            }
            checkCancelled()
            outcome.results.forEach { events.finishPatch(it.patch, it.status) }
            events.beforePublish()
            // The SDK has finished reading its inputs. Release only our disposable copies.
            components.filter { it != input }.forEach { it.delete() }
            val unsignedOutput = workspace!!.resolve("unsigned.apk")
            events.preparingUnsigned(0, components.size)
            if (components.size > 1) {
                val unsignedComponents = components.mapIndexed { index, component ->
                    checkCancelled()
                    val signed = signedOutput.resolve(component.name)
                    require(signed.isFile) { "Reseam output is missing ${component.name}." }
                    ReseamUnsignedApk.stripInPlace(signed)
                    events.preparingUnsigned(index + 1, components.size)
                    signed
                }
                events.finalizing()
                SplitApkFiles.pack(unsignedComponents, unsignedOutput) { copied, total ->
                    checkCancelled()
                    events.finalizing(copied, total)
                }
            } else {
                ReseamUnsignedApk.stripInPlace(signedOutput)
                events.preparingUnsigned(1, 1)
                events.finalizing()
                Files.move(signedOutput.toPath(), unsignedOutput.toPath())
            }
            checkCancelled()
            Files.move(unsignedOutput.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            events.published()
            null
        } catch (_: CancellationException) {
            "__PATCHING_CANCELLED__"
        } catch (error: Throwable) {
            val step = progress?.activeStep?.let { kind -> when (kind) {
                "ReadAPK" -> StepId.ReadAPK
                "WriteAPK" -> StepId.WriteAPK
                else -> StepId.ExecutePatches
            } } ?: activeStep
            val stack = error.toSafeStackTraceString()
            val message = if (error.message?.contains("No space left on device", ignoreCase = true) == true) {
                "Device storage is full. Free internal storage or exclude unused split APKs, then retry. " + error.message
            } else error.message ?: if (error is OutOfMemoryError) "Patching ran out of memory" else null
            // The host may terminate the process immediately when it receives a fatal event.
            runCatching { workspace?.deleteRecursively() }
            event(ProgressEvent.Failed(step, RemoteError(error.javaClass.name, message, stack)))
            stack
        } finally {
            workspace?.deleteRecursively()
        }
    }

    private data class LoadedBundle(val config: Map<*, *>, val response: InspectResponse)
    private data class SelectedPatch(val spec: PatchSpec, val config: Map<*, *>) {
        val reference: String get() = spec.bundle + "/" + spec.id
    }

    private fun StepId.toMap(): Map<String, Any?> = when (this) {
        is StepId.ExecutePatch -> mapOf("kind" to "ExecutePatch", "index" to index)
        else -> mapOf("kind" to when (this) {
            StepId.LoadPatches -> "LoadPatches"
            StepId.PrepareSplitApk -> "PrepareSplitApk"
            StepId.ReadAPK -> "ReadAPK"
            StepId.ExecutePatches -> "ExecutePatches"
            StepId.WriteAPK -> "WriteAPK"
            StepId.SignAPK -> "SignAPK"
            StepId.DownloadAPK -> "DownloadAPK"
            else -> error("Unknown step")
        })
    }

    private fun ProgressEvent.toMap(): Map<String, Any?> = when (this) {
        is ProgressEvent.Started -> mapOf("type" to "Started", "stepId" to stepId.toMap(), "subSteps" to subSteps)
        is ProgressEvent.Progress -> mapOf(
            "type" to "Progress", "stepId" to stepId.toMap(), "message" to message,
            "current" to current, "total" to total, "subSteps" to subSteps
        )
        is ProgressEvent.Completed -> mapOf("type" to "Completed", "stepId" to stepId.toMap())
        is ProgressEvent.Failed -> mapOf(
            "type" to "Failed", "stepId" to stepId?.toMap(),
            "error" to mapOf("type" to error.type, "message" to error.message, "stackTrace" to error.stackTrace)
        )
    }
}
