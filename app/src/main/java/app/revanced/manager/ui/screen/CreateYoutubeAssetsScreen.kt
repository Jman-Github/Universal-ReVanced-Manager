package app.revanced.manager.ui.screen

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.ui.component.AppScaffold
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.ExportSavedApkFileNameDialog
import app.revanced.manager.ui.component.haptics.HapticExtendedFloatingActionButton
import app.revanced.manager.ui.component.patches.PathSelectorDialog
import app.revanced.manager.util.toast
import app.universal.revanced.manager.R
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateYoutubeAssetsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs: PreferencesManager = koinInject()
    val fs: Filesystem = koinInject()
    val useCustomFilePicker by prefs.useCustomFilePicker.getAsState()
    val scope = rememberCoroutineScope()
    val storageRoots = remember { fs.storageRoots() }
    val imageExtensions = remember { setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") }

    var activePicker by rememberSaveable { mutableStateOf<PickerTarget?>(null) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showSavePicker by rememberSaveable { mutableStateOf(false) }
    var saveDialogState by remember { mutableStateOf<SaveDialogState?>(null) }

    var adaptiveSource by rememberSaveable { mutableStateOf<String?>(null) }
    var lightSource by rememberSaveable { mutableStateOf<String?>(null) }
    var darkSource by rememberSaveable { mutableStateOf<String?>(null) }
    var adaptiveTransform by rememberSaveable(stateSaver = ImageTransform.saver) { mutableStateOf(ImageTransform()) }
    var lightTransform by rememberSaveable(stateSaver = ImageTransform.saver) { mutableStateOf(ImageTransform()) }
    var darkTransform by rememberSaveable(stateSaver = ImageTransform.saver) { mutableStateOf(ImageTransform()) }
    var adaptiveSize by remember { mutableStateOf(IntSize.Zero) }
    var lightSize by remember { mutableStateOf(IntSize.Zero) }
    var darkSize by remember { mutableStateOf(IntSize.Zero) }

    var foregroundName by rememberSaveable { mutableStateOf("morphe_adaptive_foreground_custom") }
    var backgroundName by rememberSaveable { mutableStateOf("morphe_adaptive_background_custom") }
    var lightName by rememberSaveable { mutableStateOf("morphe_header_custom_light") }
    var darkName by rememberSaveable { mutableStateOf("morphe_header_custom_dark") }
    var adaptiveBackgroundHex by rememberSaveable { mutableStateOf("FFB6E3FF") }
    val adaptiveBackgroundColor = remember(adaptiveBackgroundHex) { parseColor(adaptiveBackgroundHex, Color(0xFFB6E3FF)) }

    val adaptiveBitmap by rememberLoadedBitmap(adaptiveSource)
    val lightBitmap by rememberLoadedBitmap(lightSource)
    val darkBitmap by rememberLoadedBitmap(darkSource)

    var generating by rememberSaveable { mutableStateOf(false) }
    var generatedZip by remember { mutableStateOf<File?>(null) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    var showColorPicker by rememberSaveable { mutableStateOf(false) }
    var generationMode by rememberSaveable { mutableStateOf(AssetGenerationMode.BOTH) }
    var showGenerationModeMenu by rememberSaveable { mutableStateOf(false) }
    var syncHeaderTransforms by rememberSaveable { mutableStateOf(false) }

    suspend fun applyImageSelection(target: PickerTarget?, source: String): Boolean {
        val decoded = withContext(Dispatchers.IO) { decodeBitmap(context, source) }
        if (decoded == null) {
            errorText = context.getString(R.string.tools_youtube_assets_image_load_failed)
            return false
        }
        when (target) {
            PickerTarget.ADAPTIVE -> {
                adaptiveSource = source
                adaptiveTransform = ImageTransform()
            }
            PickerTarget.LIGHT -> {
                lightSource = source
                lightTransform = ImageTransform()
            }
            PickerTarget.DARK -> {
                darkSource = source
                darkTransform = ImageTransform()
            }
            null -> Unit
        }
        return true
    }

    fun centered(transform: ImageTransform): ImageTransform = transform.copy(offsetX = 0f, offsetY = 0f)

    fun updateLightTransform(updated: ImageTransform) {
        lightTransform = updated
        if (syncHeaderTransforms) darkTransform = updated
    }

    fun updateDarkTransform(updated: ImageTransform) {
        darkTransform = updated
        if (syncHeaderTransforms) lightTransform = updated
    }

    val openImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = activePicker ?: return@rememberLauncherForActivityResult
        showPicker = false
        activePicker = null
        val source = uri?.toString() ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        scope.launch {
            applyImageSelection(target, source)
        }
    }

    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val zip = generatedZip ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        zip.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IOException("Unable to open destination.")
                }
            }.onSuccess {
                context.toast(context.getString(R.string.tools_youtube_assets_save_success))
            }.onFailure {
                errorText = it.message ?: context.getString(R.string.tools_youtube_assets_save_failed)
            }
        }
    }

    LaunchedEffect(activePicker, useCustomFilePicker) {
        if (activePicker == null) return@LaunchedEffect
        if (useCustomFilePicker) showPicker = true else openImage.launch("image/*")
    }

    fun generate() {
        generatedZip = null
        val adaptive = adaptiveBitmap
        val light = lightBitmap
        val dark = darkBitmap
        val sanitizedForeground = sanitizeName(foregroundName)
        val sanitizedBackground = sanitizeName(backgroundName)
        val sanitizedLight = sanitizeName(lightName)
        val sanitizedDark = sanitizeName(darkName)
        val missingMessage = when (generationMode) {
            AssetGenerationMode.BOTH -> if (adaptive == null || light == null || dark == null) {
                context.getString(R.string.tools_youtube_assets_missing_images)
            } else null
            AssetGenerationMode.ADAPTIVE_ONLY -> if (adaptive == null) {
                context.getString(R.string.tools_youtube_assets_missing_adaptive_image)
            } else null
            AssetGenerationMode.HEADER_ONLY -> if (light == null || dark == null) {
                context.getString(R.string.tools_youtube_assets_missing_header_images)
            } else null
        }
        if (missingMessage != null) {
            errorText = missingMessage
            return
        }
        val nameConflictMessage = when (generationMode) {
            AssetGenerationMode.BOTH -> when {
                sanitizedForeground == sanitizedBackground -> context.getString(R.string.tools_youtube_assets_name_conflict_adaptive)
                sanitizedLight == sanitizedDark -> context.getString(R.string.tools_youtube_assets_name_conflict_headers)
                else -> null
            }
            AssetGenerationMode.ADAPTIVE_ONLY -> if (sanitizedForeground == sanitizedBackground) {
                context.getString(R.string.tools_youtube_assets_name_conflict_adaptive)
            } else null
            AssetGenerationMode.HEADER_ONLY -> if (sanitizedLight == sanitizedDark) {
                context.getString(R.string.tools_youtube_assets_name_conflict_headers)
            } else null
        }
        if (nameConflictMessage != null) {
            errorText = nameConflictMessage
            return
        }
        generating = true
        errorText = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val cacheDir = context.cacheDir.resolve("youtube-assets-tools").apply { mkdirs() }
                    cleanupOldGeneratedZips(cacheDir)
                    generateArchive(
                        cacheDir = cacheDir,
                        request = AssetRequest(
                            mode = generationMode,
                            adaptiveForeground = adaptive,
                            adaptiveBackgroundArgb = adaptiveBackgroundColor.toArgb(),
                            headerLight = light,
                            headerDark = dark,
                            adaptiveTransform = adaptiveTransform,
                            lightTransform = lightTransform,
                            darkTransform = darkTransform,
                            adaptiveSize = adaptiveSize,
                            lightSize = lightSize,
                            darkSize = darkSize,
                            foregroundName = sanitizedForeground,
                            backgroundName = sanitizedBackground,
                            lightName = sanitizedLight,
                            darkName = sanitizedDark
                        )
                    )
                }
            }.onSuccess {
                generatedZip = it
                context.toast(context.getString(R.string.tools_youtube_assets_generated))
            }.onFailure {
                generatedZip = null
                errorText = it.message ?: context.getString(R.string.tools_youtube_assets_generate_failed)
            }
            generating = false
        }
    }

    if (showPicker && activePicker != null && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { path ->
                if (path == null) {
                    showPicker = false
                    activePicker = null
                    return@PathSelectorDialog
                }
                if (Files.isDirectory(path)) return@PathSelectorDialog
                val source = Uri.fromFile(path.toFile()).toString()
                val target = activePicker
                showPicker = false
                activePicker = null
                scope.launch {
                    applyImageSelection(target, source)
                }
            },
            fileFilter = { it.extension.lowercase(Locale.ROOT) in imageExtensions },
            allowDirectorySelection = false,
            fileTypeLabel = ".png .jpg .jpeg .webp .gif .bmp",
        )
    }

    if (showColorPicker) {
        AdaptiveColorWheelDialog(
            initialColor = adaptiveBackgroundColor,
            onDismiss = { showColorPicker = false },
            onConfirm = { selected ->
                adaptiveBackgroundHex = String.format(
                    Locale.ROOT,
                    "%08X",
                    selected.toArgb()
                )
            }
        )
    }

    if (showSavePicker && useCustomFilePicker) {
        PathSelectorDialog(
            roots = storageRoots,
            onSelect = { if (it == null) showSavePicker = false },
            fileFilter = { false },
            allowDirectorySelection = true,
            confirmButtonText = stringResource(R.string.save),
            onConfirm = { selected ->
                saveDialogState = SaveDialogState(
                    directory = if (Files.isDirectory(selected)) selected else (selected.parent ?: selected),
                    fileName = generatedZip?.name ?: "youtube-assets.zip"
                )
            }
        )
    }

    saveDialogState?.let { state ->
        ExportSavedApkFileNameDialog(
            initialName = state.fileName,
            onDismiss = { saveDialogState = null }
        ) { enteredName ->
            val zip = generatedZip ?: return@ExportSavedApkFileNameDialog
            saveDialogState = null
            showSavePicker = false
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        zip.copyTo(state.directory.resolve(enteredName.ifBlank { "youtube-assets.zip" }.ensureZip()).toFile(), overwrite = true)
                    }
                }.onSuccess {
                    context.toast(context.getString(R.string.tools_youtube_assets_save_success))
                }.onFailure {
                    errorText = it.message ?: context.getString(R.string.tools_youtube_assets_save_failed)
                }
            }
        }
    }

    AppScaffold(
        topBar = { behavior ->
            AppTopBar(
                title = stringResource(R.string.tools_youtube_assets_title),
                onBackClick = onBackClick,
                scrollBehavior = behavior
            )
        },
        floatingActionButton = {
            HapticExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.save)) },
                icon = { Icon(Icons.Outlined.Save, null) },
                enabled = generatedZip?.exists() == true && !generating,
                onClick = {
                    if (useCustomFilePicker) showSavePicker = true
                    else saveDocument.launch(generatedZip?.name ?: "youtube-assets.zip")
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.tools_youtube_assets_info),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 14.sp)
                        )
                    }
                }
            }
            item {
                AssetEditorCard(
                    title = stringResource(R.string.tools_youtube_assets_adaptive_section),
                    onReset = { adaptiveTransform = ImageTransform() },
                    onCenter = { adaptiveTransform = centered(adaptiveTransform) },
                    onClear = {
                        adaptiveSource = null
                        adaptiveTransform = ImageTransform()
                    }
                ) {
                    PreviewCircle(
                        bitmap = adaptiveBitmap,
                        transform = adaptiveTransform,
                        onTransformChange = { adaptiveTransform = it },
                        onSizeChanged = { adaptiveSize = it },
                        backgroundColor = adaptiveBackgroundColor
                    )
                    Spacer(Modifier.height(8.dp))
                    AdaptiveGuideLegend()
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { activePicker = PickerTarget.ADAPTIVE }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.tools_youtube_assets_select_adaptive_foreground))
                    }
                    Spacer(Modifier.height(10.dp))
                    NameField(
                        label = stringResource(R.string.tools_youtube_assets_adaptive_foreground_name),
                        value = foregroundName,
                        onChange = { foregroundName = it }
                    )
                    NameField(
                        label = stringResource(R.string.tools_youtube_assets_adaptive_background_name),
                        value = backgroundName,
                        onChange = { backgroundName = it }
                    )
                    OutlinedTextField(
                        value = adaptiveBackgroundHex,
                        onValueChange = { adaptiveBackgroundHex = it.uppercase(Locale.ROOT) },
                        label = { Text(stringResource(R.string.tools_youtube_assets_background_color_hex)) },
                        supportingText = { Text(stringResource(R.string.tools_youtube_assets_background_color_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(adaptiveBackgroundColor)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape)
                        )
                        Button(
                            onClick = { showColorPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.tools_youtube_assets_pick_color_wheel))
                        }
                    }
                }
            }
            item {
                AssetEditorCard(
                    title = stringResource(R.string.tools_youtube_assets_headers_section)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.tools_youtube_assets_sync_header_transforms),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = syncHeaderTransforms,
                            onCheckedChange = { enabled ->
                                syncHeaderTransforms = enabled
                                if (enabled) {
                                    darkTransform = lightTransform
                                }
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.tools_youtube_assets_light_header),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { updateLightTransform(ImageTransform()) }) {
                            Text(stringResource(R.string.tools_youtube_assets_reset_transform))
                        }
                        TextButton(onClick = { updateDarkTransform(lightTransform) }) {
                            Text(stringResource(R.string.tools_youtube_assets_copy_light_to_dark))
                        }
                        TextButton(onClick = { updateLightTransform(centered(lightTransform)) }) {
                            Text(stringResource(R.string.tools_youtube_assets_center_transform))
                        }
                        TextButton(onClick = {
                            lightSource = null
                            updateLightTransform(ImageTransform())
                        }) {
                            Text(stringResource(R.string.clear))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    PreviewHeader(
                        bitmap = lightBitmap,
                        transform = lightTransform,
                        onTransformChange = { updateLightTransform(it) },
                        onSizeChanged = { lightSize = it },
                        backgroundColor = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { activePicker = PickerTarget.LIGHT }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.tools_youtube_assets_select_light_header))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.tools_youtube_assets_dark_header),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { updateDarkTransform(ImageTransform()) }) {
                            Text(stringResource(R.string.tools_youtube_assets_reset_transform))
                        }
                        TextButton(onClick = { updateLightTransform(darkTransform) }) {
                            Text(stringResource(R.string.tools_youtube_assets_copy_dark_to_light))
                        }
                        TextButton(onClick = { updateDarkTransform(centered(darkTransform)) }) {
                            Text(stringResource(R.string.tools_youtube_assets_center_transform))
                        }
                        TextButton(onClick = {
                            darkSource = null
                            updateDarkTransform(ImageTransform())
                        }) {
                            Text(stringResource(R.string.clear))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    PreviewHeader(
                        bitmap = darkBitmap,
                        transform = darkTransform,
                        onTransformChange = { updateDarkTransform(it) },
                        onSizeChanged = { darkSize = it },
                        backgroundColor = Color(0xFF101216)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { activePicker = PickerTarget.DARK }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.tools_youtube_assets_select_dark_header))
                    }
                    Spacer(Modifier.height(10.dp))
                    NameField(
                        label = stringResource(R.string.tools_youtube_assets_light_header_name),
                        value = lightName,
                        onChange = { lightName = it }
                    )
                    NameField(
                        label = stringResource(R.string.tools_youtube_assets_dark_header_name),
                        value = darkName,
                        onChange = { darkName = it }
                    )
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.tools_youtube_assets_presets_title), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = {
                                    foregroundName = "morphe_adaptive_foreground_custom"
                                    backgroundName = "morphe_adaptive_background_custom"
                                    lightName = "morphe_header_custom_light"
                                    darkName = "morphe_header_custom_dark"
                                },
                                label = { Text(stringResource(R.string.tools_youtube_assets_preset_morphe)) }
                            )
                            AssistChip(
                                onClick = {
                                    foregroundName = "revanced_adaptive_foreground_custom"
                                    backgroundName = "revanced_adaptive_background_custom"
                                    lightName = "revanced_header_custom_light"
                                    darkName = "revanced_header_custom_dark"
                                },
                                label = { Text(stringResource(R.string.tools_youtube_assets_preset_youtube)) }
                            )
                        }
                        Text(stringResource(R.string.tools_youtube_assets_output_info), style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = stringResource(R.string.tools_youtube_assets_generate_mode_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showGenerationModeMenu = !showGenerationModeMenu }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(generationMode.labelRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Outlined.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (showGenerationModeMenu) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                                    .padding(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                AssetGenerationMode.entries.forEach { mode ->
                                    val selected = mode == generationMode
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                generationMode = mode
                                                showGenerationModeMenu = false
                                            }
                                    ) {
                                        Text(
                                            text = stringResource(mode.labelRes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Button(onClick = ::generate, enabled = !generating, modifier = Modifier.fillMaxWidth()) {
                            if (generating) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = stringResource(
                                    R.string.tools_youtube_assets_generate_mode_button,
                                    stringResource(R.string.tools_youtube_assets_generate),
                                    stringResource(generationMode.labelRes)
                                )
                            )
                        }
                        generatedZip?.takeIf { it.exists() }?.let {
                            Text(
                                stringResource(R.string.tools_youtube_assets_generated_file, it.name),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            errorText?.takeIf { it.isNotBlank() }?.let { error ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Info, null)
                            Text(error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetEditorCard(
    title: String,
    onReset: (() -> Unit)? = null,
    onCenter: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                onReset?.let { reset ->
                    TextButton(onClick = reset) { Text(stringResource(R.string.tools_youtube_assets_reset_transform)) }
                }
                onCenter?.let { center ->
                    TextButton(onClick = center) { Text(stringResource(R.string.tools_youtube_assets_center_transform)) }
                }
                onClear?.let { clear ->
                    TextButton(onClick = clear) { Text(stringResource(R.string.clear)) }
                }
            }
            content()
        }
    }
}

@Composable
private fun NameField(
    label: String,
    value: String,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        suffix = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = ".png",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AdaptiveGuideLegend() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ComposeCanvas(modifier = Modifier.size(12.dp)) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.6f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                    )
                }
                Text(
                    text = stringResource(R.string.tools_youtube_assets_guide_safe_zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ComposeCanvas(modifier = Modifier.size(12.dp)) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.35f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                    )
                }
                Text(
                    text = stringResource(R.string.tools_youtube_assets_guide_mask_zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PreviewCircle(
    bitmap: Bitmap?,
    transform: ImageTransform,
    onTransformChange: (ImageTransform) -> Unit,
    onSizeChanged: (IntSize) -> Unit,
    backgroundColor: Color
) {
    val latestTransform by rememberUpdatedState(transform)
    val hasBitmap by rememberUpdatedState(bitmap != null)
    Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(210.dp)
                .clip(CircleShape)
                .background(backgroundColor)
                .onSizeChanged(onSizeChanged)
                .pointerInput(bitmap) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (!hasBitmap) return@detectTransformGestures
                        val current = latestTransform
                        onTransformChange(
                            current.copy(
                                scale = (current.scale * zoom).coerceIn(0.4f, 5f),
                                offsetX = current.offsetX + pan.x,
                                offsetY = current.offsetY + pan.y
                            )
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap == null) {
                Text(stringResource(R.string.tools_youtube_assets_no_image_selected), style = MaterialTheme.typography.bodySmall)
            } else {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.offsetX
                        translationY = transform.offsetY
                    }
                )
            }
            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(Color.White.copy(alpha = 0.35f), radius = size.minDimension * 0.24f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.2f), radius = size.minDimension * 0.38f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
        }
    }
}

@Composable
private fun PreviewHeader(
    bitmap: Bitmap?,
    transform: ImageTransform,
    onTransformChange: (ImageTransform) -> Unit,
    onSizeChanged: (IntSize) -> Unit,
    backgroundColor: Color
) {
    val latestTransform by rememberUpdatedState(transform)
    val hasBitmap by rememberUpdatedState(bitmap != null)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .onSizeChanged(onSizeChanged)
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (!hasBitmap) return@detectTransformGestures
                    val current = latestTransform
                    onTransformChange(
                        current.copy(
                            scale = (current.scale * zoom).coerceIn(0.4f, 5f),
                            offsetX = current.offsetX + pan.x,
                            offsetY = current.offsetY + pan.y
                        )
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) {
            Text(stringResource(R.string.tools_youtube_assets_no_image_selected), style = MaterialTheme.typography.bodySmall)
        } else {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationX = transform.offsetX
                    translationY = transform.offsetY
                }
            )
        }
    }
}

