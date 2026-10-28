package app.universal.patch.manager.network.api

import kotlinx.serialization.json.*
import app.universal.patch.manager.network.dto.BundleNode
import app.universal.patch.manager.network.dto.SourceNode
import app.universal.patch.manager.network.dto.SourceMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogFiltersTest {
    @Test
    fun `bundle search matches type and repository names with channel restrictions`() {
        val bundle = BundleNode(id = 1, bundleType = "Morphe:V1", isPrerelease = true,
            source = SourceNode(url = "https://example.com/source",
                sourceMetadata = SourceMetadata(ownerName = "Publisher", repoName = "Patches")))
        assertTrue(CatalogFilters(query = "morphe").matchesBundle(bundle))
        assertTrue(CatalogFilters(query = "PUBLISHER").matchesBundle(bundle))
        assertFalse(CatalogFilters(query = "morphe", release = "release").matchesBundle(bundle))
        assertFalse(CatalogFilters(query = "missing").matchesBundle(bundle))
    }

    @Test
    fun `exact package queries scope bundle IDs without hydrating package relationships`() {
        val where = scopedPackagePatchWhere(listOf(5, 9), listOf("com.example.app"), null)
        assertEquals(listOf(5, 9), where["bundle_fk"]!!.jsonObject["_in"]!!.jsonArray
            .map { it.jsonPrimitive.int })
        assertNull(where["patch_packages"])
        assertEquals("verified", where["metadata_status"]!!.jsonObject["_eq"]!!.jsonPrimitive.content)
    }

    @Test
    fun `version scoped package scans retain unrestricted compatibility`() {
        val where = scopedPackagePatchWhere(listOf(5), listOf("com.example.app"), "1.2")
        val pkg = where["patch_packages"]!!.jsonObject["package"]!!.jsonObject
        val versions = pkg["_or"]!!.jsonArray
        assertEquals("com.example.app", pkg["name"]!!.jsonObject["_eq"]!!.jsonPrimitive.content)
        assertEquals("1.2", versions[0].jsonObject["version"]!!.jsonObject["_eq"]!!.jsonPrimitive.content)
        assertTrue(versions[1].jsonObject["version"]!!.jsonObject["_is_null"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `default browsing uses current releases and selected sources expose history`() {
        assertEquals(true, CatalogFilters().bundleWhere()["is_latest"]!!.jsonObject["_eq"]!!.jsonPrimitive.boolean)
        val history = CatalogFilters(sourceUrl = "https://gitlab.com/group/patches", release = "all")
            .bundleWhere()
        assertNull(history["is_latest"])
        assertEquals("https://gitlab.com/group/patches",
            history["source"]!!.jsonObject["url"]!!.jsonObject["_eq"]!!.jsonPrimitive.content)
    }

    @Test
    fun `latest source browsing and release channels compose with type filters`() {
        val where = CatalogFilters(sourceUrl = "https://github.com/example/patches",
            bundleType = "Morphe:V1", release = "latest").bundleWhere()
        assertTrue(where["is_latest"]!!.jsonObject["_eq"]!!.jsonPrimitive.boolean)
        val stable = CatalogFilters(release = "release", bundleType = "ReVanced:v21").bundleWhere()
        assertFalse(stable["is_prerelease"]!!.jsonObject["_eq"]!!.jsonPrimitive.boolean)
        assertEquals("ReVanced:v21", stable["bundle_type"]!!.jsonObject["_eq"]!!.jsonPrimitive.content)
    }

    @Test
    fun `app selections match exact package identities and require verified compatibility`() {
        val where = CatalogFilters(packageName = "com.example.app", exactPackage = true).bundleWhere()
        val pkg = where["patches"]!!.jsonObject["patch_packages"]!!.jsonObject["package"]!!.jsonObject
        assertEquals("com.example.app", pkg["name"]!!.jsonObject["_eq"]!!.jsonPrimitive.content)
        assertEquals("verified", where["metadata_status"]!!.jsonObject["_eq"]!!.jsonPrimitive.content)
    }

    @Test
    fun `package search treats SQL wildcard characters as literal text`() {
        val where = CatalogFilters(packageName = "com.example_app%").bundleWhere()
        val pkg = where["patches"]!!.jsonObject["patch_packages"]!!.jsonObject["package"]!!.jsonObject
        assertEquals("%com.example\\_app\\%%", pkg["name"]!!.jsonObject["_ilike"]!!.jsonPrimitive.content)
    }

    @Test
    fun `exact app lookups keep the package index constraint on the root patch`() {
        val where = CatalogFilters(packageName = "com.example.app", exactPackage = true,
            sourceUrl = "https://gitlab.com/example/patches", release = "all").patchWhere()
        val pkg = where["patch_packages"]!!.jsonObject["package"]!!.jsonObject
        assertEquals("com.example.app", pkg["name"]!!.jsonObject["_eq"]!!.jsonPrimitive.content)
        val bundle = where["bundle"]!!.jsonObject
        assertNull(bundle["patches"])
        assertNull(bundle["is_latest"])
        assertEquals("https://gitlab.com/example/patches",
            bundle["source"]!!.jsonObject["url"]!!.jsonObject["_eq"]!!.jsonPrimitive.content)
        assertEquals("verified", bundle["metadata_status"]!!.jsonObject["_eq"]!!.jsonPrimitive.content)
    }

    @Test
    fun `app version filters include unrestricted compatibility`() {
        val where = CatalogFilters(packageName = "com.example.app@1.2", exactPackage = true).bundleWhere()
        val pkg = where["patches"]!!.jsonObject["patch_packages"]!!.jsonObject["package"]!!.jsonObject
        val versions = pkg["_or"]!!.jsonArray
        assertEquals("1.2", versions[0].jsonObject["version"]!!.jsonObject["_eq"]!!.jsonPrimitive.content)
        assertTrue(versions[1].jsonObject["version"]!!.jsonObject["_is_null"]!!.jsonPrimitive.boolean)
    }
}
