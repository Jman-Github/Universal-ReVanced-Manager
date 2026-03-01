package app.revanced.manager.domain.repository

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Parcelable
import android.util.Log
import app.revanced.manager.data.room.AppDatabase
import app.revanced.manager.data.room.plugins.TrustedDownloaderPlugin
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.network.downloader.DownloaderPluginState
import app.revanced.manager.network.downloader.LoadedDownloaderPlugin
import app.revanced.manager.network.downloader.ParceledDownloaderData
import app.revanced.manager.plugin.downloader.DownloaderBuilder
import app.revanced.manager.plugin.downloader.GetScope as LegacyGetScope
import app.revanced.manager.plugin.downloader.OutputDownloadScope as LegacyOutputDownloadScope
import app.revanced.manager.plugin.downloader.PluginHostApi
import app.revanced.manager.plugin.downloader.Scope as LegacyScope
import app.revanced.manager.downloader.DownloaderBuilder as ModernDownloaderBuilder
import app.revanced.manager.downloader.DownloaderHostApi as ModernDownloaderHostApi
import app.revanced.manager.downloader.Scope as ModernScope
import app.revanced.manager.util.PM
import app.revanced.manager.util.tag
import dalvik.system.PathClassLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.lang.reflect.Modifier

@OptIn(PluginHostApi::class, ModernDownloaderHostApi::class)
class DownloaderPluginRepository(
    private val pm: PM,
    private val prefs: PreferencesManager,
    private val app: Application,
    db: AppDatabase
) {
    private val trustDao = db.trustedDownloaderPluginDao()
    private val _pluginStates = MutableStateFlow(emptyMap<String, DownloaderPluginState>())
    val pluginStates = _pluginStates.asStateFlow()
    val loadedPluginsFlow = pluginStates.map { states ->
        states.values
            .filterIsInstance<DownloaderPluginState.Loaded>()
            .flatMap { it.plugins }
    }

    private val acknowledgedDownloaderPlugins = prefs.acknowledgedDownloaderPlugins
    private val installedPluginPackageNames = MutableStateFlow(emptySet<String>())
    val newPluginPackageNames = combine(
        installedPluginPackageNames,
        acknowledgedDownloaderPlugins.flow
    ) { installed, acknowledged ->
        installed subtract acknowledged
    }

    suspend fun reload() {
        val plugins =
            withContext(Dispatchers.IO) {
                pm.getPackagesWithFeatures(setOf(LEGACY_PLUGIN_FEATURE, MODERN_PLUGIN_FEATURE))
                    .associate { it.packageName to loadPlugin(it.packageName) }
            }

        _pluginStates.value = plugins
        installedPluginPackageNames.value = plugins.keys

        val acknowledgedPlugins = acknowledgedDownloaderPlugins.get()
        val uninstalledPlugins = acknowledgedPlugins subtract installedPluginPackageNames.value
        if (uninstalledPlugins.isNotEmpty()) {
            Log.d(tag, "Uninstalled plugins: ${uninstalledPlugins.joinToString(", ")}")
            acknowledgedDownloaderPlugins.update(acknowledgedPlugins subtract uninstalledPlugins)
            trustDao.removeAll(uninstalledPlugins)
        }
    }

    fun unwrapParceledData(data: ParceledDownloaderData): Pair<LoadedDownloaderPlugin, Parcelable> {
        val loadedState = (_pluginStates.value[data.pluginPackageName] as? DownloaderPluginState.Loaded)
            ?: throw Exception("Downloader plugin with name ${data.pluginPackageName} is not available")

        val plugin = data.pluginClassName
            ?.let { className -> loadedState.plugins.firstOrNull { it.className == className } }
            ?: loadedState.plugins.firstOrNull()
            ?: throw Exception("No downloader implementation is available for ${data.pluginPackageName}")

        return plugin to data.unwrapWith(plugin)
    }

    private suspend fun loadPlugin(packageName: String): DownloaderPluginState {
        try {
            if (!verify(packageName)) return DownloaderPluginState.Untrusted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Got exception while verifying plugin $packageName", e)
            return DownloaderPluginState.Failed(e)
        }

        return try {
            val packageInfo = pm.getPackageInfo(packageName, flags = PackageManager.GET_META_DATA)!!
            val pluginContext = app.createPackageContext(packageName, 0)
            val classNames = resolveClassNames(packageInfo, pluginContext)
            val classLoader = PathClassLoader(packageInfo.applicationInfo!!.sourceDir, app.classLoader)
            val packageLabel = with(pm) { packageInfo.label() }

            val scopeImpl = object : LegacyScope, ModernScope {
                override val hostPackageName = app.packageName
                override val pluginPackageName = pluginContext.packageName
                override val downloaderPackageName = pluginContext.packageName
            }

            val loadedPlugins = classNames.map { className ->
                val downloader = classLoader
                    .loadClass(className)
                    .getDownloaderBuilder()
                    .buildDownloader(scopeImpl, pluginContext)
                val fallbackName = if (classNames.size > 1) {
                    className.substringAfterLast('.')
                } else {
                    packageLabel
                }

                LoadedDownloaderPlugin(
                    packageName = packageName,
                    className = className,
                    name = downloader.resolveName(pluginContext, fallbackName),
                    version = packageInfo.versionName ?: "0",
                    getImpl = downloader.resolveGet(),
                    downloadImpl = downloader.resolveDownload(),
                    classLoader = classLoader
                )
            }

            DownloaderPluginState.Loaded(
                plugins = loadedPlugins,
                classLoader = classLoader,
                name = packageLabel
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(tag, "Failed to load plugin $packageName", t)
            DownloaderPluginState.Failed(t)
        }
    }

    suspend fun trustPackage(packageName: String) {
        trustDao.upsertTrust(
            TrustedDownloaderPlugin(
                packageName,
                pm.getSignature(packageName).toByteArray()
            )
        )

        reload()
        prefs.edit {
            acknowledgedDownloaderPlugins += packageName
        }
    }

    suspend fun revokeTrustForPackage(packageName: String) =
        trustDao.remove(packageName).also { reload() }

    suspend fun acknowledgeAllNewPlugins() =
        acknowledgedDownloaderPlugins.update(installedPluginPackageNames.value)

    suspend fun removePlugin(packageName: String) {
        trustDao.remove(packageName)
        acknowledgedDownloaderPlugins.update(acknowledgedDownloaderPlugins.get() - packageName)
        _pluginStates.value = _pluginStates.value - packageName
        installedPluginPackageNames.value = installedPluginPackageNames.value - packageName
    }

    private suspend fun verify(packageName: String): Boolean {
        val expectedSignature = trustDao.getTrustedSignature(packageName) ?: return false
        return pm.hasSignature(packageName, expectedSignature)
    }

    private fun resolveClassNames(
        packageInfo: android.content.pm.PackageInfo,
        pluginContext: Context
    ): List<String> {
        val names = linkedSetOf<String>()
        val metaData = packageInfo.applicationInfo?.metaData

        fun addClassName(value: String?) {
            val normalized = value?.trim().orEmpty()
            if (normalized.isNotEmpty()) names += normalized
        }

        fun addClassArray(resourceId: Int) {
            if (resourceId == 0) return
            runCatching { pluginContext.resources.getStringArray(resourceId) }
                .getOrNull()
                ?.forEach(::addClassName)
        }

        addClassName(metaData?.getString(LEGACY_METADATA_PLUGIN_CLASS))
        addClassName(metaData?.getString(MODERN_METADATA_PLUGIN_CLASS))

        addClassArray(metaData?.getInt(LEGACY_METADATA_CLASSES_ARRAY, 0) ?: 0)
        addClassArray(metaData?.getInt(MODERN_METADATA_CLASSES_ARRAY, 0) ?: 0)

        addClassArray(findStringArrayResource(pluginContext, LEGACY_CLASSES_RESOURCE_NAME))
        addClassArray(findStringArrayResource(pluginContext, MODERN_CLASSES_RESOURCE_NAME))

        if (names.isEmpty()) {
            throw Exception(
                "Missing downloader class metadata. Expected one of " +
                    "$LEGACY_METADATA_PLUGIN_CLASS, $MODERN_METADATA_PLUGIN_CLASS, " +
                    "$LEGACY_CLASSES_RESOURCE_NAME, or $MODERN_CLASSES_RESOURCE_NAME"
            )
        }

        return names.toList()
    }

    private fun findStringArrayResource(context: Context, resourceName: String): Int =
        runCatching {
            @Suppress("DiscouragedApi")
            context.resources.getIdentifier(resourceName, "array", context.packageName)
        }.getOrDefault(0)

    private companion object {
        const val LEGACY_PLUGIN_FEATURE = "app.revanced.manager.plugin.downloader"
        const val MODERN_PLUGIN_FEATURE = "app.revanced.manager.downloader"

        const val LEGACY_METADATA_PLUGIN_CLASS = "app.revanced.manager.plugin.downloader.class"
        const val MODERN_METADATA_PLUGIN_CLASS = "app.revanced.manager.downloader.class"
        const val LEGACY_METADATA_CLASSES_ARRAY = "app.revanced.manager.plugin.downloader.classes"
        const val MODERN_METADATA_CLASSES_ARRAY = "app.revanced.manager.downloader.classes"
        const val LEGACY_CLASSES_RESOURCE_NAME = "app.revanced.manager.plugin.downloader.classes"
        const val MODERN_CLASSES_RESOURCE_NAME = "app.revanced.manager.downloader.classes"

        const val PUBLIC_STATIC = Modifier.PUBLIC or Modifier.STATIC
        val Int.isPublicStatic get() = (this and PUBLIC_STATIC) == PUBLIC_STATIC

        val Class<*>.isDownloaderBuilder get() =
            DownloaderBuilder::class.java.isAssignableFrom(this) ||
                ModernDownloaderBuilder::class.java.isAssignableFrom(this)

        @Suppress("UNCHECKED_CAST")
        fun Class<*>.getDownloaderBuilder(): Any =
            declaredMethods
                .firstOrNull {
                    it.modifiers.isPublicStatic &&
                        it.returnType.isDownloaderBuilder &&
                        it.parameterTypes.isEmpty()
                }
                ?.invoke(null)
                ?: throw Exception("Could not find a valid downloader implementation in class $canonicalName")

        fun Any.buildDownloader(scopeImpl: Any, context: Context): Any {
            val buildMethod = this::class.java.methods.firstOrNull {
                it.name == "build" && it.parameterTypes.size == 2
            } ?: throw Exception("Could not find build(scope, context) on ${this::class.java.canonicalName}")
            return buildMethod.invoke(this, scopeImpl, context)
                ?: throw Exception("Downloader build returned null for ${this::class.java.canonicalName}")
        }

        @Suppress("UNCHECKED_CAST")
        fun Any.resolveGet() =
            this::class.java.methods
                .firstOrNull { it.name == "getGet" && it.parameterCount == 0 }
                ?.invoke(this) as? (suspend LegacyGetScope.(String, String?) -> Pair<Parcelable, String?>?)
                ?: throw Exception("Downloader ${this::class.java.canonicalName} has no valid get function")

        @Suppress("UNCHECKED_CAST")
        fun Any.resolveDownload() =
            this::class.java.methods
                .firstOrNull { it.name == "getDownload" && it.parameterCount == 0 }
                ?.invoke(this) as? (suspend LegacyOutputDownloadScope.(Parcelable, OutputStream) -> Unit)
                ?: throw Exception("Downloader ${this::class.java.canonicalName} has no valid download function")

        fun Any.resolveName(context: Context, fallback: String): String {
            val resId = this::class.java.methods
                .firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                ?.invoke(this) as? Int
            if (resId == null || resId == 0) return fallback

            return runCatching { context.getString(resId) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: fallback
        }
    }
}
