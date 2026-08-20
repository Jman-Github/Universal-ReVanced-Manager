package app.urv.manager.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts

class BatchActivityProxyActivity : ComponentActivity() {
    private var requestId: String? = null
    private var resultDelivered = false

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        finishWithResult(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        if (savedInstanceState != null) return

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
        if (resultDelivered) return
        resultDelivered = true
        setResult(Activity.RESULT_OK, resultIntent(result))
        super.finish()
    }

    override fun finish() {
        if (!resultDelivered && requestId != null) {
            resultDelivered = true
            setResult(
                Activity.RESULT_OK,
                resultIntent(ActivityResult(Activity.RESULT_CANCELED, null))
            )
        }
        super.finish()
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

        fun createIntent(context: Context, requestId: String, target: Intent) =
            Intent(context, BatchActivityProxyActivity::class.java).apply {
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_TARGET_INTENT, target)
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
