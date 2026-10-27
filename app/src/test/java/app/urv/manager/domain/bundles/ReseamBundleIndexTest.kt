package app.urv.manager.domain.bundles

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ReseamBundleIndexTest {
    private val endpoint = "https://example.com/patches/index.json"
    private fun parse(value: String, prerelease: Boolean? = false) =
        ReseamBundleIndex.parse(Json.parseToJsonElement(value).jsonObject, endpoint, prerelease)

    @Test fun selectsStableAndResolvesDownloadUrl() {
        val json = """{"bundle":{"name":"Test"},"releases":[
            {"version":"2-dev","download_url":"dev.reseam","prerelease":true},
            {"version":"1","download_url":"release.reseam"}]}"""
        assertEquals("1", parse(json)!!.asset.version)
        assertEquals("https://example.com/patches/release.reseam", parse(json)!!.asset.downloadUrl)
        assertEquals("2-dev", parse(json, null)!!.asset.version)
        assertEquals("2-dev", parse(json, true)!!.asset.version)
        assertEquals(parse(json)!!.asset.createdAt, parse(json)!!.asset.createdAt)
    }

    @Test fun acceptsOfficialSingleReleaseResponse() {
        val json = """{"bundle":{"name":"Test"},"release":{"version":"1","download_url":"https://example.com/test.reseam"}}"""
        assertEquals("1", parse(json)!!.asset.version)
        assertNull(parse(json)!!.publicKey)
    }

    @Test fun versionPickerIncludesBothChannelsAndResolvesEachUrl() {
        val json = Json.parseToJsonElement("""{"bundle":{"name":"Test"},"releases":[
            {"version":"2-dev","download_url":"dev.reseam","prerelease":true},
            {"version":"1","download_url":"release.reseam"}]}""").jsonObject
        val releases = ReseamBundleIndex.parseAll(json, endpoint)!!
        assertEquals(listOf("2-dev", "1"), releases.map { it.asset.version })
        assertEquals("https://example.com/patches/release.reseam", releases[1].asset.downloadUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun versionPickerRejectsInvalidUrlsInOlderReleases() {
        val json = Json.parseToJsonElement("""{"bundle":{},"releases":[
            {"version":"2","download_url":"new.reseam"},
            {"version":"1","download_url":"file:///tmp/unsafe.reseam"}]}""").jsonObject
        ReseamBundleIndex.parseAll(json, endpoint)
    }

    @Test fun existingRevancedManifestIsNotIntercepted() {
        assertNull(parse("""{"version":"1","download_url":"https://example.com/test.rvp"}"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedPublisherKey() {
        parse("""{"bundle":{"public_key":"bad"},"releases":[{"version":"1","download_url":"test.reseam"}]}""")
    }
}
