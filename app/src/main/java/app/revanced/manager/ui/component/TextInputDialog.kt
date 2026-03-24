package app.revanced.manager.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import app.universal.revanced.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

@Composable
fun TextInputDialog(
    initial: String,
    title: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
    validator: (String) -> Boolean = String::isNotEmpty,
    singleLine: Boolean = true,
    minLines: Int = if (singleLine) 1 else 2,
    maxLines: Int = if (singleLine) 1 else 4,
) {
    val (value, setValue) = rememberSaveable(initial) {
        mutableStateOf(initial)
    }
    var valid by remember { mutableStateOf(false) }
    val validatorRef by rememberUpdatedState(validator)
    val onConfirmRef by rememberUpdatedState(onConfirm)
    val submitScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(value) {
        valid = withContext(Dispatchers.Default) {
            runCatching { validatorRef(value) }.getOrDefault(false)
        }
    }

    fun submit() {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        submitScope.launch {
            yield()
            onConfirmRef(value)
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = ::submit,
                enabled = valid
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = {
            Text(title)
        },
        text = {
            TextField(
                modifier = Modifier.fillMaxWidth(),
                value = value,
                onValueChange = setValue,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                keyboardOptions = KeyboardOptions(
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (valid) {
                            submit()
                        }
                    }
                )
            )
        }
    )
}
