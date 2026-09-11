package app.urv.manager.ui.model

import android.os.Parcelable
import app.urv.manager.network.downloader.ParceledDownloaderData
import kotlinx.parcelize.Parcelize
import java.io.File

sealed interface SelectedApp : Parcelable {
    val packageName: String
    val version: String?
    val versionCode: Long?

    @Parcelize
    data class Download(
        override val packageName: String,
        override val version: String?,
        val data: ParceledDownloaderData,
        override val versionCode: Long? = null
    ) : SelectedApp

    @Parcelize
    data class Search(
        override val packageName: String,
        override val version: String?,
        override val versionCode: Long? = null
    ) : SelectedApp

    @Parcelize
    data class Local(
        override val packageName: String,
        override val version: String,
        val file: File,
        val temporary: Boolean,
        val resolved: Boolean = true,
        override val versionCode: Long? = null
    ) : SelectedApp

    @Parcelize
    data class Installed(
        override val packageName: String,
        override val version: String,
        override val versionCode: Long? = null
    ) : SelectedApp
}
