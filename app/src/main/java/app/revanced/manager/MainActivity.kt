package app.revanced.manager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.appcompat.app.AppCompatActivity
import app.revanced.manager.ui.model.navigation.AppSelector
import app.revanced.manager.ui.model.navigation.ComplexParameter
import app.revanced.manager.ui.model.navigation.Dashboard
import app.revanced.manager.ui.model.navigation.InstalledApplicationInfo
import app.revanced.manager.ui.model.navigation.Patcher
import app.revanced.manager.ui.model.navigation.PatchBundleDiscovery
import app.revanced.manager.ui.model.navigation.PatchBundleDiscoveryPatches
import app.revanced.manager.ui.model.navigation.SelectedApplicationInfo
import app.revanced.manager.ui.model.navigation.Settings
import app.revanced.manager.ui.model.navigation.Update
import app.revanced.manager.ui.model.SelectedApp
import app.revanced.manager.ui.screen.AppSelectorScreen
import app.revanced.manager.ui.screen.DashboardScreen
import app.revanced.manager.ui.screen.InstalledAppInfoScreen
import app.revanced.manager.ui.screen.PatcherScreen
import app.revanced.manager.ui.screen.PatchBundleDiscoveryScreen
import app.revanced.manager.ui.screen.PatchBundleDiscoveryPatchesScreen
import app.revanced.manager.ui.screen.PatchesSelectorScreen
import app.revanced.manager.ui.screen.RequiredOptionsScreen
import app.revanced.manager.ui.screen.SelectedAppInfoScreen
import app.revanced.manager.ui.screen.SettingsScreen
import app.revanced.manager.ui.screen.UpdateScreen
import app.revanced.manager.ui.screen.settings.AboutSettingsScreen
import app.revanced.manager.ui.screen.settings.AdvancedSettingsScreen
import app.revanced.manager.ui.screen.settings.ContributorSettingsScreen
import app.revanced.manager.ui.screen.settings.DeveloperSettingsScreen
import app.revanced.manager.ui.screen.settings.DownloadsSettingsScreen
import app.revanced.manager.ui.screen.settings.GeneralSettingsScreen
import app.revanced.manager.ui.screen.settings.ImportExportSettingsScreen
import app.revanced.manager.ui.screen.settings.update.ChangelogsSettingsScreen
import app.revanced.manager.ui.screen.settings.update.UpdatesSettingsScreen
import app.revanced.manager.ui.theme.ReVancedManagerTheme
import app.revanced.manager.ui.theme.Theme
import app.revanced.manager.ui.viewmodel.MainViewModel
import app.revanced.manager.ui.viewmodel.SelectedAppInfoViewModel
import app.revanced.manager.util.EventEffect
import app.revanced.manager.util.AppForeground
import app.universal.revanced.manager.R
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.compose.navigation.koinNavViewModel
import org.koin.core.parameter.parametersOf
import org.koin.androidx.viewmodel.ext.android.getViewModel as getActivityViewModel
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    @ExperimentalAnimationApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        installSplashScreen()

        val vm: MainViewModel = getActivityViewModel()
        vm.handleIntent(intent)

        setContent {
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
                onResult = vm::applyLegacySettings
            )
            val theme by vm.prefs.theme.getAsState()
            val dynamicColor by vm.prefs.dynamicColor.getAsState()
            val pureBlackTheme by vm.prefs.pureBlackTheme.getAsState()
            val pureBlackOnSystemDark by vm.prefs.pureBlackOnSystemDark.getAsState()
            val customAccentColor by vm.prefs.customAccentColor.getAsState()
            val customThemeColor by vm.prefs.customThemeColor.getAsState()
            val customBackgroundImageUri by vm.prefs.customBackgroundImageUri.getAsState()
            val systemDark = isSystemInDarkTheme()
            val darkThemeEnabled = theme == Theme.SYSTEM && systemDark || theme == Theme.DARK
            val pureBlackEnabled = pureBlackTheme || (pureBlackOnSystemDark && theme == Theme.SYSTEM && systemDark)

            EventEffect(vm.legacyImportActivityFlow) {
                try {
                    launcher.launch(it)
                } catch (_: ActivityNotFoundException) {
                }
            }

            ReVancedManagerTheme(
                darkTheme = darkThemeEnabled,
                dynamicColor = dynamicColor,
                pureBlackTheme = pureBlackEnabled,
                accentColorHex = customAccentColor.takeUnless { it.isBlank() },
                themeColorHex = customThemeColor.takeUnless { it.isBlank() },
                hasCustomBackground = !customBackgroundImageUri.isNullOrBlank()
            ) {
                ReVancedManagerBackground(customBackgroundImageUri.takeUnless { it.isBlank() }) {
                    ReVancedManager(
                        vm = vm,
                        disableScreenSlideTransitions = !customBackgroundImageUri.isNullOrBlank()
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val vm: MainViewModel = getActivityViewModel()
        vm.handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        AppForeground.onWindowFocusChanged(hasFocus)
    }
}

@Composable
private fun ReVancedManagerBackground(
    customBackgroundImageUri: String?,
    content: @Composable () -> Unit
) {
    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
        if (!customBackgroundImageUri.isNullOrBlank()) {
            // Keep a single shared base/tint layer outside screen transitions.
            Box(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            )
            CustomBackgroundImage(
                customBackgroundImageUri = customBackgroundImageUri,
                modifier = androidx.compose.ui.Modifier.fillMaxSize()
            )
            Box(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.93f))
            )
        }
        content()
    }
}

