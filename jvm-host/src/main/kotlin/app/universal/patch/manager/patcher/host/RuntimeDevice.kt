package app.universal.patch.manager.patcher.runtime

import java.util.Locale

/** Android sends these facts before a run; host OS properties must never drive APK preparation. */
object RuntimeDevice {
    var sdk: Int = 26
        private set
    var fingerprint: String = ""
        private set
    var supportedAbis: List<String> = emptyList()
        private set
    var locales: List<Locale> = emptyList()
        private set
    var densityDpi: Int = 160
        private set

    fun configure(values: Map<*, *>) {
        sdk = (values["sdk"] as Number).toInt()
        fingerprint = values["fingerprint"] as String
        supportedAbis = (values["abis"] as List<*>).map { it as String }
        locales = (values["locales"] as List<*>).map { Locale.forLanguageTag(it as String) }
        densityDpi = (values["density"] as Number).toInt()
    }
}
