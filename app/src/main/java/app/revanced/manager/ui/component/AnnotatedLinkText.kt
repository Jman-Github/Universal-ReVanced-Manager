// From PR #37: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/37
package app.revanced.manager.ui.component

import android.content.Context
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import app.revanced.manager.util.openUrl

@Composable
fun AnnotatedLinkText(
    text: String,
    linkLabel: String,
    url: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant
    ),
    linkStyle: SpanStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        textDecoration = TextDecoration.Underline
    ),
    context: Context = LocalContext.current
) {
    val (clickableLabel, trailingLabel) = remember(linkLabel) { splitTrailingPunctuation(linkLabel) }
    val annotatedString = remember(text, clickableLabel, trailingLabel, url, linkStyle) {
        buildAnnotatedString {
            append(text)
            append(" ")

            pushStringAnnotation(tag = "URL", annotation = url)
            withStyle(linkStyle) {
                append(clickableLabel)
            }
            pop()
            append(trailingLabel)
        }
    }

    ClickableText(
        text = annotatedString,
        style = style,
        modifier = modifier,
        onClick = { offset ->
            annotatedString
                .getStringAnnotations("URL", offset, offset)
                .firstOrNull()
                ?.let { context.openUrl(it.item) }
        }
    )
}

private fun splitTrailingPunctuation(label: String): Pair<String, String> {
    if (label.isEmpty()) return label to ""

    val trimmedEnd = label.trimEnd()
    if (trimmedEnd.isEmpty()) return label to ""

    var splitIndex = trimmedEnd.length
    while (splitIndex > 0 && trimmedEnd[splitIndex - 1].isTrailingPunctuation()) {
        splitIndex--
    }

    if (splitIndex == trimmedEnd.length || splitIndex == 0) return label to ""

    val trailingWhitespace = label.substring(trimmedEnd.length)
    val clickable = trimmedEnd.substring(0, splitIndex)
    val trailing = trimmedEnd.substring(splitIndex) + trailingWhitespace
    return clickable to trailing
}

private fun Char.isTrailingPunctuation(): Boolean = when (this) {
    '.', ',', '!', '?', ':', ';', '…', '。', '،', '؛' -> true
    else -> false
}