@Composable
private fun CustomBackgroundImage(
    customBackgroundImageUri: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    val context = LocalContext.current
    val uri = remember(customBackgroundImageUri) { Uri.parse(customBackgroundImageUri) }
    val isFileUri = remember(uri) { uri.scheme.equals("file", ignoreCase = true) }
    val fileUriPath = remember(uri, isFileUri) { uri.path?.takeIf { isFileUri && it.isNotBlank() } }
    val asyncImageModel = remember(uri, fileUriPath) {
        fileUriPath?.let(::File) ?: uri
    }
    val mimeType = remember(uri) {
        runCatching { context.contentResolver.getType(uri) }
            .getOrNull()
            .orEmpty()
            .lowercase(Locale.ROOT)
    }
    val pathSegment = remember(uri) { uri.lastPathSegment.orEmpty().lowercase(Locale.ROOT) }
    val isTiff = mimeType == "image/tiff" || pathSegment.endsWith(".tif") || pathSegment.endsWith(".tiff")

    val tiffBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = uri, key2 = isTiff) {
        if (!isTiff) {
            value = null
            return@produceState
        }

        value = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    fileUriPath?.let { ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(it))) }
                        ?: ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
                } else {
                    fileUriPath?.let(BitmapFactory::decodeFile)
                        ?: context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                }
                bitmap?.asImageBitmap()
            }.getOrNull()
        }
    }

    if (isTiff && tiffBitmap != null) {
        Image(
            bitmap = tiffBitmap!!,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        AsyncImage(
            model = asyncImageModel,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun ReVancedManager(
    vm: MainViewModel,
    disableScreenSlideTransitions: Boolean
) {
    val navController = rememberNavController()
    var pendingBundleDeepLink by remember { mutableStateOf<app.revanced.manager.util.BundleDeepLink?>(null) }
    val context = LocalContext.current

    EventEffect(vm.appSelectFlow) { params ->
        navController.popBackStack(SelectedApplicationInfo.Main, inclusive = true)
        if (params.returnToDashboard) {
            navController.popBackStack(Dashboard, inclusive = false)
        }
        navController.navigateComplex(
            SelectedApplicationInfo,
            params
        )
    }

    EventEffect(vm.bundleDeepLinkFlow) { deepLink ->
        pendingBundleDeepLink = deepLink
        navController.navigate(Dashboard) {
            launchSingleTop = true
            popUpTo(Dashboard) { inclusive = false }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Dashboard,
        enterTransition = {
            if (disableScreenSlideTransitions) EnterTransition.None
            else slideInHorizontally(initialOffsetX = { it })
        },
        exitTransition = {
            if (disableScreenSlideTransitions) ExitTransition.None
            else slideOutHorizontally(targetOffsetX = { -it / 3 })
        },
        popEnterTransition = {
            if (disableScreenSlideTransitions) EnterTransition.None
            else slideInHorizontally(initialOffsetX = { -it / 3 })
        },
        popExitTransition = {
            if (disableScreenSlideTransitions) ExitTransition.None
            else slideOutHorizontally(targetOffsetX = { it })
        },
    ) {
        composable<Dashboard> {
            DashboardScreen(
                mainVm = vm,
                onSettingsClick = { navController.navigate(Settings) },
                onAppSelectorClick = {
                    navController.navigate(AppSelector())
                },
                onStorageSelect = { saved -> vm.selectApp(saved) },
                onUpdateClick = {
                    navController.navigate(Update())
                },
                onDownloaderPluginClick = {
                    navController.navigate(Settings.Downloads)
                },
                onBundleDiscoveryClick = {
                    navController.navigate(PatchBundleDiscovery)
                },
                onAppClick = { packageName, action ->
                    navController.navigate(InstalledApplicationInfo(packageName, action))
                },
                onProfileLaunch = { launchData ->
                    val apkFile = launchData.profile.apkPath
                        ?.let(::File)
                        ?.takeIf { it.exists() }
                    val resolvedVersion = launchData.profile.apkVersion
                        ?: launchData.profile.appVersion
                        ?: context.getString(R.string.app_version_unspecified)
                    val selectedApp = if (apkFile != null) {
                        SelectedApp.Local(
                            packageName = launchData.profile.packageName,
                            version = resolvedVersion,
                            file = apkFile,
                            temporary = false,
                            resolved = true
                        )
                    } else {
                        SelectedApp.Search(
                            launchData.profile.packageName,
                            launchData.profile.appVersion
                        )
                    }
                    navController.navigateComplex(
                        SelectedApplicationInfo,
                        SelectedApplicationInfo.ViewModelParams(
                            app = selectedApp,
                            patches = null,
                            profileId = launchData.profile.uid,
                            requiresSourceSelection = apkFile == null
                        )
                    )
                },
                bundleDeepLink = pendingBundleDeepLink,
                onBundleDeepLinkConsumed = { pendingBundleDeepLink = null }
            )
        }

        composable<InstalledApplicationInfo> {
            val data = it.toRoute<InstalledApplicationInfo>()

            InstalledAppInfoScreen(
                onPatchClick = { packageName, selection, selectionPayload, persistConfiguration ->
                    vm.selectApp(packageName, selection, selectionPayload, persistConfiguration)
                },
                onBackClick = navController::popBackStack,
                viewModel = koinViewModel { parametersOf(data.packageName) },
                initialAction = data.action
            )
        }

        composable<AppSelector> {
            val args = it.toRoute<AppSelector>()
            AppSelectorScreen(
                onSelect = vm::selectApp,
                onStorageSelect = vm::selectApp,
                onBackClick = navController::popBackStack,
                autoOpenStorage = args.autoStorage,
                returnToDashboardOnStorage = args.autoStorageReturn
            )
        }

        composable<Patcher> {
            val params = it.getComplexArg<Patcher.ViewModelParams>()
            PatcherScreen(
                onBackClick = navController::popBackStack,
                onReviewSelection = { app, selection, options, missing ->
                    val appWithVersion = when (app) {
                        is SelectedApp.Search -> app.copy(version = app.version ?: params.selectedApp.version)
                        is SelectedApp.Download -> if (app.version.isNullOrBlank()) app.copy(version = params.selectedApp.version) else app
                        else -> app
                    }
                    navController.navigateComplex(
                        SelectedApplicationInfo.PatchesSelector,
                        SelectedApplicationInfo.PatchesSelector.ViewModelParams(
                            app = appWithVersion,
                            currentSelection = selection,
                            options = options,
                            missingPatchNames = missing,
                            preferredAppVersion = app.version,
                            preferredBundleVersion = null,
                            preferredBundleUid = selection.keys.firstOrNull(),
                            preferredBundleOverride = null,
                            preferredBundleTargetsAllVersions = false
                        )
                    )
                },
                viewModel = koinViewModel { parametersOf(params) }
            )
        }

        composable<Update> {
            val data = it.toRoute<Update>()

            UpdateScreen(
                onBackClick = navController::popBackStack,
                vm = koinViewModel { parametersOf(data.downloadOnScreenEntry) }
            )
        }

        composable<PatchBundleDiscovery> {
            PatchBundleDiscoveryScreen(
                onBackClick = navController::popBackStack,
                onViewPatches = { bundleId ->
                    navController.navigate(PatchBundleDiscoveryPatches(bundleId))
                }
            )
        }

        composable<PatchBundleDiscoveryPatches> {
            val data = it.toRoute<PatchBundleDiscoveryPatches>()
            PatchBundleDiscoveryPatchesScreen(
                bundleId = data.bundleId,
                onBackClick = navController::popBackStack
            )
        }

        navigation<SelectedApplicationInfo>(startDestination = SelectedApplicationInfo.Main) {
            composable<SelectedApplicationInfo.Main> {
                val parentBackStackEntry = navController.navGraphEntry(it)
                val data =
                    parentBackStackEntry.getComplexArg<SelectedApplicationInfo.ViewModelParams>()
                val viewModel =
                    koinNavViewModel<SelectedAppInfoViewModel>(viewModelStoreOwner = parentBackStackEntry) {
                        parametersOf(data)
                    }

                SelectedAppInfoScreen(
                    onBackClick = navController::popBackStack,
                    onPatchClick = {
                        it.lifecycleScope.launch {
                            navController.navigateComplex(
                                Patcher,
                                viewModel.getPatcherParams()
                            )
                        }
                    },
                    onPatchSelectorClick = { app, patches, options ->
                        val versionHint = viewModel.selectedAppInfo?.versionName?.takeUnless { it.isNullOrBlank() }
                            ?: app.version?.takeUnless { it.isNullOrBlank() }
                            ?: viewModel.preferredBundleVersion?.takeUnless { it.isNullOrBlank() }
                            ?: viewModel.desiredVersion
                        val appWithVersion = when (app) {
                            is SelectedApp.Search -> app.copy(version = versionHint)
                            is SelectedApp.Download -> if (app.version.isNullOrBlank()) app.copy(version = versionHint) else app
                            else -> app
                        }
                        navController.navigateComplex(
                            SelectedApplicationInfo.PatchesSelector,
                            SelectedApplicationInfo.PatchesSelector.ViewModelParams(
                                appWithVersion,
                                patches,
                                options,
                                preferredAppVersion = versionHint,
                                preferredBundleVersion = viewModel.preferredBundleVersion,
                                preferredBundleUid = viewModel.selectedBundleUidFlow.value,
                                preferredBundleOverride = viewModel.selectedBundleVersionOverrideFlow.value,
                                preferredBundleTargetsAllVersions = viewModel.preferredBundleTargetsAllVersionsFlow.value
                            )
                        )
                    },
                    onRequiredOptions = { app, patches, options ->
                        val versionHint = viewModel.selectedAppInfo?.versionName?.takeUnless { it.isNullOrBlank() }
                            ?: app.version?.takeUnless { it.isNullOrBlank() }
                            ?: viewModel.preferredBundleVersion?.takeUnless { it.isNullOrBlank() }
                            ?: viewModel.desiredVersion
                        val appWithVersion = when (app) {
                            is SelectedApp.Search -> app.copy(version = versionHint)
                            is SelectedApp.Download -> if (app.version.isNullOrBlank()) app.copy(version = versionHint) else app
                            else -> app
                        }
                        navController.navigateComplex(
                            SelectedApplicationInfo.RequiredOptions,
                            SelectedApplicationInfo.PatchesSelector.ViewModelParams(
                                appWithVersion,
                                patches,
                                options,
                                preferredAppVersion = versionHint,
                                preferredBundleVersion = viewModel.preferredBundleVersion,
                                preferredBundleUid = viewModel.selectedBundleUidFlow.value,
                                preferredBundleOverride = viewModel.selectedBundleVersionOverrideFlow.value,
                                preferredBundleTargetsAllVersions = viewModel.preferredBundleTargetsAllVersionsFlow.value
                            )
                        )
                    },
                    vm = viewModel
                )
            }

            composable<SelectedApplicationInfo.PatchesSelector> {
                val data =
                    it.getComplexArg<SelectedApplicationInfo.PatchesSelector.ViewModelParams>()
                val parentEntry = navController.navGraphEntry(it)
                val parentArgs =
                    parentEntry.getComplexArg<SelectedApplicationInfo.ViewModelParams>()
                val selectedAppInfoVm = koinNavViewModel<SelectedAppInfoViewModel>(
                    viewModelStoreOwner = parentEntry
                ) {
                    parametersOf(parentArgs)
                }

                PatchesSelectorScreen(
                    onBackClick = navController::popBackStack,
                    onSave = { patches, options ->
                        selectedAppInfoVm.updateConfiguration(patches, options)
                        navController.popBackStack()
                    },
                    viewModel = koinViewModel { parametersOf(data) }
                )
            }

            composable<SelectedApplicationInfo.RequiredOptions> {
                val data =
                    it.getComplexArg<SelectedApplicationInfo.PatchesSelector.ViewModelParams>()
                val parentEntry = navController.navGraphEntry(it)
                val parentArgs =
                    parentEntry.getComplexArg<SelectedApplicationInfo.ViewModelParams>()
                val selectedAppInfoVm = koinNavViewModel<SelectedAppInfoViewModel>(
                    viewModelStoreOwner = parentEntry
                ) {
                    parametersOf(parentArgs)
                }

                RequiredOptionsScreen(
                    onBackClick = navController::popBackStack,
                    onContinue = { patches, options ->
                        selectedAppInfoVm.updateConfiguration(patches, options)
                        it.lifecycleScope.launch {
                            navController.navigateComplex(
                                Patcher,
                                selectedAppInfoVm.getPatcherParams()
                            )
                        }
                    },
                    vm = koinViewModel { parametersOf(data) }
                )
            }
        }

        navigation<Settings>(startDestination = Settings.Main) {
            composable<Settings.Main> {
                SettingsScreen(
                    onBackClick = navController::popBackStack,
                    navigate = navController::navigate
                )
            }

            composable<Settings.General> {
                GeneralSettingsScreen(onBackClick = navController::popBackStack)
            }

            composable<Settings.Advanced> {
                AdvancedSettingsScreen(onBackClick = navController::popBackStack)
            }

            composable<Settings.Developer> {
                DeveloperSettingsScreen(onBackClick = navController::popBackStack)
            }

            composable<Settings.Updates> {
                UpdatesSettingsScreen(
                    onBackClick = navController::popBackStack,
                    onChangelogClick = { navController.navigate(Settings.Changelogs) },
                    onUpdateClick = { navController.navigate(Update()) }
                )
            }

            composable<Settings.Downloads> {
                DownloadsSettingsScreen(onBackClick = navController::popBackStack)
            }

            composable<Settings.ImportExport> {
                ImportExportSettingsScreen(onBackClick = navController::popBackStack)
            }

            composable<Settings.About> {
                AboutSettingsScreen(
                    onBackClick = navController::popBackStack,
                    navigate = navController::navigate
                )
            }

            composable<Settings.Changelogs> {
                ChangelogsSettingsScreen(onBackClick = navController::popBackStack)
            }

            composable<Settings.Contributors> {
                ContributorSettingsScreen(onBackClick = navController::popBackStack)
            }


        }
    }
}

@Composable
private fun NavController.navGraphEntry(entry: NavBackStackEntry) =
    remember(entry) { getBackStackEntry(entry.destination.parent!!.id) }

// Androidx Navigation does not support storing complex types in route objects, so we have to store them inside the saved state handle of the back stack entry instead.
private fun <T : Parcelable, R : ComplexParameter<T>> NavController.navigateComplex(
    route: R,
    data: T
) {
    navigate(route)
    getBackStackEntry(route).savedStateHandle["args"] = data
}

private fun <T : Parcelable> NavBackStackEntry.getComplexArg() = savedStateHandle.get<T>("args")!!
