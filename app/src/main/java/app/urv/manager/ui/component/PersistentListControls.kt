package app.urv.manager.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Keep list controls accessible and give them an opaque background over scrolling rows. */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.persistentControls(
    key: Any,
    content: @Composable ColumnScope.() -> Unit
) {
    stickyHeader(key = key) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}
