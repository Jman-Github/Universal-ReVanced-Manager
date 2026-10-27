package app.urv.manager.util

import java.text.Collator
import java.util.Locale

internal fun <T> sortLanguageOptions(
    options: List<T>,
    locale: Locale,
    code: (T) -> String,
    label: (T) -> String
): List<T> {
    val collator = Collator.getInstance(locale)
    return options.sortedWith(compareBy<T> { code(it) != "system" }
        .thenComparator { a, b -> collator.compare(label(a), label(b)) })
}