@Composable
private fun rememberLoadedBitmap(source: String?): androidx.compose.runtime.State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(initialValue = null, key1 = source) {
        value = if (source.isNullOrBlank()) null else withContext(Dispatchers.IO) { decodeBitmap(context, source) }
    }
}

private suspend fun decodeBitmap(context: android.content.Context, source: String): Bitmap? {
    val uri = Uri.parse(source)
    val model: Any = if (uri.scheme.equals("file", ignoreCase = true)) uri.path?.let(::File) ?: uri else uri
    val result = runCatching {
        context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(model)
                .allowHardware(false)
                .build()
        )
    }.getOrNull() as? SuccessResult ?: return null
    val drawable = result.drawable ?: return null
    return when (drawable) {
        is BitmapDrawable -> drawable.bitmap
        else -> runCatching { drawable.toBitmap() }.getOrNull()
    }
}

private data class SaveDialogState(
    val directory: Path,
    val fileName: String
)

private enum class PickerTarget {
    ADAPTIVE,
    LIGHT,
    DARK
}

private data class ImageTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
) {
    companion object {
        val saver: Saver<ImageTransform, List<Float>> = Saver(
            save = { listOf(it.scale, it.offsetX, it.offsetY) },
            restore = { ImageTransform(it.getOrElse(0) { 1f }, it.getOrElse(1) { 0f }, it.getOrElse(2) { 0f }) }
        )
    }
}

