package app.revanced.manager.ui.component.bundle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.revanced.manager.domain.bundles.PatchBundleChangelogEntry
import app.revanced.manager.ui.component.ColumnWithScrollbar
import app.revanced.manager.ui.component.FullscreenDialog
import app.revanced.manager.ui.component.settings.Changelog
import app.revanced.manager.util.relativeTime
import app.universal.revanced.manager.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleChangelogHistoryDialog(
    entries: List<PatchBundleChangelogEntry>,
    onDismissRequest: () -> Unit,
) {
    FullscreenDialog(
        onDismissRequest = onDismissRequest,
    ) {
        Scaffold(
            topBar = {
                BundleTopBar(
                    title = stringResource(R.string.bundle_previous_changelogs),
                    onBackClick = onDismissRequest,
                    backIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                )
            }
        ) { paddingValues ->
            if (entries.isEmpty()) {
                BundleChangelogHistoryEmpty(paddingValues)
            } else {
                BundleChangelogHistoryContent(
                    paddingValues = paddingValues,
                    entries = entries
                )
            }
        }
    }
}

@Composable
private fun BundleChangelogHistoryEmpty(paddingValues: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.bundle_previous_changelogs_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun BundleChangelogHistoryContent(
    paddingValues: PaddingValues,
    entries: List<PatchBundleChangelogEntry>
) {
    val context = LocalContext.current

    ColumnWithScrollbar(
        modifier = Modifier
            .fillMaxWidth()
            .padding(paddingValues)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            entries.forEach { entry ->
                val publishDate = remember(entry.publishedAtMillis) {
                    entry.publishedAtMillis?.relativeTime(context)
                        ?: context.getString(R.string.invalid_date)
                }
                val markdown = remember(entry.description) {
                    entry.description
                        .replace("\r\n", "\n")
                        .sanitizePatchChangelogMarkdown()
                }

                Changelog(
                    markdown = if (markdown.isBlank()) {
                        stringResource(R.string.bundle_changelog_empty)
                    } else markdown,
                    version = entry.version,
                    publishDate = publishDate
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}
