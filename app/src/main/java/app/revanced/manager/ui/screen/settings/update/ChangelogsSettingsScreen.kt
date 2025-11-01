package app.revanced.manager.ui.screen.settings.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.universal.revanced.manager.R
import app.revanced.manager.ui.component.AppTopBar
import app.revanced.manager.ui.component.ColumnWithScrollbar
import app.revanced.manager.ui.component.LoadingIndicator
import app.revanced.manager.ui.component.settings.Changelog
import app.revanced.manager.ui.viewmodel.ChangelogsViewModel
import app.revanced.manager.util.relativeTime
import org.koin.androidx.compose.koinViewModel
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogsSettingsScreen(
    onBackClick: () -> Unit,
    vm: ChangelogsViewModel = koinViewModel()
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.changelog),
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        ColumnWithScrollbar(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (vm.releaseInfo == null) Arrangement.Center else Arrangement.Top
        ) {
            vm.releaseInfo?.let { info ->
                val uriHandler = LocalUriHandler.current
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Changelog(
                        markdown = info.description.replace("`", ""),
                        version = info.version,
                        publishDate = info.createdAt.relativeTime(LocalContext.current)
                    )
                    info.pageUrl?.let { url ->
                        TextButton(onClick = { uriHandler.openUri(url) }) {
                            Text(stringResource(R.string.changelog))
                        }
                    }
                }
            } ?: LoadingIndicator()
        }
    }
}
