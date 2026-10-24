package app.urv.manager.domain.manager

import android.content.Context
import app.urv.manager.patcher.runtime.BoundRuntimeClient
import app.urv.manager.patcher.runtime.RuntimeEngine
import java.io.File

internal class SignatureMetadataInjectorRuntime(private val context: Context) {
    @Volatile private var client: BoundRuntimeClient? = null
    fun cancelActiveExecution() { client?.cancel() }
    suspend fun execute(metadataArchive: File, targetApk: File, outputApk: File, workspace: File,
        mode: SignatureMetadataInjectionMode,
        onProgress: (SignatureMetadataInjectorProgress) -> Unit,
        onLog: (String) -> Unit): SignatureMetadataInjectorEngineResult {
        val current = BoundRuntimeClient(context)
        client = current
        var result: SignatureMetadataInjectorEngineResult? = null
        try {
            current.execute(RuntimeEngine.TOOLS, mapOf("action" to "signature",
                "metadataArchive" to metadataArchive.path, "inputFile" to targetApk.path,
                "outputFile" to outputApk.path, "mode" to mode.name)) { type, message ->
                when (type) {
                    "log" -> onLog(message["message"] as String)
                    "event" -> (message["event"] as Map<*, *>).let { event ->
                        (event["stage"] as? String)?.let {
                            onProgress(SignatureMetadataInjectorProgress(SignatureMetadataInjectorStage.valueOf(it)))
                        }
                        (event["result"] as? Map<*, *>)?.let {
                            result = SignatureMetadataInjectorEngineResult(
                                (it["injectedEntries"] as List<*>).map { name -> name as String },
                                (it["skippedEntries"] as List<*>).map { name -> name as String },
                                (it["removedSignatureEntryCount"] as Number).toInt())
                        }
                    }
                }
            }
            return checkNotNull(result) { "The signature host did not return its result." }
        } catch (error: Throwable) { outputApk.delete(); throw error }
        finally { if (client === current) client = null }
    }
}
