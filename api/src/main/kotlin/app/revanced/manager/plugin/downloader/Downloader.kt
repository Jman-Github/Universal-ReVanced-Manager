package app.revanced.manager.plugin.downloader

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcelable
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is only intended for plugin hosts, don't use it in a plugin.",
)
@Retention(AnnotationRetention.BINARY)
annotation class PluginHostApi

interface Scope {
    val hostPackageName: String
    val pluginPackageName: String
}

interface GetScope : Scope {
    suspend fun requestStartActivity(intent: Intent): Intent?
}

interface BaseDownloadScope : Scope

interface InputDownloadScope : BaseDownloadScope

typealias Size = Long
typealias DownloadResult = Pair<InputStream, Size?>

typealias Version = String
typealias GetResult<T> = Pair<T, Version?>

class DownloaderScope<T : Parcelable> internal constructor(
    private val scopeImpl: Scope,
    internal val context: Context
) : Scope by scopeImpl {
    internal var download: (suspend OutputDownloadScope.(T, OutputStream) -> Unit)? = null
    internal var get: (suspend GetScope.(String, String?) -> GetResult<T>?)? = null
    private val inputDownloadScopeImpl = object : InputDownloadScope, Scope by scopeImpl {}

    fun download(block: suspend InputDownloadScope.(data: T) -> DownloadResult) {
        download = { app, outputStream ->
            val (inputStream, size) = inputDownloadScopeImpl.block(app)

            inputStream.use {
                if (size != null) reportSize(size)
                it.copyTo(outputStream)
            }
        }
    }

    fun get(block: suspend GetScope.(packageName: String, version: String?) -> GetResult<T>?) {
        get = block
    }

    suspend fun <R : Any?> useService(intent: Intent, block: suspend (IBinder) -> R): R {
        var onBind: ((IBinder) -> Unit)? = null
        val serviceConn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) =
                onBind!!(service!!)

            override fun onServiceDisconnected(name: ComponentName?) {}
        }

        return try {
            val binder = withTimeout(10000L) {
                suspendCoroutine { continuation ->
                    onBind = continuation::resume
                    context.bindService(intent, serviceConn, Context.BIND_AUTO_CREATE)
                }
            }
            block(binder)
        } finally {
            onBind = null
            context.unbindService(serviceConn)
        }
    }
}

class DownloaderBuilder<T : Parcelable> internal constructor(private val block: DownloaderScope<T>.() -> Unit) {
    @PluginHostApi
    fun build(scopeImpl: Scope, context: Context) =
        with(DownloaderScope<T>(scopeImpl, context)) {
            block()

            Downloader(
                download = download!!,
                get = get!!
            )
        }
}

class Downloader<T : Parcelable> internal constructor(
    @property:PluginHostApi val get: suspend GetScope.(packageName: String, version: String?) -> GetResult<T>?,
    @property:PluginHostApi val download: suspend OutputDownloadScope.(data: T, outputStream: OutputStream) -> Unit
)

fun <T : Parcelable> Downloader(block: DownloaderScope<T>.() -> Unit) = DownloaderBuilder(block)

sealed class UserInteractionException(message: String) : Exception(message) {
    class RequestDenied @PluginHostApi constructor() :
        UserInteractionException("Request denied by user")

    sealed class Activity(message: String) : UserInteractionException(message) {
        class Cancelled @PluginHostApi constructor() : Activity("Interaction cancelled")

        class NotCompleted @PluginHostApi constructor(val resultCode: Int, val intent: Intent?) :
            Activity("Unexpected activity result code: $resultCode")
    }
}