private data class Density(val folder: String, val width: Int, val height: Int)

private val adaptiveDensities = listOf(
    Density("mdpi", 108, 108),
    Density("hdpi", 162, 162),
    Density("xhdpi", 216, 216),
    Density("xxhdpi", 324, 324),
    Density("xxxhdpi", 432, 432)
)

private val headerDensities = listOf(
    Density("mdpi", 145, 54),
    Density("hdpi", 194, 72),
    Density("xhdpi", 258, 96),
    Density("xxhdpi", 387, 144),
    Density("xxxhdpi", 512, 192)
)

private enum class AssetGenerationMode(val labelRes: Int) {
    BOTH(R.string.tools_youtube_assets_generate_mode_both),
    ADAPTIVE_ONLY(R.string.tools_youtube_assets_generate_mode_adaptive),
    HEADER_ONLY(R.string.tools_youtube_assets_generate_mode_header)
}

private data class AssetRequest(
    val mode: AssetGenerationMode,
    val adaptiveForeground: Bitmap?,
    val adaptiveBackgroundArgb: Int,
    val headerLight: Bitmap?,
    val headerDark: Bitmap?,
    val adaptiveTransform: ImageTransform,
    val lightTransform: ImageTransform,
    val darkTransform: ImageTransform,
    val adaptiveSize: IntSize,
    val lightSize: IntSize,
    val darkSize: IntSize,
    val foregroundName: String,
    val backgroundName: String,
    val lightName: String,
    val darkName: String
)

