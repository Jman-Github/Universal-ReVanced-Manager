package app.urv.manager.reseam.runtime

/** Use the existing expandable split step for extraction, inspection and each retained component. */
internal class ReseamSplitProgress(private val callback: ReseamRuntimeCallback) {
    private val step = mapOf("kind" to "PrepareSplitApk")
    private val phases = listOf("Extracting split APKs", "Inspecting split APKs")
    private val preparation = "Preparing split APKs"

    fun start() = callback.event(mapOf("type" to "Started", "stepId" to step, "subSteps" to phases + preparation))

    fun extracting(copied: Long, total: Long) = report(phases[0], copied, total)

    fun inspecting() = report(phases[1], 0, 1)

    fun components(names: List<String>) = callback.event(mapOf(
        "type" to "Progress", "stepId" to step,
        "subSteps" to phases + names.map { "Preparing $it" }
    ))

    fun preparing(name: String, finished: Boolean) = report("Preparing $name", if (finished) 1 else 0, 1)

    fun completed() = callback.event(mapOf("type" to "Completed", "stepId" to step))

    private fun report(message: String, current: Long, total: Long) = callback.event(mapOf(
        "type" to "Progress", "stepId" to step, "message" to message,
        "current" to current, "total" to total
    ))
}
