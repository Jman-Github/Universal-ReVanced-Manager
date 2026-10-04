package app.urv.manager.ui.screen

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.children
import app.urv.manager.ui.component.AppTopBar
import app.urv.manager.ui.model.navigation.Announcement
import app.urv.manager.util.relativeTime
import app.universal.revanced.manager.R
import kotlinx.datetime.Instant
import org.intellij.lang.annotations.Language

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementScreen(
    onBackClick: () -> Unit,
    announcement: Announcement.Payload
) {
    val context = LocalContext.current
    val createdAt = runCatching { Instant.parse(announcement.createdAt) }.getOrNull()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = {},
                onBackClick = onBackClick
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = announcement.title,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = buildString {
                    append(createdAt?.relativeTime(context) ?: context.getString(R.string.invalid_date))
                    append(" • ")
                    append(announcement.author)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnnouncementTags(
                tags = announcement.tags,
                modifier = Modifier.fillMaxWidth()
            )
            AnnouncementWebView(
                modifier = Modifier
                    .fillMaxWidth(),
                content = announcement.content.sanitizedAnnouncementContent()
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AnnouncementWebView(
    modifier: Modifier = Modifier,
    content: String
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    var contentHeightPx by remember(content) { mutableIntStateOf(1) }
    var pendingHeightPx by remember(content) { mutableIntStateOf(0) }

    LaunchedEffect(pendingHeightPx) {
        if (pendingHeightPx > 0) {
            contentHeightPx = pendingHeightPx
        }
    }

    AndroidView(
        modifier = modifier.height(with(density) { contentHeightPx.toDp() }),
        factory = { context ->
            val webView = WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                isLongClickable = false
                setOnLongClickListener { true }
                isHapticFeedbackEnabled = false
                @SuppressLint("ClickableViewAccessibility")
                setOnTouchListener { _, event ->
                    event.action == MotionEvent.ACTION_MOVE
                }
                settings.javaScriptEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view ?: return
                        view.post {
                            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                                view.width.coerceAtLeast(1),
                                View.MeasureSpec.EXACTLY
                            )
                            val heightSpec = View.MeasureSpec.makeMeasureSpec(
                                0,
                                View.MeasureSpec.UNSPECIFIED
                            )
                            view.measure(widthSpec, heightSpec)
                            val measuredHeight = view.measuredHeight.coerceAtLeast(1)
                            if (measuredHeight != pendingHeightPx) {
                                pendingHeightPx = measuredHeight
                            }
                        }
                        view.postDelayed({
                            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                                view.width.coerceAtLeast(1),
                                View.MeasureSpec.EXACTLY
                            )
                            val heightSpec = View.MeasureSpec.makeMeasureSpec(
                                0,
                                View.MeasureSpec.UNSPECIFIED
                            )
                            view.measure(widthSpec, heightSpec)
                            val delayedHeight = view.measuredHeight.coerceAtLeast(1)
                            if (delayedHeight != pendingHeightPx) {
                                pendingHeightPx = delayedHeight
                            }
                        }, 200)
                    }
                }
            }
            FrameLayout(context).apply {
                webView.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                addView(webView)
            }
        },
        update = { container ->
            val webView = container.children.first() as WebView
            @Language("HTML")
            val html = """
                <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1" />
                        <style>
                            body {
                                margin: 0;
                                padding: 0;
                                color: ${textColor.toCss()};
                                background: transparent;
                                font-size: 16px;
                                line-height: 1.6;
                                word-wrap: break-word;
                            }
                            img {
                                max-width: 100%;
                                height: auto;
                            }
                            a {
                                color: ${linkColor.toCss()};
                            }
                            pre, code {
                                white-space: pre-wrap;
                                word-break: break-word;
                            }
                        </style>
                    </head>
                    <body>
                        $content
                    </body>
                </html>
            """.trimIndent()
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        onRelease = { container ->
            val webView = container.children.first() as WebView
            webView.destroy()
        }
    )
}

private fun Color.toCss(): String = "rgba(${red * 255f}, ${green * 255f}, ${blue * 255f}, $alpha)"

private fun String.sanitizedAnnouncementContent(): String {
    val fullAnnouncementLinkPattern = Regex(
        pattern = """(?is)<(?:p|div|section)[^>]*>\s*(?:<a\b[^>]*>\s*read\s+(?:the\s+)?full\s+announcement\s*</a>|read\s+(?:the\s+)?full\s+announcement)\s*</(?:p|div|section)>|<a\b[^>]*>\s*read\s+(?:the\s+)?full\s+announcement\s*</a>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    val emptyBlockPattern = Regex(
        pattern = """(?is)<(?:p|div|section)[^>]*>\s*(?:&nbsp;|\s|<br\s*/?>)*</(?:p|div|section)>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    return replace(fullAnnouncementLinkPattern, "")
        .replace(emptyBlockPattern, "")
        .trim()
}