private fun generateArchive(cacheDir: File, request: AssetRequest): File {
    cacheDir.mkdirs()
    val output = File(cacheDir, "youtube-assets-${System.currentTimeMillis()}.zip")
    ZipOutputStream(FileOutputStream(output)).use { zip ->
        if (request.mode != AssetGenerationMode.HEADER_ONLY) {
            val adaptive = requireNotNull(request.adaptiveForeground)
            adaptiveDensities.forEach { density ->
                val fg = renderBitmap(adaptive, density.width, density.height, request.adaptiveTransform, request.adaptiveSize)
                val bg = Bitmap.createBitmap(density.width, density.height, Bitmap.Config.ARGB_8888).apply { eraseColor(request.adaptiveBackgroundArgb) }
                putPng(zip, "adaptive-icon/mipmap-${density.folder}/${request.foregroundName}.png", fg)
                putPng(zip, "adaptive-icon/mipmap-${density.folder}/${request.backgroundName}.png", bg)
                fg.recycle()
                bg.recycle()
            }
        }
        if (request.mode != AssetGenerationMode.ADAPTIVE_ONLY) {
            val lightHeader = requireNotNull(request.headerLight)
            val darkHeader = requireNotNull(request.headerDark)
            headerDensities.forEach { density ->
                val light = renderBitmap(lightHeader, density.width, density.height, request.lightTransform, request.lightSize)
                val dark = renderBitmap(darkHeader, density.width, density.height, request.darkTransform, request.darkSize)
                putPng(zip, "header/drawable-${density.folder}/${request.lightName}.png", light)
                putPng(zip, "header/drawable-${density.folder}/${request.darkName}.png", dark)
                light.recycle()
                dark.recycle()
            }
        }
    }
    return output
}

