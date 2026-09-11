package app.revanced.manager.downloader

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Parcelable
import java.io.OutputStream

interface OutputDownloadScope : BaseDownloadScope {
    suspend fun reportSize(size: Long)
}

fun <T : Parcelable> DownloaderScope<T>.download(
    block: suspend OutputDownloadScope.(T, OutputStream) -> Unit
) {
    download = block
}

suspend inline fun <reified ACTIVITY : Activity> GetScope.requestStartActivity() =
    requestStartActivity(
        Intent().apply { setClassName(downloaderPackageName, ACTIVITY::class.qualifiedName!!) }
    )

suspend inline fun <reified SERVICE : Service, R : Any?> DownloaderScope<*>.useService(
    noinline block: suspend (IBinder) -> R
) = useService(
    Intent().apply { setClassName(downloaderPackageName, SERVICE::class.qualifiedName!!) },
    block
)
