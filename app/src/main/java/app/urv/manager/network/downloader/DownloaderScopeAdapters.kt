package app.urv.manager.network.downloader

import android.content.Intent
import app.urv.manager.downloader.GetScope as ModernGetScope
import app.urv.manager.downloader.OutputDownloadScope as ModernOutputDownloadScope
import app.urv.manager.plugin.downloader.GetScope as LegacyGetScope
import app.urv.manager.plugin.downloader.OutputDownloadScope as LegacyOutputDownloadScope
import app.revanced.manager.downloader.GetScope as RevancedModernGetScope
import app.revanced.manager.downloader.OutputDownloadScope as RevancedModernOutputDownloadScope
import app.revanced.manager.plugin.downloader.GetScope as RevancedLegacyGetScope
import app.revanced.manager.plugin.downloader.OutputDownloadScope as RevancedLegacyOutputDownloadScope

internal fun LegacyGetScope.asDualGetScope(): LegacyGetScope {
    val scope = this
    return if (scope is ModernGetScope && scope is RevancedLegacyGetScope && scope is RevancedModernGetScope) {
        scope
    } else {
        object : LegacyGetScope, ModernGetScope, RevancedLegacyGetScope, RevancedModernGetScope {
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
    return if (
        scope is ModernOutputDownloadScope &&
        scope is RevancedLegacyOutputDownloadScope &&
        scope is RevancedModernOutputDownloadScope
    ) {
        scope
    } else {
        object : LegacyOutputDownloadScope, ModernOutputDownloadScope, RevancedLegacyOutputDownloadScope, RevancedModernOutputDownloadScope {
            override val hostPackageName = scope.hostPackageName
            override val pluginPackageName = scope.pluginPackageName
            override val downloaderPackageName = scope.pluginPackageName

            override suspend fun reportSize(size: Long) = scope.reportSize(size)
        }
    }
}
