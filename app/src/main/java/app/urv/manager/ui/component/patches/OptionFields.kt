package app.urv.manager.ui.component.patches

import android.app.Application
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.patcher.patch.Option
import app.urv.manager.ui.component.AlertDialogExtended
import app.urv.manager.ui.component.AppTopBar
import app.urv.manager.ui.component.FloatInputDialog
import app.urv.manager.ui.component.FullscreenDialog
import app.urv.manager.ui.component.IntInputDialog
import app.urv.manager.ui.component.LongInputDialog
import app.urv.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.urv.manager.ui.component.haptics.HapticRadioButton
import app.urv.manager.ui.component.haptics.HapticSwitch
import app.urv.manager.util.isScrollingUp
import app.urv.manager.util.mutableStateSetOf
import app.urv.manager.util.saver.snapshotStateListSaver
import app.urv.manager.util.saver.snapshotStateSetSaver
import app.urv.manager.util.toast
import app.urv.manager.util.transparentListItemColors
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.Serializable
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.reflect.typeOf

private class OptionEditorScope<T : Any>(
    private val editor: OptionEditor<T>,
    val option: Option<T>,
    val openDialog: () -> Unit,
    val dismissDialog: () -> Unit,
    val selectionWarningEnabled: Boolean,
    val showSelectionWarning: () -> Unit,
    val value: T?,
    val setValue: (T?) -> Unit
) {
    fun submitDialog(value: T?) {
        setValue(value)
        dismissDialog()
    }

    fun checkSafeguard(block: () -> Unit) {
        if (!option.required && selectionWarningEnabled)
            showSelectionWarning()
        else
            block()
    }

    fun clickAction() {
        checkSafeguard {
            editor.clickAction(this)
        }
    }

    @Composable
    fun ListItemTrailingContent() = editor.ListItemTrailingContent(this)

    @Composable
    fun Dialog() = editor.Dialog(this)
}

private interface OptionEditor<T : Any> {
    fun clickAction(scope: OptionEditorScope<T>) = scope.openDialog()

    @Composable
    fun ListItemTrailingContent(scope: OptionEditorScope<T>) {
        IconButton(onClick = { scope.checkSafeguard { clickAction(scope) } }) {
            Icon(Icons.Outlined.Edit, stringResource(R.string.edit))
        }
    }

    @Composable
    fun Dialog(scope: OptionEditorScope<T>)
}

private inline fun <reified T : Serializable> OptionEditor<T>.toMapEditorElements() = arrayOf(
    typeOf<T>() to this,
    typeOf<List<T>>() to ListOptionEditor(this)
)

private val optionEditors = mapOf(
    *BooleanOptionEditor.toMapEditorElements(),
    *StringOptionEditor.toMapEditorElements(),
    *IntOptionEditor.toMapEditorElements(),
    *LongOptionEditor.toMapEditorElements(),
    *FloatOptionEditor.toMapEditorElements()
)

@Composable
private inline fun <T : Any> WithOptionEditor(
    editor: OptionEditor<T>,
    option: Option<T>,
    value: T?,
    noinline setValue: (T?) -> Unit,
    selectionWarningEnabled: Boolean,
    crossinline onDismissDialog: @DisallowComposableCalls () -> Unit = {},
    block: OptionEditorScope<T>.() -> Unit
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showSelectionWarningDialog by rememberSaveable { mutableStateOf(false) }

    val scope = remember(editor, option, value, setValue, selectionWarningEnabled) {
        OptionEditorScope(
            editor,
            option,
            openDialog = { showDialog = true },
            dismissDialog = {
                showDialog = false
                onDismissDialog()
            },
            selectionWarningEnabled,
            showSelectionWarning = { showSelectionWarningDialog = true },
            value,
            setValue
        )
    }

    if (showSelectionWarningDialog)
        SelectionWarningDialog(
            onDismiss = { showSelectionWarningDialog = false }
        )

    if (showDialog) scope.Dialog()

    scope.block()
}

