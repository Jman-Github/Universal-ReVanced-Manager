package app.revanced.manager.plugin.downloader

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.net.HttpURLConnection
import java.net.URI

@Parcelize
data class Package(val name: String, val version: String) : Parcelable

@Parcelize
data class DownloadUrl(val url: String, val headers: Map<String, String> = emptyMap()) : Parcelable {
    fun toDownloadResult(): DownloadResult = with(URI.create(url).toURL().openConnection() as HttpURLConnection) {
        useCaches = false
        allowUserInteraction = false
        headers.forEach(::setRequestProperty)

        connectTimeout = 10_000
        connect()

        inputStream to getHeaderField("Content-Length").toLong()
    }
}
