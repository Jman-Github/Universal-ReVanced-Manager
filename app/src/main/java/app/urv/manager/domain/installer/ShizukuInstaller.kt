package app.urv.manager.domain.installer

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.content.pm.IPackageInstaller
import android.content.pm.IPackageInstallerSession
import android.content.pm.IPackageManager
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import app.universal.revanced.manager.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.SystemServiceHelper
import rikka.sui.Sui
import java.io.File
import java.io.IOException
import java.lang.reflect.Constructor
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ShizukuInstaller(private val app: Application) {

    init {
        val isSui = Sui.init(app.packageName)
        if (!isSui) {
            runCatching { ShizukuProvider.requestBinderForNonProviderProcess(app) }
        }
    }

    data class OperationResult(val status: Int, val message: String?)

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/734
    fun availability(target: InstallerManager.InstallTarget): InstallerManager.Availability =
        status(target).availability

    fun isInstalled(): Boolean {
        if (isSuiMode()) return true
        return installedManagerPackageName() != null
    }

    fun installedManagerPackageName(): String? =
        MANAGER_PACKAGE_NAMES.firstOrNull(::isPackageInstalled)

    fun launchApp(): Boolean {
        MANAGER_PACKAGE_NAMES.forEach { packageName ->
            val intent = app.packageManager.getLaunchIntentForPackage(packageName)
                ?: return@forEach
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            return true
        }
        return false
    }

    suspend fun install(
        sourceFile: File,
        expectedPackage: String,
        installerPackageNameOverride: String? = null
    ): OperationResult =
        installMultiple(listOf(sourceFile), expectedPackage, installerPackageNameOverride)

    suspend fun installMultiple(
        sourceFiles: List<File>,
        expectedPackage: String?,
        installerPackageNameOverride: String? = null
    ): OperationResult = withContext(Dispatchers.IO) {
        if (sourceFiles.isEmpty()) {
            throw IllegalArgumentException("No APK files provided")
        }
        val packageInstaller = obtainPackageInstaller()
        val isRoot = runCatching { Shizuku.getUid() }.getOrDefault(-1) == 0
        val defaultInstallerPackageName = if (isRoot) app.packageName else SHELL_PACKAGE
        val installerPackageName = installerPackageNameOverride
            ?.takeIf { it.isNotBlank() }
            ?: defaultInstallerPackageName
        val installerAttributionTag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) app.attributionTag else null
        val userId = currentUserId()

        val packageInstallerWrapper = PackageInstallerCompat.createPackageInstaller(
            packageInstaller,
            installerPackageName,
            installerAttributionTag,
            userId,
            app
        )
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            expectedPackage?.takeIf { it.isNotBlank() }?.let { packageName ->
                runCatching { setAppPackageName(packageName) }
            }
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRequestUpdateOwnership(true)
            }
        }
        PackageInstallerCompat.applyFlags(params)

        val sessionId = packageInstallerWrapper.createSession(params)
        val sessionBinder = IPackageInstallerSession.Stub.asInterface(
            ShizukuBinderWrapper(packageInstaller.openSession(sessionId).asBinder())
        )
        val session = PackageInstallerCompat.createSession(sessionBinder)
        var committed = false

        try {
            sourceFiles.forEachIndexed { index, sourceFile ->
                val splitName = if (index == 0) BASE_APK_NAME else "split-$index.apk"
                sourceFile.inputStream().use { input ->
                    session.openWrite(splitName, 0, sourceFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
            }

            val resultDeferred = CompletableDeferred<OperationResult>()
            val intentSender = IntentSenderCompat.create { intent ->
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                resultDeferred.complete(OperationResult(status, message))
            }

            session.commit(intentSender)
            committed = true
            val result = try {
                withTimeout(INSTALL_RESULT_TIMEOUT) { resultDeferred.await() }
            } catch (_: TimeoutCancellationException) {
                runCatching { session.abandon() }
                throw InstallerOperationException(
                    PackageInstaller.STATUS_FAILURE_TIMEOUT,
                    "Timed out waiting for Shizuku install result"
                )
            } catch (cancelled: CancellationException) {
                runCatching { session.abandon() }
                throw cancelled
            }
            if (result.status != PackageInstaller.STATUS_SUCCESS) {
                throw InstallerOperationException(result.status, result.message)
            }
            result
        } finally {
            if (!committed) runCatching { session.abandon() }
            runCatching { session.close() }
        }
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/734
    suspend fun uninstall(packageName: String): OperationResult = withContext(Dispatchers.IO) {
        val packageInstaller = obtainPackageInstaller()
        val identity = installerIdentity()
        val wrapper = PackageInstallerCompat.createPackageInstaller(
            packageInstaller,
            identity.packageName,
            identity.attributionTag,
            identity.userId,
            app
        )
        val deferred = CompletableDeferred<OperationResult>()
        val intentSender = IntentSenderCompat.create { intent ->
            deferred.complete(
                OperationResult(
                    status = intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE
                    ),
                    message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                )
            )
        }

        wrapper.uninstall(packageName, intentSender)
        val result = try {
            withTimeout(UNINSTALL_RESULT_TIMEOUT) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            throw InstallerOperationException(
                PackageInstaller.STATUS_FAILURE_TIMEOUT,
                "Timed out waiting for Shizuku uninstall result"
            )
        }
        if (result.status != PackageInstaller.STATUS_SUCCESS) {
            throw InstallerOperationException(result.status, result.message)
        }
        result
    }

    // Code adapted from Morphe, see third-party/NOTICE for more information
    // https://github.com/MorpheApp/morphe-manager/pull/734
    fun status(
        @Suppress("UNUSED_PARAMETER") target: InstallerManager.InstallTarget
    ): Status {
        val sui = isSuiMode()
        val packageName = installedManagerPackageName()
        val installed = sui || packageName != null
        val supported = installed && !runCatching { Shizuku.isPreV11() }.getOrDefault(true)
        val running = supported && runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permissionGranted = running && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val availability = when {
            !installed -> InstallerManager.Availability(
                false,
                R.string.installer_status_shizuku_not_installed
            )
            !supported -> InstallerManager.Availability(
                false,
                R.string.installer_status_shizuku_unsupported
            )
            !running -> InstallerManager.Availability(
                false,
                R.string.installer_status_shizuku_not_running
            )
            !permissionGranted -> InstallerManager.Availability(
                false,
                R.string.installer_status_shizuku_permission
            )
            else -> InstallerManager.Availability(true)
        }
        return Status(
            installed = installed,
            supported = supported,
            running = running,
            permissionGranted = permissionGranted,
            mode = if (sui) Mode.SUI else Mode.SHIZUKU,
            packageName = packageName,
            availability = availability
        )
    }

    private fun obtainPackageInstaller(): IPackageInstaller {
        val binder = SystemServiceHelper.getSystemService("package")
            ?: throw IOException("Package service unavailable")
        try {
            val manager = IPackageManager.Stub.asInterface(ShizukuBinderWrapper(binder))
            val installer = manager.packageInstaller
            return IPackageInstaller.Stub.asInterface(ShizukuBinderWrapper(installer.asBinder()))
        } catch (error: RemoteException) {
            throw IOException(error)
        }
    }

    private fun currentUserId(): Int = androidUserIdForUid(Process.myUid())

    private fun isSuiMode(): Boolean = runCatching { Sui.isSui() }.getOrDefault(false)

    private fun installerIdentity(
        installerPackageNameOverride: String? = null
    ): InstallerIdentity {
        val root = runCatching { Shizuku.getUid() }.getOrDefault(-1) == 0
        return InstallerIdentity(
            packageName = installerPackageNameOverride
                ?.takeIf { it.isNotBlank() }
                ?: if (root) app.packageName else SHELL_PACKAGE,
            attributionTag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.attributionTag
            } else null,
            userId = currentUserId()
        )
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            app.packageManager.getPackageInfo(packageName, 0)
        }.isSuccess

    class InstallerOperationException(val status: Int, override val message: String?) : Exception(message)

    data class Status(
        val installed: Boolean,
        val supported: Boolean,
        val running: Boolean,
        val permissionGranted: Boolean,
        val mode: Mode,
        val packageName: String?,
        val availability: InstallerManager.Availability
    )

    enum class Mode { SHIZUKU, SUI }

    private data class InstallerIdentity(
        val packageName: String,
        val attributionTag: String?,
        val userId: Int
    )

    companion object {
        internal const val GOOGLE_PLAY_PACKAGE = "com.android.vending"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val BASE_APK_NAME = "base.apk"
        private val INSTALL_RESULT_TIMEOUT = 5.minutes
        private val UNINSTALL_RESULT_TIMEOUT = 30.seconds
        internal const val PACKAGE_NAME = "moe.shizuku.privileged.api"
        internal const val SHEVERY_PACKAGE_NAME = "com.hamondev.shevery"
        private val MANAGER_PACKAGE_NAMES = listOf(PACKAGE_NAME, SHEVERY_PACKAGE_NAME)
    }
}