@Composable
fun <T : Any> OptionItem(
    option: Option<T>,
    value: T?,
    setValue: (T?) -> Unit,
    selectionWarningEnabled: Boolean
) {
    val editor = remember(option.type, option.presets) {
        @Suppress("UNCHECKED_CAST")
        val baseOptionEditor =
            optionEditors.getOrDefault(option.type, UnknownTypeEditor) as OptionEditor<T>

        if (option.type != typeOf<Boolean>() && option.presets != null) PresetOptionEditor(
            baseOptionEditor
        )
        else baseOptionEditor
    }

    WithOptionEditor(editor, option, value, setValue, selectionWarningEnabled) {
        ListItem(
            modifier = Modifier.clickable(onClick = ::clickAction),
            headlineContent = { Text(option.title) },
            supportingContent = {
                Column {
                    Text(option.description)
                    if (option.required && value == null) Text(
                        stringResource(R.string.option_required),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            trailingContent = { ListItemTrailingContent() }
        )
    }
}

private object StringOptionEditor : OptionEditor<String> {
    @Composable
    override fun ListItemTrailingContent(scope: OptionEditorScope<String>) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (scope.option.isColorOption()) {
                ColorOptionSwatch(scope.value ?: scope.option.default)
            }
            super.ListItemTrailingContent(scope)
        }
    }

    @Composable
    override fun Dialog(scope: OptionEditorScope<String>) {
        var showFileDialog by rememberSaveable { mutableStateOf(false) }
        var showColorPicker by rememberSaveable { mutableStateOf(false) }
        val isColorOption = remember(scope.option) { scope.option.isColorOption() }
        var fieldValue by rememberSaveable(scope.value) {
            mutableStateOf(scope.value.orEmpty())
        }
        var validatorFailed by remember { mutableStateOf(false) }
        val validatorRef by rememberUpdatedState(scope.option.validator)
        LaunchedEffect(fieldValue, isColorOption) {
            val failed = withContext(Dispatchers.Default) {
                val normalizedValue =
                    if (isColorOption) normalizeColorOptionValue(fieldValue) else fieldValue
                val invalidColorValue = isColorOption &&
                    fieldValue.isNotBlank() &&
                    normalizedValue == null
                invalidColorValue ||
                    runCatching { !validatorRef(normalizedValue ?: fieldValue) }.getOrDefault(true)
            }
            validatorFailed = failed
        }

        val fs: Filesystem = koinInject()
        val app: Application = koinInject()
        val prefs: PreferencesManager = koinInject()
        val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
        val storageRoots = remember { fs.storageRoots() }
        val (contract, permissionName) = fs.permissionContract()
        val permissionLauncher = rememberLauncherForActivityResult(contract = contract) {
            showFileDialog = it
        }
        val openDocumentLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            uri?.let { selectedUri ->
                importDocumentUriToLocalPath(fs, selectedUri)?.let { localPath ->
                    fieldValue = localPath
                } ?: app.toast(app.getString(R.string.failed_to_load_file))
            }
        }

        if (showFileDialog && useCustomFilePicker) {
            PathSelectorDialog(
                roots = storageRoots,
                onSelect = {
                showFileDialog = false
                it?.let { path ->
                    fieldValue = path.toString()
                }
            })
        }
        LaunchedEffect(useCustomFilePicker) {
            if (!useCustomFilePicker) {
                showFileDialog = false
            }
        }
        if (showColorPicker) {
            PatchOptionColorPickerDialog(
                title = scope.option.title,
                currentColor = fieldValue,
                onColorSelected = {
                    fieldValue = it
                    showColorPicker = false
                },
                onDismiss = { showColorPicker = false }
            )
        }

        AlertDialog(
            onDismissRequest = scope.dismissDialog,
            title = { Text(scope.option.title) },
            text = {
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    placeholder = {
                        Text(
                            stringResource(
                                if (isColorOption) R.string.color_option_input_placeholder
                                else R.string.dialog_input_placeholder
                            )
                        )
                    },
                    isError = validatorFailed,
                    supportingText = {
                        if (validatorFailed) {
                            Text(
                                stringResource(R.string.input_dialog_value_invalid),
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    trailingIcon = {
                        var showDropdownMenu by rememberSaveable { mutableStateOf(false) }
                        IconButton(
                            onClick = { showDropdownMenu = true }
                        ) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                stringResource(R.string.string_option_menu_description)
                            )
                        }

                        DropdownMenu(
                            expanded = showDropdownMenu,
                            onDismissRequest = { showDropdownMenu = false }
                        ) {
                            if (isColorOption) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        ColorOptionSwatch(fieldValue)
                                    },
                                    text = {
                                        Text(stringResource(R.string.color_picker))
                                    },
                                    onClick = {
                                        showDropdownMenu = false
                                        showColorPicker = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(Icons.Outlined.Folder, null)
                                },
                                text = {
                                    Text(stringResource(R.string.path_selector))
                                },
                                onClick = {
                                    showDropdownMenu = false
                                    if (useCustomFilePicker) {
                                        if (fs.hasStoragePermission()) {
                                            showFileDialog = true
                                        } else {
                                            permissionLauncher.launch(permissionName)
                                        }
                                    } else {
                                        openDocumentLauncher.launch("*/*")
                                    }
                                }
                            )
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !validatorFailed,
                    onClick = {
                        val value = if (isColorOption) {
                            normalizeColorOptionValue(fieldValue) ?: fieldValue.trim()
                        } else {
                            fieldValue
                        }
                        scope.submitDialog(value)
                    }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = scope.dismissDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ColorOptionSwatch(value: String?) {
    val color = remember(value) { parseColorOption(value) }
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(8.dp)
            )
    )
}

@Composable
private fun PatchOptionColorPickerDialog(
    title: String,
    currentColor: String,
    onColorSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val initialColor = remember(currentColor) { parseColorOption(currentColor) ?: Color.Black }
    val initialRed = remember(initialColor) { (initialColor.red * 255).roundToInt() }
    val initialGreen = remember(initialColor) { (initialColor.green * 255).roundToInt() }
    val initialBlue = remember(initialColor) { (initialColor.blue * 255).roundToInt() }

    var red by rememberSaveable(initialRed) { mutableStateOf(initialRed) }
    var green by rememberSaveable(initialGreen) { mutableStateOf(initialGreen) }
    var blue by rememberSaveable(initialBlue) { mutableStateOf(initialBlue) }
    var hexInput by rememberSaveable(currentColor) {
        mutableStateOf(normalizeColorOptionValue(currentColor) ?: rgbToColorHex(initialRed, initialGreen, initialBlue))
    }
    val hexInputInvalid = remember(hexInput) {
        hexInput.isNotBlank() && normalizeColorOptionValue(hexInput) == null
    }
    val previewColor = remember(red, green, blue) { rgbToColor(red, green, blue) }

    fun updateFromColor(r: Int = red, g: Int = green, b: Int = blue) {
        red = r.coerceIn(0, 255)
        green = g.coerceIn(0, 255)
        blue = b.coerceIn(0, 255)
        hexInput = rgbToColorHex(red, green, blue)
    }

    AlertDialogExtended(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.color_option_preview),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(previewColor)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = rgbToColorHex(red, green, blue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (previewColor.red + previewColor.green + previewColor.blue > 1.5f) {
                            Color.Black
                        } else {
                            Color.White
                        }
                    )
                }
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        hexInput = input
                        parseColorOption(input)?.let { color ->
                            red = (color.red * 255).roundToInt()
                            green = (color.green * 255).roundToInt()
                            blue = (color.blue * 255).roundToInt()
                        }
                    },
                    singleLine = true,
                    isError = hexInputInvalid,
                    placeholder = { Text(stringResource(R.string.color_option_input_placeholder)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                    supportingText = {
                        if (hexInputInvalid) {
                            Text(
                                stringResource(R.string.input_dialog_value_invalid),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                ColorChannelSlider(
                    label = stringResource(R.string.color_channel_red),
                    value = red,
                    trackColor = Color.Red,
                    onValueChange = { updateFromColor(r = it) }
                )
                ColorChannelSlider(
                    label = stringResource(R.string.color_channel_green),
                    value = green,
                    trackColor = Color.Green,
                    onValueChange = { updateFromColor(g = it) }
                )
                ColorChannelSlider(
                    label = stringResource(R.string.color_channel_blue),
                    value = blue,
                    trackColor = Color.Blue,
                    onValueChange = { updateFromColor(b = it) }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !hexInputInvalid,
                onClick = {
                    onColorSelected(normalizeColorOptionValue(hexInput) ?: rgbToColorHex(red, green, blue))
                }
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ColorChannelSlider(
    label: String,
    value: Int,
    trackColor: Color,
    onValueChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value.toString(), style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                activeTrackColor = trackColor,
                inactiveTrackColor = trackColor.copy(alpha = 0.3f),
                thumbColor = trackColor
            )
        )
    }
}

private fun Option<String>.isColorOption(): Boolean {
    val text = listOf(key, title, description)
        .joinToString(" ")
        .lowercase(Locale.US)
    if ("color" in text || "colour" in text || "hex" in text) return true

    if (default?.trim()?.isColorLikeOptionValue() == true) return true
    return presets?.values?.any { it?.trim()?.isColorLikeOptionValue() == true } == true
}

private fun String.isColorLikeOptionValue(): Boolean =
    startsWith("@android:color/") ||
        startsWith("@color/") ||
        normalizeColorOptionValue(this) != null

private fun normalizeColorOptionValue(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.startsWith("@")) return trimmed

    val hex = trimmed
        .removePrefix("#")
        .removePrefix("0x")
        .removePrefix("0X")
    if (!hex.matches(Regex("^[0-9a-fA-F]{3,4}$|^[0-9a-fA-F]{6}$|^[0-9a-fA-F]{8}$"))) {
        return null
    }

    val expanded = when (hex.length) {
        3 -> hex.flatMap { listOf(it, it) }.joinToString("")
        4 -> "${hex[3]}${hex[3]}${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
        else -> hex
    }.uppercase(Locale.US)

    val normalized = "#$expanded"
    return if (runCatching { AndroidColor.parseColor(normalized) }.isSuccess) normalized else null
}

private fun parseColorOption(value: String?): Color? {
    val normalized = normalizeColorOptionValue(value.orEmpty()) ?: return null
    if (normalized.startsWith("@") || normalized.isBlank()) return null
    return runCatching { Color(AndroidColor.parseColor(normalized)) }.getOrNull()
}

private fun rgbToColor(red: Int, green: Int, blue: Int): Color = Color(
    red = red.coerceIn(0, 255) / 255f,
    green = green.coerceIn(0, 255) / 255f,
    blue = blue.coerceIn(0, 255) / 255f
)

private fun rgbToColorHex(red: Int, green: Int, blue: Int): String =
    "#%02X%02X%02X".format(
        red.coerceIn(0, 255),
        green.coerceIn(0, 255),
        blue.coerceIn(0, 255)
    )

private fun importDocumentUriToLocalPath(fs: Filesystem, uri: Uri): String? {
    val resolver = fs.contentResolver
    val displayName = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex < 0) null else cursor.getString(nameIndex)
            }.orEmpty()
    }.getOrDefault("")
    val extension = displayName
        .substringAfterLast('.', "")
        .lowercase(Locale.ROOT)
        .ifBlank {
            resolver.getType(uri)
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                .orEmpty()
                .lowercase(Locale.ROOT)
        }
        .takeIf { it.matches(Regex("^[a-z0-9]{1,10}$")) }
        ?: "dat"
    val outDir = fs.tempDir.resolve("patch-option-inputs").apply { mkdirs() }
    val outFile = outDir.resolve("option_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}.$extension")
    val input = resolver.openInputStream(uri) ?: return null

    return runCatching {
        input.use { source ->
            outFile.outputStream().use { output -> source.copyTo(output) }
        }
        outFile.absolutePath
    }.getOrNull()
}

private abstract class NumberOptionEditor<T : Number> : OptionEditor<T> {
    @Composable
    abstract fun NumberDialog(
        title: String,
        current: T?,
        validator: (T?) -> Boolean,
        onSubmit: (T?) -> Unit
    )

    @Composable
    override fun Dialog(scope: OptionEditorScope<T>) {
        NumberDialog(scope.option.title, scope.value, scope.option.validator) {
            if (it == null) return@NumberDialog scope.dismissDialog()

            scope.submitDialog(it)
        }
    }
}

private object IntOptionEditor : NumberOptionEditor<Int>() {
    @Composable
    override fun NumberDialog(
        title: String,
        current: Int?,
        validator: (Int?) -> Boolean,
        onSubmit: (Int?) -> Unit
    ) = IntInputDialog(current, title, validator, onSubmit)
}

private object LongOptionEditor : NumberOptionEditor<Long>() {
    @Composable
    override fun NumberDialog(
        title: String,
        current: Long?,
        validator: (Long?) -> Boolean,
        onSubmit: (Long?) -> Unit
    ) = LongInputDialog(current, title, validator, onSubmit)
}

private object FloatOptionEditor : NumberOptionEditor<Float>() {
    @Composable
    override fun NumberDialog(
        title: String,
        current: Float?,
        validator: (Float?) -> Boolean,
        onSubmit: (Float?) -> Unit
    ) = FloatInputDialog(current, title, validator, onSubmit)
}

private object BooleanOptionEditor : OptionEditor<Boolean> {
    override fun clickAction(scope: OptionEditorScope<Boolean>) {
        scope.setValue(!scope.current)
    }

    @Composable
    override fun ListItemTrailingContent(scope: OptionEditorScope<Boolean>) {
        HapticSwitch(
            checked = scope.current,
            onCheckedChange = { value ->
                scope.checkSafeguard {
                    scope.setValue(value)
                }
            }
        )
    }

    @Composable
    override fun Dialog(scope: OptionEditorScope<Boolean>) {
    }

    private val OptionEditorScope<Boolean>.current get() = value ?: false
}

private object UnknownTypeEditor : OptionEditor<Any>, KoinComponent {
    override fun clickAction(scope: OptionEditorScope<Any>) =
        get<Application>().toast("Unknown type: ${scope.option.type}")

    @Composable
    override fun Dialog(scope: OptionEditorScope<Any>) {
    }
}

/**
 * A wrapper for [OptionEditor]s that shows selectable presets.
 *
 * @param innerEditor The [OptionEditor] for [T].
 */
private class PresetOptionEditor<T : Any>(private val innerEditor: OptionEditor<T>) :
    OptionEditor<T> {
    @Composable
    override fun Dialog(scope: OptionEditorScope<T>) {
        var selectedPreset by rememberSaveable(scope.value, scope.option.presets) {
            val presets = scope.option.presets!!

            mutableStateOf(presets.entries.find { it.value == scope.value }?.key)
        }

        WithOptionEditor(
            innerEditor,
            scope.option,
            scope.value,
            scope.setValue,
            scope.selectionWarningEnabled,
            onDismissDialog = scope.dismissDialog
        ) inner@{
            var hidePresetsDialog by rememberSaveable {
                mutableStateOf(false)
            }
            var openCustomDialog by rememberSaveable {
                mutableStateOf(false)
            }
            LaunchedEffect(openCustomDialog) {
                if (openCustomDialog) {
                    openCustomDialog = false
                    this@inner.openDialog()
                }
            }
            if (hidePresetsDialog) return@inner

            // TODO: add a divider for scrollable content
            AlertDialogExtended(
                onDismissRequest = scope.dismissDialog,
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (selectedPreset != null) scope.submitDialog(
                                scope.option.presets?.get(
                                    selectedPreset
                                )
                            )
                            else {
                                // Hide the presets dialog so it doesn't show up in the background.
                                hidePresetsDialog = true
                                // Open the custom dialog on the next frame to avoid flicker.
                                openCustomDialog = true
                            }
                        }
                    ) {
                        Text(stringResource(if (selectedPreset != null) R.string.save else R.string.continue_))
                    }
                },
                dismissButton = {
                    TextButton(onClick = scope.dismissDialog) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                title = { Text(scope.option.title) },
                textHorizontalPadding = PaddingValues(horizontal = 0.dp),
                text = {
                    val presets = remember(scope.option.presets) {
                        scope.option.presets?.entries?.toList().orEmpty()
                    }

                    LazyColumn {
                        @Composable
                        fun Item(title: String, value: Any?, presetKey: String?) {
                            ListItem(
                                modifier = Modifier.clickable { selectedPreset = presetKey },
                                headlineContent = { Text(title) },
                                supportingContent = value?.toString()?.let { { Text(it) } },
                                leadingContent = {
                                    HapticRadioButton(
                                        selected = selectedPreset == presetKey,
                                        onClick = { selectedPreset = presetKey }
                                    )
                                },
                                colors = transparentListItemColors
                            )
                        }

                        items(presets, key = { it.key }) {
                            Item(it.key, it.value, it.key)
                        }

                        item(key = null) {
                            Item(stringResource(R.string.option_preset_custom_value), null, null)
                        }
                    }
                }
            )
        }
    }
}

