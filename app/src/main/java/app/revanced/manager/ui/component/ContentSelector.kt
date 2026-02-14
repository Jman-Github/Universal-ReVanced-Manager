package app.revanced.manager.ui.component

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.ui.component.patches.PathSelectorDialog
import org.koin.compose.koinInject
import java.nio.file.Path

@Composable
fun ContentSelector(mime: String, onSelect: (Uri) -> Unit, content: @Composable () -> Unit) {
    val fs: Filesystem = koinInject()
    val prefs: PreferencesManager = koinInject()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val storageRoots = remember { fs.storageRoots() }
    val (permissionContract, permissionName) = remember { fs.permissionContract() }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var pendingPicker by rememberSaveable { mutableStateOf(false) }
    val fileFilter = remember(mime) { fileFilterForMime(mime) }
    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) { granted ->
        if (granted && pendingPicker) {
            showPicker = true
        }
        pendingPicker = false
    }
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(onSelect)
    }

    if (showPicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                showPicker = false
                path?.let { onSelect(Uri.fromFile(it.toFile())) }
            },
            fileFilter = fileFilter,
            allowDirectorySelection = false
        )
    }
    LaunchedEffect(useCustomFilePicker) {
        if (!useCustomFilePicker) {
            showPicker = false
            pendingPicker = false
        }
    }

    Button(
        onClick = {
            if (useCustomFilePicker) {
                if (fs.hasStoragePermission()) {
                    showPicker = true
                } else {
                    pendingPicker = true
                    permissionLauncher.launch(permissionName)
                }
            } else {
                openDocumentLauncher.launch(
                    if (mime.isBlank()) arrayOf("*/*") else arrayOf(mime)
                )
            }
        }
    ) {
        content()
    }
}

private fun fileFilterForMime(mime: String): (Path) -> Boolean {
    val extension = when (mime.lowercase()) {
        "application/json" -> "json"
        "application/vnd.android.package-archive" -> "apk"
        "*/*" -> null
        else -> mime.substringAfterLast("/", "").takeIf { it.isNotBlank() && it != "*" }
    } ?: return { true }
    return { path ->
        val name = path.fileName?.toString()?.lowercase().orEmpty()
        name.endsWith(".${extension.lowercase()}")
    }
}
