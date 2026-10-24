package app.urv.manager.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageOptionOrderingTest {
    @Test fun keepsDeviceLanguageFirstAndSortsDisplayedLabels() {
        val options = listOf("en" to "English", "system" to "Use device language",
            "fr" to "French", "de" to "German", "ar" to "Arabic")
        val sorted = sortLanguageOptions(options, Locale.ENGLISH, { it.first }, { it.second })
        assertEquals(listOf("system", "ar", "en", "fr", "de"), sorted.map { it.first })
    }

    @Test fun usesLocalizedLabelsRatherThanLanguageCodes() {
        val options = listOf("en" to "Anglais", "de" to "Allemand", "es" to "Espagnol")
        val sorted = sortLanguageOptions(options, Locale.FRENCH, { it.first }, { it.second })
        assertEquals(listOf("de", "en", "es"), sorted.map { it.first })
    }
}
