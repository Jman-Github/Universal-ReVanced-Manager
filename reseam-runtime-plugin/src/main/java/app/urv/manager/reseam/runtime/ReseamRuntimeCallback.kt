package app.urv.manager.reseam.runtime

interface ReseamRuntimeCallback {
    fun log(level: String, message: String)
    fun event(event: Map<String, Any?>)
    fun isCancelled(): Boolean
}
