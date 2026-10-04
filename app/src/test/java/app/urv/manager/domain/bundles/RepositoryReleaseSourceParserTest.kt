package app.urv.manager.domain.bundles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoryReleaseSourceParserTest {
    @Test
    fun parsesGitHubRawManifestInNestedDirectory() {
        assertEquals(
            RepositoryReleaseSource.GitHub("https://github.com/owner/patches"),
            RepositoryReleaseSourceParser.parse(
                "https://raw.githubusercontent.com/owner/patches/dev/manifests/patches-bundle.json"
            )
        )
    }

    @Test
    fun parsesGitHubReleaseManifest() {
        assertEquals(
            RepositoryReleaseSource.GitHub("https://github.com/owner/patches"),
            RepositoryReleaseSourceParser.parse(
                "https://github.com/owner/patches/releases/download/v2.0.0/patches-bundle.json"
            )
        )
    }

    @Test
    fun parsesGitHubApiRepositoryUrl() {
        assertEquals(
            RepositoryReleaseSource.GitHub("https://github.com/owner/patches"),
            RepositoryReleaseSourceParser.parse(
                "https://api.github.com/repos/owner/patches/releases/latest"
            )
        )
    }

    @Test
    fun parsesGitLabRawManifestForNestedProject() {
        assertEquals(
            RepositoryReleaseSource.GitLab("group/subgroup/patches"),
            RepositoryReleaseSourceParser.parse(
                "https://gitlab.com/group/subgroup/patches/-/raw/dev/manifests/patches-bundle.json"
            )
        )
    }

    @Test
    fun parsesGitLabReleaseManifest() {
        assertEquals(
            RepositoryReleaseSource.GitLab("group/patches"),
            RepositoryReleaseSourceParser.parse(
                "https://gitlab.com/group/patches/-/releases/v2.0.0/downloads/patches-bundle.json"
            )
        )
    }

    @Test
    fun rejectsUnrelatedUrls() {
        assertNull(
            RepositoryReleaseSourceParser.parse(
                "https://example.com/owner/patches/patches-bundle.json"
            )
        )
        assertNull(RepositoryReleaseSourceParser.parse("https://gitlab.com/group/patches"))
    }
}
