package app.urv.manager.network.downloader

import android.content.Intent
import app.urv.manager.downloader.GetScope as ModernGetScope
import app.urv.manager.downloader.OutputDownloadScope as ModernOutputDownloadScope
import app.urv.manager.plugin.downloader.GetScope as LegacyGetScope
import app.urv.manager.plugin.downloader.OutputDownloadScope as LegacyOutputDownloadScope

internal fun LegacyGetScope.asDualGetScope(): LegacyGetScope {
    val scope = this
    return if (scope is ModernGetScope) {
        scope
    } else {
        object : LegacyGetScope, ModernGetScope {
            override val hostPackageName = scope.hostPackageName
            override val pluginPackageName = scope.pluginPackageName
            override val downloaderPackageName = scope.pluginPackageName

            override suspend fun requestStartActivity(intent: Intent): Intent? =
                scope.requestStartActivity(intent)
        }
    }
}

internal fun LegacyOutputDownloadScope.asDualOutputScope(): LegacyOutputDownloadScope {
    val scope = this
    return if (scope is ModernOutputDownloadScope) {
        scope
    } else {
        object : LegacyOutputDownloadScope, ModernOutputDownloadScope {
            override val hostPackageName = scope.hostPackageName
            override val pluginPackageName = scope.pluginPackageName
            override val downloaderPackageName = scope.pluginPackageName

            override suspend fun reportSize(size: Long) = scope.reportSize(size)
        }
    }
}
