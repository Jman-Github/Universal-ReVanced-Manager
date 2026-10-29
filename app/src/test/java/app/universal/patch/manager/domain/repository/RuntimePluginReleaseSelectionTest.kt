package app.universal.patch.manager.domain.repository

import app.universal.patch.manager.network.dto.GitHubAsset
import app.universal.patch.manager.network.dto.GitHubRelease
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

    @Test
    fun excludesBothManagerBrandsFromRepositoryDiscovery() {
        val release = release(
            "release", false,
            "universal-revanced-manager-v1.9.0-universal.apk",
            "universal-patch-manager-v1.10.0-universal.apk",
            "FULL-UNIVERSAL-PATCH-MANAGER-v1.10.0-arm64-v8a.APK",
            "revanced.v21-plugin.apk",
            "reseam-plugin.apk",
            "morphe-runtime.apk",
            "checksums.txt"
        )
        assertEquals(
            listOf("revanced.v21-plugin.apk", "morphe-runtime.apk"),
            selectRuntimePluginAssets(listOf(release)) {
                it.name.takeIf(::isRuntimePluginAssetName)
            }.map { it.second.name }
        )
    }
}
