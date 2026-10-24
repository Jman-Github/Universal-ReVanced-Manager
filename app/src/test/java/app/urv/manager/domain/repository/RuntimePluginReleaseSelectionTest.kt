package app.urv.manager.domain.repository

import app.urv.manager.network.dto.GitHubAsset
import app.urv.manager.network.dto.GitHubRelease
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePluginReleaseSelectionTest {
    private fun release(tag: String, prerelease: Boolean, vararg names: String) =
        GitHubRelease(tagName = tag, prerelease = prerelease, assets = names.map {
            GitHubAsset(name = it, downloadUrl = "https://example.com/$tag/$it")
        })

    private fun select(vararg releases: GitHubRelease) =
        selectRuntimePluginAssets(releases.toList()) {
            it.name.takeIf { name -> name.endsWith("-plugin.apk") }
        }.map { (release, asset) -> release.tagName to asset.name }

    @Test
    fun discoversPrereleaseOnlyPluginWithoutReplacingStablePlugin() {
        assertEquals(
            listOf("stable" to "v21-plugin.apk", "dev" to "reseam-plugin.apk"),
            select(
                release("dev", true, "v21-plugin.apk", "reseam-plugin.apk"),
                release("stable", false, "v21-plugin.apk")
            )
        )
    }

    @Test
    fun keepsNewestReleaseWithinEachChannel() {
        assertEquals(
            listOf("stable-new" to "v21-plugin.apk", "dev-new" to "reseam-plugin.apk"),
            select(
                release("dev-new", true, "reseam-plugin.apk"),
                release("dev-old", true, "reseam-plugin.apk"),
                release("stable-new", false, "v21-plugin.apk"),
                release("stable-old", false, "v21-plugin.apk")
            )
        )
    }

    @Test
    fun ignoresDraftsAndUnrelatedAssets() {
        assertEquals(
            listOf("published" to "reseam-plugin.apk"),
            select(
                release("draft", false, "reseam-plugin.apk").copy(draft = true),
                release("published", true, "manager.apk", "reseam-plugin.apk")
            )
        )
    }
}
