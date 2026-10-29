package app.universal.patch.manager.patcher.runtime

import android.content.res.Resources
import android.os.Build
import java.util.Locale

/** Device facts used by the patcher on either ART or the embedded JVM. */
object RuntimeDevice {
    val sdk: Int get() = Build.VERSION.SDK_INT
    val fingerprint: String get() = Build.FINGERPRINT.orEmpty()
    val supportedAbis: List<String> get() = Build.SUPPORTED_ABIS.toList()
    val locales: List<Locale> get() = Resources.getSystem().configuration.locales.let { list ->
        (0 until list.size()).map { list[it] }
    }
    val densityDpi: Int get() = Resources.getSystem().displayMetrics.densityDpi
}
