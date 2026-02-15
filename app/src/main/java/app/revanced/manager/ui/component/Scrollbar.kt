package app.revanced.manager.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gigamole.composescrollbars.Scrollbars
import com.gigamole.composescrollbars.ScrollbarsState
import com.gigamole.composescrollbars.config.ScrollbarsConfig
import com.gigamole.composescrollbars.config.ScrollbarsOrientation
import com.gigamole.composescrollbars.config.layercontenttype.ScrollbarsLayerContentType
import com.gigamole.composescrollbars.config.layersType.ScrollbarsLayersType
import com.gigamole.composescrollbars.config.layersType.thicknessType.ScrollbarsThicknessType
import com.gigamole.composescrollbars.config.visibilitytype.ScrollbarsVisibilityType
import com.gigamole.composescrollbars.scrolltype.ScrollbarsScrollType
import com.gigamole.composescrollbars.scrolltype.knobtype.ScrollbarsDynamicKnobType
import com.gigamole.composescrollbars.scrolltype.knobtype.ScrollbarsStaticKnobType

@Composable
fun Scrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    prominent: Boolean = false
) {
    Scrollbar(
        ScrollbarsScrollType.Scroll(
            knobType = ScrollbarsStaticKnobType.Auto(),
            state = scrollState
        ),
        modifier,
        prominent
    )
}

@Composable
fun Scrollbar(
    lazyListState: LazyListState,
    modifier: Modifier = Modifier,
    prominent: Boolean = false
) {
    Scrollbar(
        ScrollbarsScrollType.Lazy.List.Dynamic(
            knobType = ScrollbarsDynamicKnobType.Auto(),
            state = lazyListState
        ),
        modifier,
        prominent
    )
}

@Composable
private fun Scrollbar(
    scrollType: ScrollbarsScrollType,
    modifier: Modifier = Modifier,
    prominent: Boolean = false
) {
    val thickness = if (prominent) 6.dp else 4.dp
    val idleAlpha = if (prominent) 0.6f else 0.35f
    val visibilityType = if (prominent) {
        ScrollbarsVisibilityType.Dynamic.Fade(
            isVisibleOnTouchDown = true,
            isStaticWhenScrollPossible = true
        )
    } else {
        ScrollbarsVisibilityType.Dynamic.Fade(
            isVisibleOnTouchDown = true,
            isStaticWhenScrollPossible = false
        )
    }
    Scrollbars(
        state = ScrollbarsState(
            ScrollbarsConfig(
                orientation = ScrollbarsOrientation.Vertical,
                paddingValues = PaddingValues(0.dp),
                layersType = ScrollbarsLayersType.Wrap(ScrollbarsThicknessType.Exact(thickness)),
                knobLayerContentType = ScrollbarsLayerContentType.Default.Colored.Idle(
                    idleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = idleAlpha)
                ),
                visibilityType = visibilityType
            ),
            scrollType
        ),
        modifier = modifier
    )
}