internal fun androidUserIdForUid(uid: Int): Int = uid / 100_000

private object PackageInstallerCompat {
    private const val INSTALL_REPLACE_EXISTING = 0x00000002
    private const val INSTALL_ALLOW_TEST = 0x00000004

    fun createPackageInstaller(
        remote: IPackageInstaller,
        installerPackageName: String,
        installerAttributionTag: String?,
        userId: Int,
        app: Application
    ): PackageInstaller {
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    PackageInstaller::class.java
                        .getDeclaredConstructor(
                            IPackageInstaller::class.java,
                            String::class.java,
                            String::class.java,
                            Int::class.javaPrimitiveType
                        )
                        .apply { isAccessible = true }
                        .newInstance(remote, installerPackageName, installerAttributionTag, userId)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    PackageInstaller::class.java
                        .getDeclaredConstructor(
                            IPackageInstaller::class.java,
                            String::class.java,
                            Int::class.javaPrimitiveType
                        )
                        .apply { isAccessible = true }
                        .newInstance(remote, installerPackageName, userId)
                }
                else -> {
                    PackageInstaller::class.java
                        .getDeclaredConstructor(
                            android.content.Context::class.java,
                            PackageManager::class.java,
                            IPackageInstaller::class.java,
                            String::class.java,
                            Int::class.javaPrimitiveType
                        )
                        .apply { isAccessible = true }
                        .newInstance(app, app.packageManager, remote, installerPackageName, userId)
                }
            }
        } catch (error: ReflectiveOperationException) {
            throw RuntimeException(error)
        }
    }

    fun createSession(remote: IPackageInstallerSession): PackageInstaller.Session {
        return try {
            PackageInstaller.Session::class.java
                .getDeclaredConstructor(IPackageInstallerSession::class.java)
                .apply { isAccessible = true }
                .newInstance(remote)
        } catch (error: ReflectiveOperationException) {
            throw RuntimeException(error)
        }
    }

    fun applyFlags(params: PackageInstaller.SessionParams) {
        runCatching {
            val field = PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags")
            field.isAccessible = true
            val current = field.getInt(params)
            field.setInt(params, current or INSTALL_REPLACE_EXISTING or INSTALL_ALLOW_TEST)
        }
    }
}

private object IntentSenderCompat {
    fun create(callback: (Intent) -> Unit): IntentSender {
        val binder = object : android.content.IIntentSender.Stub() {
            override fun send(
                code: Int,
                intent: Intent?,
                resolvedType: String?,
                finishedReceiver: android.content.IIntentReceiver?,
                requiredPermission: String?,
                options: Bundle?
            ): Int {
                intent?.let(callback)
                return 0
            }

            override fun send(
                code: Int,
                intent: Intent?,
                resolvedType: String?,
                whitelistToken: IBinder?,
                finishedReceiver: android.content.IIntentReceiver?,
                requiredPermission: String?,
                options: Bundle?
            ) {
                intent?.let(callback)
            }
        }
        return try {
            val ctor: Constructor<IntentSender> = IntentSender::class.java.getDeclaredConstructor(android.content.IIntentSender::class.java)
            ctor.isAccessible = true
            ctor.newInstance(binder)
        } catch (error: ReflectiveOperationException) {
            throw RuntimeException(error)
        }
    }
}
