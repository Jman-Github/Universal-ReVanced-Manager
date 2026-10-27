package app.urv.manager.util

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.universal.revanced.manager.R
import java.util.Locale

data class AppLanguageOption(val code: String, @StringRes val labelRes: Int)

// Include every locale with translated strings; empty translation directories are placeholders.
val appLanguageOptions = listOf(
    AppLanguageOption("system", R.string.language_option_system),
    AppLanguageOption("en", R.string.language_option_english),
    AppLanguageOption("ar", R.string.language_option_arabic),
    AppLanguageOption("de", R.string.language_option_german),
    AppLanguageOption("el", R.string.language_option_greek),
    AppLanguageOption("es", R.string.language_option_spanish),
    AppLanguageOption("fil", R.string.language_option_filipino),
    AppLanguageOption("fr", R.string.language_option_french),
    AppLanguageOption("gu", R.string.language_option_gujarati),
    AppLanguageOption("hi", R.string.language_option_hindi),
    AppLanguageOption("in", R.string.language_option_indonesian),
    AppLanguageOption("it", R.string.language_option_italian),
    AppLanguageOption("ja", R.string.language_option_japanese),
    AppLanguageOption("ko", R.string.language_option_korean),
    AppLanguageOption("pt-BR", R.string.language_option_portuguese_brazil),
    AppLanguageOption("ru", R.string.language_option_russian),
    AppLanguageOption("tr", R.string.language_option_turkish),
    AppLanguageOption("uk", R.string.language_option_ukrainian),
    AppLanguageOption("vi", R.string.language_option_vietnamese),
    AppLanguageOption("zh-CN", R.string.language_option_chinese_simplified)
)

private val supportedLanguages = appLanguageOptions.map { normalizeTag(it.code) }.toSet() + "id"

private fun normalizeTag(tag: String): String =
    tag.trim()
        .replace('_', '-')
        .lowercase(Locale.ROOT)

fun resolveAppLanguageCode(preference: String): String {
    val pref = normalizeTag(preference)
    if (pref.isBlank() || pref == "system") {
        val sysLocale = LocaleListCompat.getAdjustedDefault().get(0)
        val systemTag = sysLocale?.toLanguageTag()?.let(::normalizeTag)

        // Exact tag match
        if (systemTag != null && supportedLanguages.contains(systemTag)) return systemTag

        // Language-only match (e.g., en-GB -> en)
        val systemLang = systemTag?.substringBefore('-')
        if (systemLang != null) {
            supportedLanguages.firstOrNull { it.substringBefore('-') == systemLang }?.let { return it }
        }

        // Fallback to English if the system locale is unsupported.
        return "en"
    }

    if (supportedLanguages.contains(pref)) return pref

    val prefLang = pref.substringBefore('-')
    return supportedLanguages.firstOrNull { it.substringBefore('-') == prefLang } ?: "en"
}

fun isAppLanguageApplied(
    code: String,
    appliedLanguageTags: String = AppCompatDelegate.getApplicationLocales().toLanguageTags()
): Boolean {
    val normalized = normalizeTag(code)
    if (normalized.isBlank() || normalized == "system") {
        return appliedLanguageTags.isBlank()
    }
    val appliedTag = appliedLanguageTags.substringBefore(',').takeIf { it.isNotBlank() }
        ?: return false
    return Locale.forLanguageTag(resolveAppLanguageCode(code)) == Locale.forLanguageTag(appliedTag)
}

fun applyAppLanguage(code: String) {
    val normalized = normalizeTag(code)

    // If user picked system, try the device locale first, otherwise fall back to English.
    val target = if (normalized == "system" || normalized.isBlank()) {
        // Reset to follow the device (AppCompat will use system locales if we set an empty list).
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        LocaleListCompat.getAdjustedDefault().get(0)?.let { Locale.setDefault(it) }
        return
    } else {
        resolveAppLanguageCode(code)
    }

    val localeList = when (target.lowercase(Locale.ROOT)) {
        "zh", "zh-cn", "zh_cn", "zh-hans" -> LocaleListCompat.create(Locale.SIMPLIFIED_CHINESE)
        "en", "en-us", "en_gb" -> LocaleListCompat.create(Locale.ENGLISH)
        "id", "in" -> LocaleListCompat.forLanguageTags("in")
        else -> LocaleListCompat.forLanguageTags(target)
    }

    localeList.get(0)?.let { Locale.setDefault(it) }
    AppCompatDelegate.setApplicationLocales(localeList)
}
