package app.revanced.manager.revanced.runtime

interface RevancedRuntimeCallback {
    fun log(level: String, message: String)
    fun event(event: Map<String, Any?>)
}

