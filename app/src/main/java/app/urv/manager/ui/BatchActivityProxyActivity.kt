package app.urv.manager.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.domain.installer.root.RootExternalInstallActivityRegistry
import app.urv.manager.domain.installer.root.RootMountTransactionCoordinator
import app.urv.manager.domain.installer.root.recoverPendingRootExternalInstall
import app.urv.manager.util.PM
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BatchActivityProxyActivity : ComponentActivity(), KoinComponent {
    private var requestId: String? = null
    private var recoveryRequestId: String? = null
    private var processChanged = false
    private var resultDelivered = false
    private var resultFinalizationStarted = false
    private var pendingFinalResult: ActivityResult? = null
    private var recoveryActivityRegistered = false
    private val rootInstaller: RootInstaller by inject()
    private val rootMountCoordinator: RootMountTransactionCoordinator by inject()
    private val pm: PM by inject()

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        finishWithResult(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        recoveryRequestId = intent.getStringExtra(EXTRA_RECOVERY_REQUEST_ID)
        processChanged = intent.getStringExtra(EXTRA_PROCESS_INSTANCE_ID) != processInstanceId
        registerRecoveryActivity()
        if (savedInstanceState != null) {
            if (savedInstanceState.getBoolean(STATE_RESULT_FINALIZATION_STARTED)) {
                finishWithResult(
                    ActivityResult(
                        savedInstanceState.getInt(
                            STATE_RESULT_CODE,
                            Activity.RESULT_CANCELED
                        ),
                        savedResultData(savedInstanceState)
                    )
                )
            }
            return
        }

        val targetIntent = intent.extras?.get(EXTRA_TARGET_INTENT) as? Intent
        if (requestId == null || targetIntent == null) {
            finishWithResult(ActivityResult(Activity.RESULT_CANCELED, null))
            return
        }

        runCatching {
            launcher.launch(
                Intent(targetIntent).apply {
                    removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure {
            finishWithResult(ActivityResult(Activity.RESULT_CANCELED, null))
        }
    }

    private fun finishWithResult(result: ActivityResult) {
        if (resultDelivered || resultFinalizationStarted) return
        resultFinalizationStarted = true
        pendingFinalResult = result
        val recoveryId = recoveryRequestId
        if (recoveryId == null) {
            deliverResult(result)
            return
        }
        val pending = requestId?.let(pendingResults::get)
        if (!processChanged && pending != null) {
            pending.result.complete(result)
            lifecycleScope.launch {
                val callerWillFinalize = try {
                    pending.callerWillFinalize.await()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    false
                }
                if (!callerWillFinalize) {
                    runCatching {
                        recoverPendingRootExternalInstall(
                            applicationContext,
                            recoveryId,
                            rootInstaller,
                            rootMountCoordinator,
                            pm
                        )
                    }
                }
                finishActivity()
            }
            return
        }
        lifecycleScope.launch {
            runCatching {
                recoverPendingRootExternalInstall(
                    applicationContext,
                    recoveryId,
                    rootInstaller,
                    rootMountCoordinator,
                    pm
                )
            }
            deliverResult(result)
        }
    }

    private fun deliverResult(result: ActivityResult) {
        setResult(Activity.RESULT_OK, resultIntent(result))
        finishActivity()
    }

    private fun finishActivity() {
        if (resultDelivered) return
        resultDelivered = true
        requestId?.let(::unregisterCaller)
        unregisterRecoveryActivity()
        super.finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingFinalResult?.let { result ->
            outState.putBoolean(STATE_RESULT_FINALIZATION_STARTED, true)
            outState.putInt(STATE_RESULT_CODE, result.resultCode)
            outState.putParcelable(STATE_RESULT_DATA, result.data)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        unregisterRecoveryActivity()
        super.onDestroy()
    }

    override fun finish() {
        if (resultFinalizationStarted && !resultDelivered) return
        if (!resultDelivered && !resultFinalizationStarted && requestId != null) {
            finishWithResult(ActivityResult(Activity.RESULT_CANCELED, null))
            return
        }
        super.finish()
    }

    private fun registerRecoveryActivity() {
        val recoveryId = recoveryRequestId ?: return
        if (recoveryActivityRegistered) return
        RootExternalInstallActivityRegistry.register(recoveryId)
        recoveryActivityRegistered = true
    }

    private fun unregisterRecoveryActivity() {
        val recoveryId = recoveryRequestId ?: return
        if (!recoveryActivityRegistered) return
        RootExternalInstallActivityRegistry.unregister(recoveryId)
        recoveryActivityRegistered = false
    }

    private fun savedResultData(state: Bundle): Intent? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            state.getParcelable(STATE_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            state.getParcelable(STATE_RESULT_DATA)
        }

    private fun resultIntent(result: ActivityResult) = Intent().apply {
        putExtra(EXTRA_REQUEST_ID, requestId)
        putExtra(EXTRA_RESULT_CODE, result.resultCode)
        putExtra(EXTRA_RESULT_DATA, result.data)
    }

    companion object {
        private const val EXTRA_REQUEST_ID =
            "app.urv.manager.extra.BATCH_ACTIVITY_REQUEST_ID"
        private const val EXTRA_TARGET_INTENT =
            "app.urv.manager.extra.BATCH_ACTIVITY_TARGET_INTENT"
        private const val EXTRA_RESULT_CODE =
            "app.urv.manager.extra.BATCH_ACTIVITY_RESULT_CODE"
        private const val EXTRA_RESULT_DATA =
            "app.urv.manager.extra.BATCH_ACTIVITY_RESULT_DATA"
        private const val EXTRA_RECOVERY_REQUEST_ID =
            "app.urv.manager.extra.BATCH_ACTIVITY_RECOVERY_REQUEST_ID"
        private const val EXTRA_PROCESS_INSTANCE_ID =
            "app.urv.manager.extra.BATCH_ACTIVITY_PROCESS_INSTANCE_ID"
        private const val STATE_RESULT_FINALIZATION_STARTED =
            "batch_activity_result_finalization_started"
        private const val STATE_RESULT_CODE = "batch_activity_result_code"
        private const val STATE_RESULT_DATA = "batch_activity_result_data"

        private class PendingResult(
            val callerWillFinalize: CompletableDeferred<Boolean>
        ) {
            val result = CompletableDeferred<ActivityResult>()
        }

        private val pendingResults = ConcurrentHashMap<String, PendingResult>()
        private val processInstanceId = UUID.randomUUID().toString()

        internal fun registerCaller(
            requestId: String,
            callerWillFinalize: CompletableDeferred<Boolean>
        ): Boolean = pendingResults.putIfAbsent(
            requestId,
            PendingResult(callerWillFinalize)
        ) == null

        internal suspend fun awaitCallerResult(requestId: String): ActivityResult =
            checkNotNull(pendingResults[requestId]) {
                "Batch activity caller is no longer registered"
            }.result.await()

        internal fun unregisterCaller(requestId: String) {
            pendingResults.remove(requestId)
        }

        internal fun failCaller(requestId: String, error: Throwable) {
            pendingResults.remove(requestId)?.let { pending ->
                pending.callerWillFinalize.complete(false)
                pending.result.completeExceptionally(error)
            }
        }

        fun createIntent(
            context: Context,
            requestId: String,
            target: Intent,
            recoveryRequestId: String? = null
        ) =
            Intent(context, BatchActivityProxyActivity::class.java).apply {
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_TARGET_INTENT, target)
                putExtra(EXTRA_RECOVERY_REQUEST_ID, recoveryRequestId)
                putExtra(EXTRA_PROCESS_INSTANCE_ID, processInstanceId)
            }

        fun requestId(intent: Intent): String? =
            intent.getStringExtra(EXTRA_REQUEST_ID)

        fun decodeResult(result: ActivityResult): Pair<String, ActivityResult>? {
            if (result.resultCode != Activity.RESULT_OK) return null
            val data = result.data ?: return null
            val requestId = data.getStringExtra(EXTRA_REQUEST_ID) ?: return null
            val resultCode = data.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData = data.extras?.get(EXTRA_RESULT_DATA) as? Intent
            return requestId to ActivityResult(resultCode, resultData)
        }
    }
}
