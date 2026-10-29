package app.universal.patch.manager.patcher.reseam

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** LinkageError is not an Exception: report a failed import instead of killing its coroutine. */
internal inline fun <T> reseamRuntimeCall(block: () -> T): T = try {
    block()
} catch (error: LinkageError) {
    throw IOException(
        "Reseam runtime could not be initialized. Restart the manager and update UP Manager if the problem continues.",
        error
    )
}

/** Keep SDK errors and cancellation visible; contain failures of its JNI initializer. */
internal fun Method.invokeReseamRuntime(vararg arguments: Any?): Any? = reseamRuntimeCall {
    try {
        invoke(null, *arguments)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
