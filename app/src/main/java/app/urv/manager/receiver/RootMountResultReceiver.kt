package app.urv.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.urv.manager.domain.installer.root.RootMountFeedback
import app.urv.manager.domain.installer.root.bootRootMountFeedbackSuccess

class RootMountResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val packageName = intent.getStringExtra("package")
            ?.takeIf { it.length <= 255 && PACKAGE_NAME.matches(it) } ?: return
        val success = bootRootMountFeedbackSuccess(intent.getStringExtra("result")) ?: return
        if (intent.hasExtra("completed_at")) {
            val completedAt = intent.getLongExtra("completed_at", -1)
            if (completedAt < 0 || completedAt > SystemClock.elapsedRealtime()) return
            RootMountFeedback.show(context, packageName, success, completedAt)
        } else {
            RootMountFeedback.show(context, packageName, success)
        }
    }

    companion object {
        const val ACTION = "app.urv.manager.action.ROOT_MOUNT_RESULT"
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    }
}
