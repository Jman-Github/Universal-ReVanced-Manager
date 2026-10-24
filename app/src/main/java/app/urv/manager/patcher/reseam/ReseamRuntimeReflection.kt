package app.urv.manager.patcher.reseam

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Keep the runtime's actionable error visible to bundle imports and patching callers. */
internal fun Method.invokeReseamRuntime(vararg arguments: Any?): Any? = try {
    invoke(null, *arguments)
} catch (error: InvocationTargetException) {
    throw error.targetException
}
