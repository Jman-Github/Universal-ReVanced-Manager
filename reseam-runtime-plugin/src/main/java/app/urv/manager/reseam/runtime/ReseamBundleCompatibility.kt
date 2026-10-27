package app.urv.manager.reseam.runtime

import app.reseam.sdk.Problem

internal fun reseamBundleProblemMessage(problem: Problem): String = when (problem) {
    is Problem.EngineTooOld ->
        "Reseam bundle '${problem.bundle}' requires engine ${problem.built}, " +
            "but the installed runtime uses ${problem.running}. Update the Reseam runtime plugin, then retry."
    is Problem.BundleTooOld ->
        "Reseam bundle '${problem.bundle}' was built for engine ${problem.built}, " +
            "but the installed runtime uses ${problem.running}. Update the patch bundle to a compatible release."
    else -> "Cannot load Reseam bundle: $problem"
}
