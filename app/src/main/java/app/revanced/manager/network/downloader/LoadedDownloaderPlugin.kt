package app.revanced.manager.network.downloader

import android.os.Parcelable
import app.revanced.manager.plugin.downloader.OutputDownloadScope
import app.revanced.manager.plugin.downloader.GetScope
import java.io.OutputStream

class LoadedDownloaderPlugin(
    val packageName: String,
    val className: String,
    val name: String,
    val version: String,
    private val getImpl: suspend GetScope.(packageName: String, version: String?) -> Pair<Parcelable, String?>?,
    private val downloadImpl: suspend OutputDownloadScope.(data: Parcelable, outputStream: OutputStream) -> Unit,
    val classLoader: ClassLoader
) {
    val id = "$packageName:$className"

    suspend fun get(scope: GetScope, packageName: String, version: String?) =
        getImpl(scope.asDualGetScope(), packageName, version)

    suspend fun download(scope: OutputDownloadScope, data: Parcelable, outputStream: OutputStream) =
        downloadImpl(scope.asDualOutputScope(), data, outputStream)
}
