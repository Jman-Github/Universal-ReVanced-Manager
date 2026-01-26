package app.revanced.manager.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SearchBarState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    textFieldState: TextFieldState,
    searchBarState: SearchBarState,
    onSearch: (String) -> Unit,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = SearchBarDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dividerColor = MaterialTheme.colorScheme.outline
    )
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(searchBarState.currentValue) {
        if (searchBarState.currentValue != SearchBarValue.Expanded) {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }
    val inputField = @Composable {
        SearchBarDefaults.InputField(
            modifier = Modifier.sizeIn(minWidth = 380.dp),
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = { query ->
                keyboardController?.hide()
                onSearch(query)
            },
            placeholder = placeholder,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            colors = colors.inputFieldColors
        )
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        SearchBar(
            modifier = Modifier
                .align(Alignment.Center)
                .statusBarsPadding(),
            state = searchBarState,
            inputField = inputField,
            colors = colors
        )
        ExpandedFullScreenSearchBar(
            state = searchBarState,
            inputField = inputField,
            colors = colors,
            content = content
        )
    }
}
