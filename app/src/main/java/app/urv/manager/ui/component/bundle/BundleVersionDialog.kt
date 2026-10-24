package app.urv.manager.ui.component.bundle

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.urv.manager.domain.bundles.JsonPatchBundle
import app.urv.manager.network.dto.ReVancedAsset
import app.urv.manager.ui.component.LazyColumnWithScrollbar
import kotlinx.coroutines.CancellationException

@Composable
internal fun BundleVersionDialog(
    source: JsonPatchBundle,
    onSelect: (ReVancedAsset) -> Unit,
    onDismiss: () -> Unit
) {
    var versions by remember(source.uid, source.endpoint) { mutableStateOf<List<ReVancedAsset>?>(null) }
    var error by remember(source.uid, source.endpoint) { mutableStateOf<Throwable?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(source.uid, source.endpoint, attempt) {
        error = null
        versions = null
        try {
            versions = source.fetchAvailableVersions()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure
        }
    }
    val selected = source.selectedVersion?.version
        ?: source.installedVersionSignature ?: source.version
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bundle_select_version)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        text = {
            when {
                error != null -> Column {
                    Text(stringResource(R.string.bundle_versions_load_error))
                    Text(error?.localizedMessage.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
                }
                versions == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                versions!!.isEmpty() -> Text(stringResource(R.string.bundle_versions_empty))
                else -> LazyColumnWithScrollbar(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                ) {
                    items(versions!!, key = { it.version }) { asset ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .selectable(
                                    selected = asset.version == selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(asset) }
                                )
                                .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(asset.version, modifier = Modifier.weight(1f))
                            RadioButton(selected = asset.version == selected, onClick = null)
                        }
                    }
                }
            }
        }
    )
}
