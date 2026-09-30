package app.urv.manager.ui.screen

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.universal.revanced.manager.R
import app.urv.manager.domain.manager.SignatureMetadataWorkflowProgress
import app.urv.manager.ui.component.TransparentLoadingDialog

@Composable
internal fun SignatureMetadataWorkflowLoadingDialog(
    state: SignatureMetadataWorkflowProgress,
    onCancel: () -> Unit,
    cancelButtonText: String = stringResource(R.string.cancel)
) {
    TransparentLoadingDialog(
        message = stringResource(R.string.patcher_signature_workflow_running) +
            "\n" + signatureMetadataStageText(state.stage),
        cancelButtonText = cancelButtonText,
        onCancel = onCancel,
        logContent = {
            SignatureMetadataRawLog(
                entries = state.logEntries,
                logRevision = state.logRevision,
                logSessionId = state.logSessionId,
                scrollState = rememberScrollState(),
                showActions = false,
                onCopy = {},
                onExport = {}
            )
        }
    )
}
