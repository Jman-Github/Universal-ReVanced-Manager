package app.revanced.manager.plugin.downloader.webview

import android.content.Intent
import app.revanced.manager.plugin.downloader.DownloadUrl
import app.revanced.manager.plugin.downloader.Downloader
import app.revanced.manager.plugin.downloader.DownloaderScope
import app.revanced.manager.plugin.downloader.GetScope
import app.revanced.manager.plugin.downloader.PluginHostApi
import app.revanced.manager.plugin.downloader.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlin.properties.Delegates

typealias InitialUrl = String
typealias PageLoadCallback<T> = suspend WebViewCallbackScope<T>.(url: String) -> Unit
typealias DownloadCallback<T> = suspend WebViewCallbackScope<T>.(url: String, mimeType: String, userAgent: String) -> Unit

interface WebViewCallbackScope<T> : Scope {
    suspend fun finish(result: T)
    suspend fun load(url: String)
}

@OptIn(PluginHostApi::class)
class WebViewScope<T> internal constructor(
    coroutineScope: CoroutineScope,
    private val scopeImpl: Scope,
    setResult: (T) -> Unit
) : Scope by scopeImpl {
    private var onPageLoadCallback: PageLoadCallback<T> = {}
    private var onDownloadCallback: DownloadCallback<T> = { _, _, _ -> }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private lateinit var webView: IWebView
    internal lateinit var initialUrl: String

    internal val binder = object : IWebViewEvents.Stub() {
        override fun ready(iface: IWebView?) {
            coroutineScope.launch(dispatcher) {
                webView = iface!!.also {
                    it.load(initialUrl)
                }
            }
        }

        override fun pageLoad(url: String?) {
            coroutineScope.launch(dispatcher) { onPageLoadCallback(callbackScope, url!!) }
        }

        override fun download(url: String?, mimetype: String?, userAgent: String?) {
            coroutineScope.launch(dispatcher) {
                onDownloadCallback(
                    callbackScope,
                    url!!,
                    mimetype!!,
                    userAgent!!
                )
            }
        }
    }

    private val callbackScope = object : WebViewCallbackScope<T>, Scope by scopeImpl {
        override suspend fun finish(result: T) {
            setResult(result)
            webView.let { withContext(Dispatchers.IO) { it.finish() } }
        }

        override suspend fun load(url: String) {
            webView.let { withContext(Dispatchers.IO) { it.load(url) } }
        }
    }

    fun download(block: DownloadCallback<T>) {
        onDownloadCallback = block
    }

    fun pageLoad(block: PageLoadCallback<T>) {
        onPageLoadCallback = block
    }
}

@JvmInline
private value class Container<U>(val value: U)

@OptIn(PluginHostApi::class)
suspend fun <T> GetScope.runWebView(
    title: String,
    block: suspend WebViewScope<T>.() -> InitialUrl
) = supervisorScope {
    var result by Delegates.notNull<Container<T>>()

    val scope = WebViewScope<T>(this@supervisorScope, this@runWebView) { result = Container(it) }
    scope.initialUrl = scope.block()

    requestStartActivity(Intent().apply {
        putExtra(
            WebViewActivity.KEY,
            WebViewActivity.Parameters(title, scope.binder)
        )
        setClassName(
            hostPackageName,
            WebViewActivity::class.qualifiedName!!
        )
    })

    coroutineContext.cancelChildren()
    result.value
}

fun WebViewDownloader(block: suspend WebViewScope<DownloadUrl>.(packageName: String, version: String?) -> InitialUrl?) =
    Downloader<DownloadUrl> {
        val label = context.applicationInfo.loadLabel(
            context.packageManager
        ).toString()

        get { packageName, version ->
            class ReturnNull : Exception()

            try {
                runWebView(label) {
                    download { url, _, userAgent ->
                        finish(
                            DownloadUrl(
                                url,
                                mapOf("User-Agent" to userAgent)
                            )
                        )
                    }

                    block(this@runWebView, packageName, version) ?: throw ReturnNull()
                } to version
            } catch (_: ReturnNull) {
                null
            }
        }

        download {
            it.toDownloadResult()
        }
    }
