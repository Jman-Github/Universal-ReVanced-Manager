package app.universal.patch.manager.domain.repository

import app.universal.patch.manager.domain.bundles.RepositoryReleaseSource
import app.universal.patch.manager.domain.bundles.RepositoryReleaseSourceParser
import org.junit.Assert.*
import org.junit.Test

class CustomGitRepositoryUrlTest {
    @Test fun normalizesForgejoRepositoryAndReleasesOverview() {
        for (suffix in listOf("", "/", ".git", "/releases")) {
            assertEquals("https://git.reseam.app/reseam/patches",
                customGitRepositoryUrl("https://git.reseam.app/reseam/patches$suffix"))
        }
    }

    @Test fun preservesCustomHostAndPort() {
        assertEquals("https://forge.example:8443/owner/patches",
            customGitRepositoryUrl("https://forge.example:8443/owner/patches/releases"))
    }

    @Test fun recognizesForgejoApiAndRawRepositoryIdentity() {
        val expected = RepositoryReleaseSource.Forgejo("https://git.reseam.app", "reseam/patches")
        for (suffix in listOf("/api/v1/repos/reseam/patches/releases",
            "/reseam/patches/raw/branch/main/patches.json",
            "/reseam/patches/releases/download/v1/patches.json")) {
            assertEquals(expected, RepositoryReleaseSourceParser.parse("https://git.reseam.app$suffix"))
        }
        assertEquals(expected.repositoryUrl,
            customGitRepositoryUrl("https://git.reseam.app/api/v1/repos/reseam/patches/releases"))
    }

    @Test fun convertsForgejoSourceManifestLinkToRaw() {
        assertEquals("https://git.reseam.app/reseam/patches/raw/branch/main/patches.json",
            customGitRepositoryUrl("https://git.reseam.app/reseam/patches/src/branch/main/patches.json"))
    }

    @Test fun rawAndBlobManifestLinksPreserveCustomPortsAndQueries() {
        val forgejo = "https://forge.example:8443/owner/patches/raw/branch/main/patches.json?download=1"
        assertEquals(forgejo, customGitRepositoryUrl(forgejo))
        assertEquals("https://git.example:8443/group/patches/-/raw/dev/patches.json",
            customGitRepositoryUrl("https://git.example:8443/group/patches/-/blob/dev/patches.json"))
    }

    @Test fun recognizesSelfHostedGitLabAndNestedGroups() {
        assertEquals(RepositoryReleaseSource.GitLab("group/subgroup/patches", "https://git.example"),
            RepositoryReleaseSourceParser.parse("https://git.example/group/subgroup/patches/-/raw/dev/patches.json"))
        assertEquals("https://git.example/group/subgroup/patches",
            customGitRepositoryUrl("https://git.example/group/subgroup/patches"))
        assertEquals("https://git.example/group/patches",
            customGitRepositoryUrl("https://git.example/group/patches/-/releases"))
    }

    @Test fun leavesGenericJsonAndHostedProvidersToExistingImporters() {
        assertNull(customGitRepositoryUrl("https://example.org/files/patches.json"))
        assertNull(RepositoryReleaseSourceParser.parse("https://example.org/owner/patches/patches.json"))
        assertNull(customGitRepositoryUrl("https://github.com/owner/patches"))
        assertNull(customGitRepositoryUrl("https://gitlab.com/group/patches"))
        assertNull(customGitRepositoryUrl("https://revanced-external-bundles.brosssh.com/bundles/id?id=1"))
        assertNull(RepositoryReleaseSourceParser.parse("https://revanced-external-bundles.brosssh.com/bundles/id?id=1"))
    }

    @Test fun rejectsCredentialsNonWebSchemesAndUnrelatedPages() {
        assertNull(customGitRepositoryUrl("ftp://git.example/owner/patches"))
        assertNull(customGitRepositoryUrl("https://user:password@git.example/owner/patches"))
        assertNull(customGitRepositoryUrl("https://git.reseam.app/reseam/patches/issues"))
        assertNull(customGitRepositoryUrl("https://git.reseam.app/reseam/patches/releases/tag/v1"))
    }
}
