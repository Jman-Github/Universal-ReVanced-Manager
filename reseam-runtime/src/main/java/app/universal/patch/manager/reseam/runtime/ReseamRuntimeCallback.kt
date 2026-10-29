package app.universal.patch.manager.reseam.runtime

interface ReseamRuntimeCallback {
    fun log(level: String, message: String)
    fun event(event: Map<String, Any?>)
    fun isCancelled(): Boolean
}
