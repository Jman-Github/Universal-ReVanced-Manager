package app.urv.manager.domain.repository

import app.urv.manager.data.room.options.Option
import app.urv.manager.data.room.profile.PatchProfilePayload
import kotlin.test.Test
import kotlin.test.assertEquals

class PatchOptionInputManagerTest {
    @Test
    fun collectsReferencesFromSavedOptionsAndPayloadSnapshots() {
        val directFile = "/data/user/0/app/files/patch-option-inputs/input.png"
        val listFile = "/data/user/0/app/files/patch-option-inputs/list.png"
        val profileFolder = "/data/user/0/app/files/patch-option-inputs/folder"
        val installedFile = "/data/user/0/app/files/patch-option-inputs/installed.ttf"

        val profilePayload = payloadWithValue(profileFolder)
        val installedPayload = payloadWithValue(installedFile)

        val result = collectReferencedPatchOptionInputPaths(
            optionValues = listOf(
                Option.SerializedValue.fromValue(directFile),
                Option.SerializedValue.fromValue(listOf(listFile)),
                Option.SerializedValue.fromValue(42)
            ),
            payloads = listOf(profilePayload, installedPayload)
        )

        assertEquals(setOf(directFile, listFile, profileFolder, installedFile), result)
    }

    @Test
    fun collectsStringsFromNestedPendingOptionValues() {
        val result = collectStringValues(
            mapOf(
                "single" to "/managed/single.png",
                "list" to listOf("/managed/first.ttf", "/managed/second.ttf"),
                "array" to arrayOf("/managed/folder")
            )
        )

        assertEquals(
            setOf(
                "/managed/single.png",
                "/managed/first.ttf",
                "/managed/second.ttf",
                "/managed/folder"
            ),
            result
        )
    }

    @Test
    fun pendingOwnershipDoesNotReleaseInheritedInputs() {
        val ownership = PendingPatchOptionInputOwnership(
            inherited = setOf("/managed/inherited"),
            initiallyReferenced = setOf("/managed/inherited", "/managed/new")
        )

        val released = ownership.reconcile(setOf("/managed/inherited"))

        assertEquals(setOf("/managed/new"), released)
        assertEquals(setOf("/managed/inherited"), ownership.releaseAll())
    }

    @Test
    fun pendingOwnershipTransfersOnlyCurrentlyReferencedNewInputs() {
        val ownership = PendingPatchOptionInputOwnership(
            inherited = setOf("/managed/inherited"),
            initiallyReferenced = setOf(
                "/managed/inherited",
                "/managed/current",
                "/managed/history-only"
            )
        )

        val transfer = ownership.transfer(
            setOf("/managed/inherited", "/managed/current")
        )

        assertEquals(setOf("/managed/current"), transfer.transferred)
        assertEquals(setOf("/managed/history-only"), transfer.released)
        assertEquals(
            setOf("/managed/inherited", "/managed/current"),
            ownership.releaseAll()
        )
    }

    @Test
    fun sharedPendingInputRemainsProtectedUntilEveryOwnerReleasesIt() {
        val path = "/managed/shared"
        val counts = mutableMapOf<String, Int>()
        val acquire: (Set<String>) -> Unit = { paths ->
            paths.forEach { ownedPath ->
                counts[ownedPath] = counts.getOrDefault(ownedPath, 0) + 1
            }
        }
        val release: (Set<String>) -> Unit = { paths ->
            paths.forEach { ownedPath ->
                val remaining = counts.getOrDefault(ownedPath, 0) - 1
                if (remaining > 0) counts[ownedPath] = remaining else counts.remove(ownedPath)
            }
        }
        val parent = PendingPatchOptionInputOwnership(
            initiallyReferenced = setOf(path),
            onAcquire = acquire,
            onRelease = release
        )
        val child = PendingPatchOptionInputOwnership(
            inherited = setOf(path),
            initiallyReferenced = setOf(path),
            onAcquire = acquire,
            onRelease = release
        )

        parent.reconcile(emptySet())
        assertEquals(mapOf(path to 1), counts)

        child.releaseAll()
        assertEquals(emptyMap(), counts)
    }

    private fun payloadWithValue(value: String) = PatchProfilePayload(
        bundles = listOf(
            PatchProfilePayload.Bundle(
                bundleUid = 1,
                patches = listOf("Patch"),
                options = mapOf(
                    "Patch" to mapOf(
                        "option" to Option.SerializedValue.fromValue(value)
                    )
                )
            )
        )
    )
}
