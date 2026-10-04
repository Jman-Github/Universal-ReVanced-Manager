package app.urv.manager.domain.repository

import app.urv.manager.data.room.profile.PatchProfilePayload
import app.urv.manager.domain.bundles.JsonPatchBundle
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PatchProfileMapperTest {
    @Test
    fun `remote payload selection resolves a replaced bundle uid by endpoint`() {
        val sourceDirectory = Files.createTempDirectory("remote-bundle-source").toFile()
        try {
            val source = JsonPatchBundle(
                name = "Example bundle",
                uid = 200,
                displayName = null,
                createdAt = null,
                updatedAt = null,
                installedVersionSignature = null,
                error = null,
                directory = sourceDirectory,
                endpoint = "https://example.com/patches/",
                autoUpdate = false,
                searchUpdate = true,
                lastNotifiedVersion = null,
                enabled = true
            )
            val payload = PatchProfilePayload(
                bundles = listOf(
                    bundle(uid = 100, endpoint = "https://example.com/patches", patches = listOf("One")),
                    bundle(uid = 101, endpoint = "https://example.com/patches/", patches = listOf("Two"))
                )
            )

            val (remappedPayload, selection) = payload.remapAndExtractSelection(listOf(source), emptyMap())
            val merged = mapOf(100 to setOf("Stored only")).mergeWithRemappedSelection(
                originalPayload = payload,
                remappedPayload = remappedPayload,
                remappedSelection = selection,
                sources = listOf(source)
            )

            assertEquals(mapOf(200 to setOf("One", "Two")), selection)
            assertEquals(mapOf(200 to setOf("One", "Two", "Stored only")), merged)
        } finally {
            sourceDirectory.deleteRecursively()
        }
    }

    @Test
    fun `stored selection follows a local payload uid remap without duplicating bundles`() {
        val originalPayload = PatchProfilePayload(
            bundles = listOf(bundle(uid = 10, patches = listOf("One")))
        )
        val remappedPayload = PatchProfilePayload(
            bundles = listOf(bundle(uid = 20, patches = listOf("One")))
        )

        val merged = mapOf(10 to setOf("One", "Stored only")).mergeWithRemappedSelection(
            originalPayload = originalPayload,
            remappedPayload = remappedPayload,
            remappedSelection = mapOf(20 to setOf("One", "Payload only")),
            sources = emptyList()
        )

        assertEquals(
            mapOf(20 to setOf("One", "Stored only", "Payload only")),
            merged
        )
    }

    private fun bundle(
        uid: Int,
        endpoint: String? = null,
        patches: List<String>
    ) = PatchProfilePayload.Bundle(
        bundleUid = uid,
        patches = patches,
        options = emptyMap(),
        sourceEndpoint = endpoint
    )
}
