package app.urv.manager.domain.lsposed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LsposedSourceParserTest {
    @Test
    fun parsesRepositoryUrl() {
        val parsed = LsposedSourceParser.parse("https://github.com/nexalloy/NexAlloy")
        assertEquals(LsposedSourceKind.GITHUB_REPOSITORY, parsed.kind)
        assertEquals("https://github.com/nexalloy/NexAlloy", parsed.repositoryUrl)
    }

    @Test
    fun parsesReleaseUrl() {
        val parsed = LsposedSourceParser.parse(
            "https://github.com/nexalloy/NexAlloy/releases/tag/v1.2.3"
        )
        assertEquals(LsposedSourceKind.GITHUB_RELEASE, parsed.kind)
        assertEquals("v1.2.3", parsed.releaseTag)
    }

    @Test
    fun parsesEncodedSlashInReleaseTag() {
        val parsed = LsposedSourceParser.parse(
            "https://github.com/nexalloy/NexAlloy/releases/tag/release%2Fv1.2.3"
        )
        assertEquals(LsposedSourceKind.GITHUB_RELEASE, parsed.kind)
        assertEquals("release/v1.2.3", parsed.releaseTag)
    }

    @Test
    fun parsesReleaseAssetUrl() {
        val parsed = LsposedSourceParser.parse(
            "https://github.com/nexalloy/NexAlloy/releases/download/v1.2.3/NexAlloy.apk"
        )
        assertEquals(LsposedSourceKind.GITHUB_ASSET, parsed.kind)
        assertEquals("NexAlloy.apk", parsed.assetName)
    }

    @Test
    fun parsesEncodedSlashInReleaseAssetTag() {
        val parsed = LsposedSourceParser.parse(
            "https://github.com/nexalloy/NexAlloy/releases/download/release%2Fv1.2.3/NexAlloy.apk"
        )
        assertEquals(LsposedSourceKind.GITHUB_ASSET, parsed.kind)
        assertEquals("release/v1.2.3", parsed.releaseTag)
        assertEquals("NexAlloy.apk", parsed.assetName)
    }

    @Test
    fun rejectsNonGithubUrls() {
        assertFailsWith<IllegalArgumentException> {
            LsposedSourceParser.parse("https://example.com/module.apk")
        }
    }

    @Test
    fun normalizesPublishedSha256() {
        val digest = "A".repeat(64)
        assertEquals("a".repeat(64), LsposedSourceParser.normalizeDigest("sha256:$digest"))
        assertNull(LsposedSourceParser.normalizeDigest("sha256:not-a-digest"))
        assertNull(LsposedSourceParser.normalizeDigest("sha512:${"a".repeat(64)}"))
    }
}
