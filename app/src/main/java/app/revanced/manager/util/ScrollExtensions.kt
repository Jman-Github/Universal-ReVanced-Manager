package app.revanced.manager.util

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput

val LocalPreventAccidentalTouching = staticCompositionLocalOf { true }

fun Modifier.consumeHorizontalScroll(scrollState: ScrollState): Modifier = composed {
    val preventAccidentalTouching = LocalPreventAccidentalTouching.current
    val scrollModifier = horizontalScroll(scrollState)

    if (!preventAccidentalTouching) {
        scrollModifier
    } else {
        scrollModifier.pointerInput(scrollState, preventAccidentalTouching) {
            detectDragGestures { change, dragAmount ->
                val deltaX = dragAmount.x
                if (deltaX != 0f) {
                    change.consumePositionChange()
                    scrollState.dispatchRawDelta(-deltaX)
                }
            }
        }
    }
}
