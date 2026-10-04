package app.urv.manager.util

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat

internal const val MANAGER_FIXED_LAUNCHER_SHORTCUTS = 1

internal fun savedAppLauncherShortcutCapacity(context: Context): Int =
    (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) -
        MANAGER_FIXED_LAUNCHER_SHORTCUTS).coerceAtLeast(0)
