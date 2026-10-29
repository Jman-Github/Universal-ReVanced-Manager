package app.universal.patch.manager.revanced.runtime

interface RevancedRuntimeCallback {
    fun log(level: String, message: String)
    fun event(event: Map<String, Any?>)
    fun isCancelled(): Boolean
}

