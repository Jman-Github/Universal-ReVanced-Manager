package app.urv.manager.domain.installer.root

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootSplitApksTest {
    private val hash = "a".repeat(64)
    private val base = RootArtifactState("/cache/base.apk", "com.example.app", "1", 1, "signer", hash)
    private val split = base.copy(path = "/cache/en.apk", splitName = "config.en", sha256 = "b".repeat(64), topology = "SPLIT")

    @Test
    fun `journal identities retain split hashes and read older single APK records`() {
        val original = state()
        assertEquals(original, Json.decodeFromString<RootPackageState>(Json.encodeToString(original)))
        val old = Json.decodeFromString<RootPackageState>("""{"packageName":"com.example.app","userId":0,"installed":true,"basePath":"/data/app/example/base.apk"}""")
        assertTrue(old.verifiedSplits().isEmpty())
        val stock = verifiedStockSet(listOf(base, split)).single()
        assertEquals(stock, Json.decodeFromString<RootArtifactState>(Json.encodeToString(stock)))
    }

    @Test
    fun `stock set accepts one base and matching signed splits in any order`() {
        val stock = verifiedStockSet(listOf(split, base)).single()
        assertEquals(base.path, stock.path)
        assertEquals("SPLIT", stock.topology)
        assertEquals(listOf(split.sha256), stock.splitHashes)
    }

    @Test
    fun `stock set rejects missing base duplicate splits and mixed identities`() {
        for (set in listOf(listOf(split), listOf(base, base), listOf(base, split, split),
            listOf(base, split.copy(signerSha256 = "other")),
            listOf(base, split.copy(versionCode = 2)), listOf(base, split.copy(packageName = "other.app")))) {
            assertFailsWith<IllegalArgumentException> { verifiedStockSet(set) }
        }
    }
    private fun state(directory: String = "/data/app/example") = RootPackageState(
        packageName = "com.example.app", userId = 0, installed = true,
        basePath = "$directory/base.apk",
        splitPaths = listOf("$directory/split_config.en.apk"),
        splitSha256 = mapOf("$directory/split_config.en.apk" to hash)
    )

    @Test
    fun `split identity survives install directory relocation`() {
        val identity = state().verifiedSplits()
        assertEquals(mapOf("split_config.en.apk" to hash), identity)
        assertTrue(state("/data/app/moved").matchesSplits(identity))
        assertEquals(identity, decodeSplitIdentity(encodeSplitIdentity(identity)))
    }

    @Test
    fun `missing changed duplicate and foreign splits fail closed`() {
        val original = state()
        assertFailsWith<IllegalArgumentException> { original.copy(splitSha256 = emptyMap()).verifiedSplits() }
        assertFailsWith<IllegalArgumentException> { original.copy(splitPaths = original.splitPaths + original.splitPaths).verifiedSplits() }
        assertFailsWith<IllegalArgumentException> { original.copy(basePath = "/data/app/other/base.apk").verifiedSplits() }
        assertFalse(original.copy(splitSha256 = original.splitSha256.mapValues { "b".repeat(64) }).matchesSplits(original.verifiedSplits()))
        assertFalse(original.copy(splitPaths = emptyList(), splitSha256 = emptyMap()).matchesSplits(original.verifiedSplits()))
    }

    @Test
    fun `stored identity rejects traversal invalid hashes duplicate names and topology mismatch`() {
        for (value in listOf("../split.apk:$hash", "base.apk:$hash", "split.apk:bad", "split.apk:$hash split.apk:$hash")) {
            assertFailsWith<IllegalArgumentException> { decodeSplitIdentity(value) }
        }
        assertFalse(validSplitIdentity("SINGLE", state().verifiedSplits()))
        assertFalse(validSplitIdentity("SPLIT", emptyMap()))
        assertTrue(validSplitIdentity("SINGLE", decodeSplitIdentity("")))
    }
}
