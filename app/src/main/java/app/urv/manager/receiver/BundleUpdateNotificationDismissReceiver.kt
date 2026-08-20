package app.urv.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BundleUpdateNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BUNDLE_UPDATE_NOTIFICATION_DISMISSED) return
        markDismissedMarkers(context, dismissalMarkers(intent))
    }

    companion object {
        const val ACTION_BUNDLE_UPDATE_NOTIFICATION_DISMISSED =
            "app.urv.manager.action.BUNDLE_UPDATE_NOTIFICATION_DISMISSED"
        const val EXTRA_DISMISSAL_MARKERS = "dismissal_markers"
        private const val PREFS_FILE = "bundle_update_notifications"
        private const val KEY_DISMISSED_MANUAL_UPDATE_MARKERS = "dismissed_manual_update_markers"

        fun dismissedMarkers(context: Context): Set<String> =
            context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getStringSet(KEY_DISMISSED_MANUAL_UPDATE_MARKERS, emptySet())
                .orEmpty()
                .toSet()

        fun dismissalMarkers(intent: Intent?): Set<String> =
            intent?.getStringArrayExtra(EXTRA_DISMISSAL_MARKERS)
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()

        fun markDismissedMarkers(context: Context, markers: Collection<String>) {
            if (markers.isEmpty()) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            val updated = prefs.getStringSet(KEY_DISMISSED_MANUAL_UPDATE_MARKERS, emptySet())
                .orEmpty()
                .toMutableSet()
                .apply { addAll(markers) }
            prefs.edit()
                .putStringSet(KEY_DISMISSED_MANUAL_UPDATE_MARKERS, updated)
                .apply()
        }

        fun clearDismissedMarkers(context: Context, markers: Set<String>) {
            if (markers.isEmpty()) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            val updated = prefs.getStringSet(KEY_DISMISSED_MANUAL_UPDATE_MARKERS, emptySet())
                .orEmpty()
                .toMutableSet()
                .apply { removeAll(markers) }
            prefs.edit()
                .putStringSet(KEY_DISMISSED_MANUAL_UPDATE_MARKERS, updated)
                .apply()
        }
    }
}
