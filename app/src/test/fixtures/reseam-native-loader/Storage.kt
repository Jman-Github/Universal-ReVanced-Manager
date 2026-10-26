package app.urv.manager.util

import android.content.Context

val Context.managerStorageContext: Context get() = applicationContext ?: this
