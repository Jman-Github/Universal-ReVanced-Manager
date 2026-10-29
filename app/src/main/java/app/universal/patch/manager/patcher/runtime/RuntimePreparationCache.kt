package app.universal.patch.manager.patcher.runtime

/** Process-local preparation cache for immutable, app-bundled code only. */
internal class RuntimePreparationCache<T : Any> {
    private var key: String? = null
    private var value: T? = null

    @Synchronized
    fun get(key: String, isValid: (T) -> Boolean, prepare: () -> T): T {
        value?.takeIf { this.key == key && isValid(it) }?.let { return it }
        return prepare().also {
            this.key = key
            value = it
        }
    }
}
