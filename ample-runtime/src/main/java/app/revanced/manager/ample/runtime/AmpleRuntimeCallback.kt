package app.revanced.manager.ample.runtime

interface AmpleRuntimeCallback {
    fun log(level: String, message: String)
    fun event(event: Map<String, Any?>)
}
