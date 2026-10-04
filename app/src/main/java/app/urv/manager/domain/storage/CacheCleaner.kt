package app.urv.manager.domain.storage

import android.content.Context
import app.urv.manager.data.platform.DisposableFileCleanup
import app.urv.manager.util.managerStorageContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun clearManagerCache(context: Context): Long = withContext(Dispatchers.IO) {
    val detachedFiles = CacheCleanupGuard.runIfIdle {
        listOf(context.managerStorageContext.cacheDir, context.managerStorageContext.codeCacheDir)
            .plus(context.managerStorageContext.externalCacheDirs.filterNotNull())
            .flatMap { directory ->
                DisposableFileCleanup.detach(
                    directory.listFiles().orEmpty().filterNot {
                        it.name == "pr_profile" || it.name == "app_pr_profile"
                    }
                )
            }
    }.orEmpty()
    DisposableFileCleanup.deleteDetached(detachedFiles)
}