private fun putPng(zip: ZipOutputStream, name: String, bitmap: Bitmap) {
    zip.putNextEntry(ZipEntry(name))
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
    zip.closeEntry()
}

private fun renderBitmap(
    source: Bitmap,
    targetWidth: Int,
    targetHeight: Int,
    transform: ImageTransform,
    previewSize: IntSize
): Bitmap {
    val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val previewW = previewSize.width.coerceAtLeast(1).toFloat()
    val previewH = previewSize.height.coerceAtLeast(1).toFloat()
    val base = max(targetWidth.toFloat() / source.width, targetHeight.toFloat() / source.height)
    val scale = base * transform.scale
    val dw = source.width * scale
    val dh = source.height * scale
    val tx = ((targetWidth - dw) / 2f) + (transform.offsetX * (targetWidth / previewW))
    val ty = ((targetHeight - dh) / 2f) + (transform.offsetY * (targetHeight / previewH))
    canvas.drawBitmap(source, null, RectF(tx, ty, tx + dw, ty + dh), paint)
    return output
}

private fun sanitizeName(input: String): String {
    val cleaned = input.trim().removeSuffix(".png").replace(Regex("[^A-Za-z0-9._-]"), "_")
    return cleaned.ifBlank { "custom_asset" }
}

private fun String.ensureZip(): String {
    val trimmed = trim().ifBlank { "youtube-assets.zip" }
    return if (trimmed.lowercase(Locale.ROOT).endsWith(".zip")) trimmed else "$trimmed.zip"
}

