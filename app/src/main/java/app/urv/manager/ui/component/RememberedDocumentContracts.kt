package app.urv.manager.ui.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

private fun Uri.toPickerInitialUri(): Uri {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    if (authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) return this
    val documentId = runCatching { DocumentsContract.getDocumentId(this) }.getOrNull() ?: return this
    if (documentId != "primary:${Environment.DIRECTORY_DOWNLOADS}") return this
    return DocumentsContract.buildRootUri(DOWNLOADS_DOCUMENTS_AUTHORITY, DOWNLOADS_ROOT_ID)
}

private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
private const val DOWNLOADS_DOCUMENTS_AUTHORITY = "com.android.providers.downloads.documents"
private const val DOWNLOADS_ROOT_ID = "downloads"

private fun Intent.withInitialUri(initialUri: () -> Uri?): Intent = apply {
    initialUri()?.toPickerInitialUri()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
}

class RememberedGetContent(private val initialUri: () -> Uri?) :
    ActivityResultContract<String, Uri?>() {
    private val delegate = ActivityResultContracts.OpenDocument()
    override fun createIntent(context: Context, input: String): Intent =
        delegate.createIntent(context, arrayOf(input)).withInitialUri(initialUri)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        delegate.parseResult(resultCode, intent)
}

class RememberedOpenDocument(private val initialUri: () -> Uri?) :
    ActivityResultContract<Array<String>, Uri?>() {
    private val delegate = ActivityResultContracts.OpenDocument()
    override fun createIntent(context: Context, input: Array<String>): Intent =
        delegate.createIntent(context, input).withInitialUri(initialUri)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        delegate.parseResult(resultCode, intent)
}

class RememberedCreateDocument(mimeType: String, private val initialUri: () -> Uri?) :
    ActivityResultContract<String, Uri?>() {
    private val delegate = ActivityResultContracts.CreateDocument(mimeType)
    override fun createIntent(context: Context, input: String): Intent =
        delegate.createIntent(context, input).withInitialUri(initialUri)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        delegate.parseResult(resultCode, intent)
}

class RememberedOpenDocumentTree(private val initialUri: () -> Uri?) :
    ActivityResultContract<Uri?, Uri?>() {
    private val delegate = ActivityResultContracts.OpenDocumentTree()
    override fun createIntent(context: Context, input: Uri?): Intent =
        delegate.createIntent(context, input ?: initialUri())
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        delegate.parseResult(resultCode, intent)
}

fun Uri.toPickerDirectoryUri(): Uri {
    if (DocumentsContract.isTreeUri(this)) return this
    val id = runCatching { DocumentsContract.getDocumentId(this) }.getOrNull() ?: return this
    val separator = id.lastIndexOf('/')
    if (separator < 0) return this
    val parentId = id.substring(0, separator)
    return runCatching {
        DocumentsContract.buildDocumentUri(authority, parentId)
    }.getOrDefault(this)
}