private class ListOptionEditor<T : Serializable>(private val elementEditor: OptionEditor<T>) :
    OptionEditor<List<T>> {
    private fun createElementOption(option: Option<List<T>>) = Option<T>(
        option.title,
        option.key,
        option.description,
        option.required,
        option.type.arguments.first().type!!,
        null,
        null
    ) { true }

    @OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
    @Composable
    override fun Dialog(scope: OptionEditorScope<List<T>>) {
        val items =
            rememberSaveable(scope.value, saver = snapshotStateListSaver()) {
                // We need a key for each element in order to support dragging.
                scope.value?.map(::Item)?.toMutableStateList() ?: mutableStateListOf()
            }
        val listIsDirty by remember {
            derivedStateOf {
                val current = scope.value.orEmpty()
                if (current.size != items.size) return@derivedStateOf true

                current.forEachIndexed { index, value ->
                    if (value != items[index].value) return@derivedStateOf true
                }

                false
            }
        }

        val lazyListState = rememberLazyListState()
        val reorderableLazyColumnState =
            // Update the list
            rememberReorderableLazyListState(lazyListState) { from, to ->
                // Update the list
                items.add(to.index, items.removeAt(from.index))
            }

        var deleteMode by rememberSaveable {
            mutableStateOf(false)
        }
        val deletionTargets = rememberSaveable(saver = snapshotStateSetSaver()) {
            mutableStateSetOf<Int>()
        }

        val back = back@{
            if (deleteMode) {
                deletionTargets.clear()
                deleteMode = false
                return@back
            }

            if (!listIsDirty) {
                scope.dismissDialog()
                return@back
            }

            scope.submitDialog(items.mapNotNull { it.value })
        }

            FullscreenDialog(
                onDismissRequest = back,
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    topBar = {
                        AppTopBar(
                            title = if (deleteMode) pluralStringResource(
                                R.plurals.selected_apps_count,
                            deletionTargets.size,
                            deletionTargets.size
                            ) else scope.option.title,
                            onBackClick = back,
                            applyContainerColor = true,
                            backIcon = {
                                if (deleteMode) {
                                    return@AppTopBar Icon(
                                    Icons.Filled.Close,
                                    stringResource(R.string.cancel)
                                )
                            }

                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        },
                        actions = {
                            if (deleteMode) {
                                IconButton(
                                    onClick = {
                                        if (items.size == deletionTargets.size) deletionTargets.clear()
                                        else deletionTargets.addAll(items.map { it.key })
                                    }
                                ) {
                                    Icon(
                                        Icons.Outlined.SelectAll,
                                        stringResource(R.string.select_deselect_all)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        items.removeIf { it.key in deletionTargets }
                                        deletionTargets.clear()
                                        deleteMode = false
                                    }
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        stringResource(R.string.delete)
                                    )
                                }
                            } else {
                                IconButton(onClick = items::clear) {
                                    Icon(Icons.Outlined.Restore, stringResource(R.string.reset))
                                }
                            }
                        }
                    )
                },
                floatingActionButton = {
                    if (deleteMode) return@Scaffold

                    HapticExtendedFloatingActionButton(
                        text = { Text(stringResource(R.string.add)) },
                        icon = {
                            Icon(
                                Icons.Outlined.Add,
                                stringResource(R.string.add)
                            )
                        },
                        expanded = lazyListState.isScrollingUp,
                        onClick = { items.add(Item(null)) }
                    )
                }
            ) { paddingValues ->
                val elementOption = remember(scope.option) { createElementOption(scope.option) }

                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(paddingValues),
                ) {
                    itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                        val interactionSource = remember { MutableInteractionSource() }

                        ReorderableItem(reorderableLazyColumnState, key = item.key) { _ ->
                            val reorderableScope = this
                            WithOptionEditor(
                                elementEditor,
                                elementOption,
                                value = item.value,
                                setValue = { items[index] = item.copy(value = it) },
                                selectionWarningEnabled = scope.selectionWarningEnabled
                            ) {
                                ListItem(
                                    modifier = Modifier.combinedClickable(
                                        indication = LocalIndication.current,
                                        interactionSource = interactionSource,
                                        onLongClickLabel = stringResource(R.string.select),
                                        onLongClick = {
                                            if (!deleteMode) {
                                                deletionTargets.add(item.key)
                                                deleteMode = true
                                            }
                                        },
                                        onClick = {
                                            if (!deleteMode) {
                                                clickAction()
                                                return@combinedClickable
                                            }

                                            if (item.key in deletionTargets) {
                                                deletionTargets.remove(
                                                    item.key
                                                )
                                                deleteMode = deletionTargets.isNotEmpty()
                                            } else deletionTargets.add(item.key)
                                        },
                                    ),
                                    tonalElevation = if (deleteMode && item.key in deletionTargets) 8.dp else 0.dp,
                                    leadingContent = {
                                        IconButton(
                                            onClick = {},
                                            interactionSource = interactionSource,
                                            modifier = with(reorderableScope) {
                                                Modifier.longPressDraggableHandle()
                                            }
                                        ) {
                                            Icon(
                                                Icons.Filled.DragHandle,
                                                stringResource(R.string.drag_handle)
                                            )
                                        }
                                    },
                                    headlineContent = {
                                        if (item.value == null) return@ListItem Text(
                                            stringResource(R.string.empty),
                                            fontStyle = FontStyle.Italic
                                        )

                                        Text(item.value.toString())
                                    },
                                    trailingContent = {
                                        ListItemTrailingContent()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Parcelize
    private data class Item<T : Serializable>(val value: T?, val key: Int = Random.nextInt()) :
        Parcelable
}
