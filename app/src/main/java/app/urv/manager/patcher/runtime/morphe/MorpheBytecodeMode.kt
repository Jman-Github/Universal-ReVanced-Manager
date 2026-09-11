package app.urv.manager.patcher.runtime.morphe

enum class MorpheBytecodeMode(
    val runtimeValue: String,
) {
    FAST("STRIP_FAST"),
    FULL("FULL");

    companion object {
        fun fromRuntimeValue(value: String?): MorpheBytecodeMode =
            entries.firstOrNull { it.runtimeValue.equals(value, ignoreCase = true) } ?: FAST
    }
}
