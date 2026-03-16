package app.revanced.manager.ui.component.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.universal.revanced.manager.R
import app.revanced.manager.domain.manager.base.Preference
import app.revanced.manager.ui.component.IntInputDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun IntegerItem(
    modifier: Modifier = Modifier,
    preference: Preference<Int>,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    @StringRes headline: Int,
    @StringRes description: Int,
    supportingText: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    neutralButtonLabel: String? = null,
    neutralValueProvider: (() -> Int?)? = null,
    validator: (Int) -> Boolean = { true },
    enabled: Boolean = true
) {
    val value by preference.getAsState()

    IntegerItem(
        modifier = modifier,
        value = value,
        onValueChange = { coroutineScope.launch { preference.update(it) } },
        headline = headline,
        description = description,
        supportingText = supportingText,
        trailingContent = trailingContent,
        defaultValue = preference.default,
        neutralButtonLabel = neutralButtonLabel,
        neutralValueProvider = neutralValueProvider,
        validator = validator,
        enabled = enabled
    )
}

@Composable
fun IntegerItem(
    modifier: Modifier = Modifier,
    value: Int,
    onValueChange: (Int) -> Unit,
    @StringRes headline: Int,
    @StringRes description: Int,
    supportingText: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    defaultValue: Int? = null,
    neutralButtonLabel: String? = null,
    neutralValueProvider: (() -> Int?)? = null,
    validator: (Int) -> Boolean = { true },
    enabled: Boolean = true
) {
    var dialogOpen by rememberSaveable {
        mutableStateOf(false)
    }

    if (dialogOpen && enabled) {
        IntInputDialog(
            current = value,
            name = stringResource(headline),
            validator = validator,
            onSubmit = { new ->
            dialogOpen = false
            new?.let(onValueChange)
            },
            neutralButtonLabel = neutralButtonLabel,
            neutralValueProvider = neutralValueProvider
        )
    }

    ExpressiveSettingsConfigurableItem(
        modifier = modifier,
        headlineContent = stringResource(headline),
        supportingContent = supportingText ?: stringResource(description),
        enabled = enabled,
        trailingContent = trailingContent,
        secondaryActionLabel = stringResource(R.string.reset),
        onSecondaryAction = { defaultValue?.let(onValueChange) },
        primaryActionLabel = stringResource(R.string.edit),
        onPrimaryAction = { dialogOpen = true },
        secondaryActionEnabled = enabled && defaultValue != null && value != defaultValue,
        primaryActionEnabled = enabled
    )
}
