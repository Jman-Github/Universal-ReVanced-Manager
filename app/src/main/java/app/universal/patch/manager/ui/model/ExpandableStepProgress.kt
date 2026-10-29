package app.universal.patch.manager.ui.model

import app.universal.patch.manager.patcher.StepId

/** Aggregate child work instead of reusing counters from unrelated phases. */
internal fun expandableStepProgressFraction(stepId: StepId, details: List<StepDetail>): Double? {
    val visible = details.filterNot { it.skipped }
    if (visible.isEmpty()) return null
    if (stepId == StepId.PrepareSplitApk &&
        visible.any { it.title == "Extracting split APKs" } &&
        visible.any { it.title == "Inspecting split APKs" }
    ) {
        val extraction = visible.first { it.title == "Extracting split APKs" }.completionUnits().first
        val inspection = visible.first { it.title == "Inspecting split APKs" }.completionUnits().first
        val components = visible.filter { it.title.startsWith("Preparing ") }
        val prepared = components.map { it.completionUnits().first }.average().takeIf { it.isFinite() } ?: 0.0
        return (extraction + inspection + prepared) / 3.0
    }
    val units = visible.map { it.completionUnits() }
    return units.sumOf { it.first } / units.sumOf { it.second }.toDouble()
}

private fun StepDetail.completionUnits(): Pair<Double, Int> {
    val visibleChildren = children.filterNot { it.skipped }
    if (visibleChildren.isNotEmpty()) {
        val units = visibleChildren.map { it.completionUnits() }
        val total = units.sumOf { it.second }
        return (if (state == State.COMPLETED) total.toDouble() else units.sumOf { it.first }) to total
    }
    val fraction = when (state) {
        State.COMPLETED -> 1.0
        State.RUNNING -> progress?.let { (current, total) ->
            total?.takeIf { it > 0L }?.let { (current.toDouble() / it).coerceIn(0.0, 1.0) }
        } ?: 0.0
        else -> 0.0
    }
    return fraction to 1
}

internal fun isComponentCountProgress(title: String): Boolean =
    title == "Inspecting split APKs" || title == "Preparing split APKs" ||
        title == "Writing patched APKs" || title == "Preparing unsigned APKs" ||
        (title.startsWith("Preparing ") && title.endsWith(".apk", ignoreCase = true))
