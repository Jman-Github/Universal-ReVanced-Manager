package app.universal.patch.manager.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Bundle
import app.universal.patch.manager.R
import app.universal.patch.manager.receiver.BundleUpdateNotificationDismissReceiver

/** Exercise Android's real notification builder and manager, not android.jar stubs. */
object BundleNotificationProbe {
    @JvmStatic fun main(args: Array<String>) {
        android.os.Looper.prepareMainLooper()
        val thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
        val system = thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
        val shell = system.createPackageContext("com.android.shell", 0)
        val base = shell.resources
        val resources = object : Resources(base.assets, base.displayMetrics, base.configuration) {
            override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any): String =
                when (id) {
                    R.plurals.bundle_updates_notification_title_found_quantity -> "$quantity updates"
                    R.plurals.bundle_updates_notification_available_quantity -> "$quantity available"
                    else -> super.getQuantityString(id, quantity, *formatArgs)
                }
        }
        lateinit var manager: NotificationManager
        val context = object : ContextWrapper(shell) {
            override fun getResources() = resources
            override fun getOpPackageName() = packageName
            override fun getSystemService(name: String): Any? =
                if (name == Context.NOTIFICATION_SERVICE) manager else super.getSystemService(name)
        }
        val constructor = NotificationManager::class.java.declaredConstructors.first {
            it.parameterTypes.contentEquals(arrayOf(Context::class.java)) ||
                it.parameterTypes.contentEquals(arrayOf(Context::class.java, android.os.Handler::class.java))
        }.apply { isAccessible = true }
        manager = constructor.newInstance(*constructor.parameterTypes.map {
            if (it == Context::class.java) context else android.os.Handler(android.os.Looper.getMainLooper())
        }.toTypedArray()) as NotificationManager
        val channel = "upm-bundle-notification-probe"
        manager.createNotificationChannel(NotificationChannel(channel, "UPM regression probe", NotificationManager.IMPORTANCE_LOW))
        fun notification(vararg uids: Int): Notification {
            val style = Notification.InboxStyle().addLine("Updates available")
            uids.forEach { style.addLine("- Bundle $it v2") }
            return Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Updates")
                .setStyle(style)
                .addExtras(Bundle().apply {
                    putIntArray(EXTRA_MANUAL_BUNDLE_UIDS, uids)
                    putStringArray(BundleUpdateNotificationDismissReceiver.EXTRA_DISMISSAL_MARKERS,
                        uids.map { "$it:2" }.toTypedArray())
                }).build()
        }
        fun post(value: Notification) {
            manager.notify(MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID, value)
            repeat(50) {
                val posted = manager.activeNotifications.firstOrNull { it.id == MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID }?.notification
                if (posted != null &&
                    posted.extras.getIntArray(EXTRA_MANUAL_BUNDLE_UIDS)?.toList() == value.extras.getIntArray(EXTRA_MANUAL_BUNDLE_UIDS)?.toList() &&
                    posted.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ==
                        value.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }) return
                Thread.sleep(20)
            }
            error("Probe notification was not posted")
        }
        fun expectGone() {
            repeat(50) {
                if (manager.activeNotifications.none { it.id == MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID }) return
                Thread.sleep(20)
            }
            error("Updated bundle notification remained posted")
        }
        try {
            val original = notification(1, 2, 3)
            check(original.withoutUpdatedBundles(context, setOf(99)) === original)
            check(notification(1).withoutUpdatedBundles(context, setOf(1)) == null)
            check(original.withoutUpdatedBundles(context, setOf(1, 2, 3)) == null)
            val remaining = checkNotNull(original.withoutUpdatedBundles(context, setOf(1, 3)))
            check(remaining.extras.getIntArray(EXTRA_MANUAL_BUNDLE_UIDS)!!.contentEquals(intArrayOf(2)))
            check(remaining.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() } ==
                listOf("1 available", "- Bundle 2 v2"))
            check(remaining.extras.getStringArray(BundleUpdateNotificationDismissReceiver.EXTRA_DISMISSAL_MARKERS)!!
                .contentEquals(arrayOf("2:2")))
            check(remaining.contentIntent != null && remaining.deleteIntent != null)
            check(remaining.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "1 updates")
            println("PASS: unrelated, single, all and partial updates preserve the correct rows and dismissal markers")
            post(notification(1, 2))
            println("Manager match: ${context.getSystemService(NotificationManager::class.java) === manager}")
            manager.activeNotifications.forEach { println("Before: id=${it.id} tag=${it.tag} uids=${it.notification.extras.getIntArray(EXTRA_MANUAL_BUNDLE_UIDS)?.toList()} lines=${it.notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList()}") }
            removeUpdatedBundleNotifications(context, setOf(1))
            var pruned = false
            repeat(50) {
                val posted = manager.activeNotifications.firstOrNull { it.id == MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID }?.notification
                if (posted?.extras?.getIntArray(EXTRA_MANUAL_BUNDLE_UIDS)?.contentEquals(intArrayOf(2)) == true) pruned = true
                if (!pruned) Thread.sleep(20)
            }
            manager.activeNotifications.forEach { println("After: id=${it.id} tag=${it.tag} uids=${it.notification.extras.getIntArray(EXTRA_MANUAL_BUNDLE_UIDS)?.toList()} lines=${it.notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList()}") }
            check(pruned) { "Grouped notification did not drop the updated bundle" }
            removeUpdatedBundleNotifications(context, setOf(2))
            expectGone()
            removeUpdatedBundleNotifications(context, setOf(1))
            expectGone()
            val legacy = notification(1, 2).apply { extras.remove(EXTRA_MANUAL_BUNDLE_UIDS) }
            post(legacy)
            removeUpdatedBundleNotifications(context, setOf(1))
            Thread.sleep(100)
            val preserved = manager.activeNotifications.single { it.id == MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID }.notification
            check(preserved.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() } ==
                listOf("Updates available", "- Bundle 1 v2", "- Bundle 2 v2"))
            check(!preserved.extras.containsKey(EXTRA_MANUAL_BUNDLE_UIDS))
            // A subsequent worker check supplies IDs, making the same alert safe to prune.
            post(notification(1, 2))
            removeUpdatedBundleNotifications(context, setOf(1, 2))
            expectGone()
            println("PASS: Android clears identified updates, preserves unidentified legacy alerts and does not repost dismissed alerts")
        } finally {
            manager.cancel(MANUAL_BUNDLE_UPDATE_NOTIFICATION_ID)
            manager.deleteNotificationChannel(channel)
        }
    }
}
