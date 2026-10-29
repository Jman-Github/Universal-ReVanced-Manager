package app.universal.patch.manager.util

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.universal.patch.manager.R
import app.universal.patch.manager.MainActivity
import app.universal.patch.manager.receiver.BundleUpdateNotificationDismissReceiver

internal const val MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID = 9101
internal const val EXTRA_MANUAL_BUNDLE_UIDS = "manual_bundle_uids"

/** Update only an existing alert; never recreate one the user has dismissed. */
internal fun removeUpdatedBundleNotifications(
    context: Context,
    updatedUids: Set<Int>
) {
    if (updatedUids.isEmpty()) return
    val manager = context.getSystemService(NotificationManager::class.java)
    val posted = manager.activeNotifications.firstOrNull {
        it.id == MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID && it.tag == null
    }?.notification ?: return
    // Older alerts have no per-row IDs. An empty in-memory update list after a
    // restart cannot prove they are resolved. The next background check replaces
    // these with an identified snapshot; until then, leave them untouched.
    val postedUids = posted.extras.getIntArray(EXTRA_MANUAL_BUNDLE_UIDS) ?: return
    if (postedUids.none { it in updatedUids }) return
    val remaining = posted.withoutUpdatedBundles(context, updatedUids)
    if (remaining == null) manager.cancel(MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID)
    else manager.notify(MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID, remaining)
}

internal fun Notification.withoutUpdatedBundles(context: Context, updatedUids: Set<Int>): Notification? {
    val uids = extras.getIntArray(EXTRA_MANUAL_BUNDLE_UIDS) ?: return this
    val retained = uids.indices.filter { uids[it] !in updatedUids }
    if (retained.size == uids.size) return this
    if (retained.isEmpty()) return null
    val oldLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().drop(1)
    if (oldLines.size != uids.size) return this
    val remainingUids = retained.map { uids[it] }.toIntArray()
    val lines = retained.map { oldLines[it] }
    val remainingUidSet = remainingUids.toSet()
    val markers = extras.getStringArray(BundleUpdateNotificationDismissReceiver.EXTRA_DISMISSAL_MARKERS)
        .orEmpty().filter { it.substringBefore(':').toIntOrNull() in remainingUidSet }.toTypedArray()
    val count = remainingUids.size
    val title = context.resources.getQuantityString(R.plurals.bundle_updates_notification_title_found_quantity, count, count)
    val header = context.resources.getQuantityString(R.plurals.bundle_updates_notification_available_quantity, count, count)
    val contentIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        BundleDeepLinkIntent.addBundleUid(this, remainingUids.singleOrNull())
        putExtra(BundleUpdateNotificationDismissReceiver.EXTRA_DISMISSAL_MARKERS, markers)
    }
    val deleteIntent = Intent(context, BundleUpdateNotificationDismissReceiver::class.java).apply {
        action = BundleUpdateNotificationDismissReceiver.ACTION_BUNDLE_UPDATE_NOTIFICATION_DISMISSED
        putExtra(BundleUpdateNotificationDismissReceiver.EXTRA_DISMISSAL_MARKERS, markers)
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val style = Notification.InboxStyle().setBigContentTitle(title).addLine(header)
    lines.forEach(style::addLine)
    return Notification.Builder.recoverBuilder(context, this)
        .setContentTitle(title)
        .setContentText((listOf(header) + lines).joinToString("\n"))
        .setStyle(style)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(context, MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID, contentIntent, flags))
        .setDeleteIntent(PendingIntent.getBroadcast(context, MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID, deleteIntent, flags))
        .addExtras(Bundle().apply {
            putIntArray(EXTRA_MANUAL_BUNDLE_UIDS, remainingUids)
            putStringArray(BundleUpdateNotificationDismissReceiver.EXTRA_DISMISSAL_MARKERS, markers)
        })
        .build()
}
