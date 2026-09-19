package app.urv.manager.domain.bundles

import app.urv.manager.network.dto.GitHubRelease
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PatchBundleChangelogHistoryTest {
    private fun entry(version: String, text: String = "Notes", time: Long? = 1L) =
        PatchBundleChangelogEntry(version, text, time, "https://example.com/releases/latest")

    @Test
    fun keepsDifferentVersionsWithTheSamePageAndTimestamp() {
        val entries = listOf(entry("1.0"), entry("2.0"))
        assertEquals(2, mergePatchBundleChangelogs(emptyList(), entries, 20).size)
    }

    @Test
    fun mergesVersionPrefixesAndRetainsMissingFields() {
        val previous = entry("v1.0", "Complete release notes", 123L)
        val incoming = PatchBundleChangelogEntry("1.0", "")
        val result = mergePatchBundleChangelogs(listOf(previous), listOf(incoming), 20).single()
        assertEquals("Complete release notes", result.description)
        assertEquals(123L, result.publishedAtMillis)
        assertEquals(previous.pageUrl, result.pageUrl)
    }

    @Test
    fun freshReleaseBodyReplacesCachedNotes() {
        val result = mergePatchBundleChangelogs(
            listOf(entry("1.0", "Old notes")),
            listOf(entry("v1.0", "Corrected notes")),
            20
        )
        assertEquals("Corrected notes", result.single().description)
    }

    @Test
    fun metadataRefreshDoesNotReplaceSavedReleaseNotesWithASummary() {
        val result = mergePatchBundleChangelogs(
            listOf(entry("1.0", "Full release notes").copy(hasReleaseBody = true)),
            listOf(entry("1.0", "Summary")),
            20
        )
        assertEquals("Full release notes", result.single().description)
    }

    @Test
    fun aReleaseTitleDoesNotOverwriteServiceNotes() {
        for (body in listOf(null, "", "   ")) {
            val incoming = GitHubRelease(tagName = "v1.0", name = "Version 1.0", body = body)
                .toChangelogEntry("https://github.com/example/patches")
            val result = mergePatchBundleChangelogs(
                listOf(entry("1.0", "Detailed service notes")), listOf(incoming), 20
            ).single()
            assertEquals("Detailed service notes", result.description)
            assertFalse(result.hasReleaseBody)
        }
    }

    @Test
    fun correctedManifestNotesReplaceEarlierManifestNotes() {
        val result = mergePatchBundleChangelogs(
            listOf(entry("1.0", "Original manifest notes")),
            listOf(entry("1.0", "Corrected manifest notes")),
            20
        ).single()
        assertEquals("Corrected manifest notes", result.description)
        assertFalse(result.hasReleaseBody)
    }

    @Test
    fun releaseBodyUpgradesServiceNotesAndCanBeCorrected() {
        val initial = GitHubRelease(tagName = "v1.0", body = "Full notes")
            .toChangelogEntry("https://github.com/example/patches")
        val upgraded = mergePatchBundleChangelogs(
            listOf(entry("1.0", "Summary")), listOf(initial), 20
        )
        assertTrue(upgraded.single().hasReleaseBody)
        val corrected = initial.copy(description = "Corrected full notes")
        val result = mergePatchBundleChangelogs(upgraded, listOf(corrected), 20).single()
        assertEquals("Corrected full notes", result.description)
        assertTrue(result.hasReleaseBody)
    }

    @Test
    fun savedReleaseBodyRemainsProtectedAfterSerialization() {
        val saved = entry("1.0", "Full notes").copy(hasReleaseBody = true)
        val restored = Json.decodeFromString<PatchBundleChangelogEntry>(Json.encodeToString(saved))
        val result = mergePatchBundleChangelogs(
            listOf(restored), listOf(entry("1.0", "Metadata summary")), 20
        ).single()
        assertEquals("Full notes", result.description)
        assertTrue(result.hasReleaseBody)
    }

    @Test
    fun legacyHistoryStillAcceptsManifestCorrections() {
        val legacy = Json.decodeFromString<PatchBundleChangelogEntry>(
            """{"version":"1.0","description":"Old notes"}"""
        )
        val result = mergePatchBundleChangelogs(
            listOf(legacy), listOf(entry("1.0", "Corrected notes")), 20
        ).single()
        assertEquals("Corrected notes", result.description)
    }

    @Test
    fun retainsCaseSensitiveTags() {
        assertFalse(entry("previewA").isSameRelease(entry("previewa")))
    }

    @Test
    fun appliesStorageLimitAfterMergingAndSorting() {
        val result = mergePatchBundleChangelogs(
            listOf(entry("1", time = 1), entry("3", time = 3)),
            listOf(entry("2", time = 2), entry("v3", time = 3)),
            2
        )
        assertEquals(listOf("v3", "2"), result.map { it.version })
    }

    @Test
    fun aSingleStoredReleaseIsRetained() {
        assertEquals(listOf(entry("1")), mergePatchBundleChangelogs(listOf(entry("1")), emptyList(), 1))
    }
}
