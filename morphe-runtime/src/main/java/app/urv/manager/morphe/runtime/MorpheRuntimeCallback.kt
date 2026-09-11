package app.urv.manager.morphe.runtime

interface MorpheRuntimeCallback {
    fun log(level: String, message: String)
    fun event(event: Map<String, Any?>)
    fun isCancelled(): Boolean
}