private fun cleanupOldGeneratedZips(cacheDir: File, keepCount: Int = 3) {
    val zipFiles = cacheDir.listFiles { file ->
        file.isFile && file.name.startsWith("youtube-assets-") && file.name.endsWith(".zip")
    }?.sortedByDescending { it.lastModified() } ?: return
    if (zipFiles.size <= keepCount) return
    zipFiles.drop(keepCount).forEach { stale -> runCatching { stale.delete() } }
}

@Composable
private fun AdaptiveColorWheelDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit
) {
    val hsv = remember(initialColor) {
        FloatArray(3).apply { AndroidColor.colorToHSV(initialColor.toArgb(), this) }
    }
    var hue by remember(initialColor) { mutableStateOf(hsv[0]) }
    var saturation by remember(initialColor) { mutableStateOf(hsv[1]) }
    var value by remember(initialColor) { mutableStateOf(hsv[2]) }
    val selectedColor = remember(hue, saturation, value) { Color.hsv(hue, saturation, value) }
    var hexInput by remember(initialColor) { mutableStateOf(colorToHexString(initialColor)) }
    var redInput by remember(initialColor) { mutableStateOf(AndroidColor.red(initialColor.toArgb()).toString()) }
    var greenInput by remember(initialColor) { mutableStateOf(AndroidColor.green(initialColor.toArgb()).toString()) }
    var blueInput by remember(initialColor) { mutableStateOf(AndroidColor.blue(initialColor.toArgb()).toString()) }

    fun setColor(updated: Color) {
        val updatedHsv = FloatArray(3).apply { AndroidColor.colorToHSV(updated.toArgb(), this) }
        hue = updatedHsv[0]
        saturation = updatedHsv[1]
        value = updatedHsv[2]
    }

    LaunchedEffect(selectedColor) {
        hexInput = colorToHexString(selectedColor)
        redInput = AndroidColor.red(selectedColor.toArgb()).toString()
        greenInput = AndroidColor.green(selectedColor.toArgb()).toString()
        blueInput = AndroidColor.blue(selectedColor.toArgb()).toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tools_youtube_assets_color_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.tools_youtube_assets_color_picker_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.tools_youtube_assets_color_hue),
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ColorSaturationValueSquare(
                        hue = hue,
                        saturation = saturation,
                        value = value,
                        onSaturationValueChange = { newSaturation, newValue ->
                            saturation = newSaturation
                            value = newValue
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        parseColorOrNull(hexInput)?.let { setColor(it) }
                    }) {
                        Text(stringResource(R.string.tools_youtube_assets_apply_hex))
                    }
                }
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { incoming ->
                        hexInput = incoming
                            .uppercase(Locale.ROOT)
                            .replace("#", "")
                            .filter { it in "0123456789ABCDEF" }
                            .take(8)
                    },
                    label = { Text(stringResource(R.string.tools_youtube_assets_background_color_hex)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        parseRgbColorOrNull(redInput, greenInput, blueInput)?.let { setColor(it) }
                    }) {
                        Text(stringResource(R.string.tools_youtube_assets_apply_rgb))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = redInput,
                        onValueChange = { redInput = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.color_channel_red)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = greenInput,
                        onValueChange = { greenInput = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.color_channel_green)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = blueInput,
                        onValueChange = { blueInput = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.color_channel_blue)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = stringResource(R.string.tools_youtube_assets_color_presets),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf(
                        Color(0xFFFFFFFF),
                        Color(0xFF000000),
                        Color(0xFFFF0000),
                        Color(0xFF00FF00),
                        Color(0xFF0000FF),
                        Color(0xFFB6E3FF)
                    ).forEach { preset ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(preset)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape)
                                .clickable { setColor(preset) }
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(selectedColor)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(selectedColor)
                onDismiss()
            }) {
                Text(stringResource(R.string.save))
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
private fun ColorSaturationValueSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onSaturationValueChange: (Float, Float) -> Unit
) {
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val hsvSquareBitmap = remember(hue, canvasSize) {
        val width = canvasSize.width.coerceAtLeast(1)
        val height = canvasSize.height.coerceAtLeast(1)
        val maxX = (width - 1).coerceAtLeast(1)
        val maxY = (height - 1).coerceAtLeast(1)
        val hueRgb = Color.hsv(hue, 1f, 1f)
        val hueR = hueRgb.red
        val hueG = hueRgb.green
        val hueB = hueRgb.blue
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val valueComponent = 1f - (y.toFloat() / maxY.toFloat())
            for (x in 0 until width) {
                val saturationComponent = x.toFloat() / maxX.toFloat()
                // Build the picker with explicit RGB blending to keep transitions linear in the square.
                val r = (1f + (hueR - 1f) * saturationComponent) * valueComponent
                val g = (1f + (hueG - 1f) * saturationComponent) * valueComponent
                val b = (1f + (hueB - 1f) * saturationComponent) * valueComponent
                pixels[y * width + x] = Color(
                    red = r.coerceIn(0f, 1f),
                    green = g.coerceIn(0f, 1f),
                    blue = b.coerceIn(0f, 1f),
                    alpha = 1f
                ).toArgb()
            }
        }
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val pickerCorner = 14.dp
    Box(
        modifier = Modifier
            .size(210.dp)
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                fun update(offset: Offset, size: IntSize) {
                    if (size.width <= 0 || size.height <= 0) return
                    val newSaturation = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val newValue = (1f - (offset.y / size.height.toFloat())).coerceIn(0f, 1f)
                    onSaturationValueChange(newSaturation, newValue)
                }

                detectTapGestures { update(it, this.size) }
            }
            .pointerInput(Unit) {
                detectDragGestures { pointerChange, _ ->
                    if (size.width <= 0 || size.height <= 0) return@detectDragGestures
                    val newSaturation = (pointerChange.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val newValue = (1f - (pointerChange.position.y / size.height.toFloat())).coerceIn(0f, 1f)
                    onSaturationValueChange(newSaturation, newValue)
                }
            }
    ) {
        ComposeCanvas(modifier = Modifier.fillMaxSize()) {
            val cornerRadius = CornerRadius(pickerCorner.toPx(), pickerCorner.toPx())
            val clipPath = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = size.height,
                        cornerRadius = cornerRadius
                    )
                )
            }

            clipPath(clipPath) {
                drawImage(
                    image = hsvSquareBitmap
                )
            }
            drawRoundRect(
                color = outlineColor,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = cornerRadius,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
            )

            val x = (saturation * size.width).coerceIn(0f, size.width)
            val y = ((1f - value) * size.height).coerceIn(0f, size.height)
            drawCircle(
                color = Color.Black.copy(alpha = 0.7f),
                radius = 8.dp.toPx(),
                center = Offset(x, y),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = 8.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}

private fun parseColor(raw: String, fallback: Color): Color {
    val cleaned = raw.trim().removePrefix("#")
    return runCatching {
        when (cleaned.length) {
            6 -> Color(android.graphics.Color.parseColor("#FF$cleaned"))
            8 -> Color(android.graphics.Color.parseColor("#$cleaned"))
            else -> fallback
        }
    }.getOrElse { fallback }
}

private fun parseColorOrNull(raw: String): Color? {
    val cleaned = raw.trim().removePrefix("#")
    return runCatching {
        when (cleaned.length) {
            6 -> Color(AndroidColor.parseColor("#FF$cleaned"))
            8 -> Color(AndroidColor.parseColor("#$cleaned"))
            else -> null
        }
    }.getOrNull()
}

private fun parseRgbColorOrNull(red: String, green: String, blue: String): Color? {
    val r = red.toIntOrNull()?.coerceIn(0, 255) ?: return null
    val g = green.toIntOrNull()?.coerceIn(0, 255) ?: return null
    val b = blue.toIntOrNull()?.coerceIn(0, 255) ?: return null
    return Color(AndroidColor.rgb(r, g, b))
}

private fun colorToHexString(color: Color): String =
    String.format(Locale.ROOT, "%08X", color.toArgb())
