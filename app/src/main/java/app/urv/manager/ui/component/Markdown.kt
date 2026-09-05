package app.urv.manager.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@Composable
fun Markdown(
    text: String,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    compactHeadings: Boolean = false
) {
    val markdown = text.trimIndent()
    val h1Style = if (compactHeadings) {
        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    } else {
        MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    }
    val h2Style = if (compactHeadings) {
        MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
    } else {
        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    }
    val h3Style = if (compactHeadings) {
        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
    } else {
        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    }

    Markdown(
        content = markdown,
        colors = markdownColor(
            text = MaterialTheme.colorScheme.onSurfaceVariant,
            codeBackground = MaterialTheme.colorScheme.secondaryContainer,
            codeText = MaterialTheme.colorScheme.onSecondaryContainer,
            linkText = MaterialTheme.colorScheme.primary
        ),
        typography = markdownTypography(
            h1 = h1Style,
            h2 = h2Style,
            h3 = h3Style,
            text = textStyle,
            list = textStyle
        )
    )
}
