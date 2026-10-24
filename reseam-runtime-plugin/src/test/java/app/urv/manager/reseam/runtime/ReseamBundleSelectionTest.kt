package app.urv.manager.reseam.runtime

import app.reseam.sdk.Compatibility
import app.reseam.sdk.PatchSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class ReseamBundleSelectionTest {
    private fun patch(bundle: String, id: String = "patch") =
        PatchSpec(bundle, id, id, false, "", true, emptyList(), Compatibility.Universal, emptyList())

    @Test fun selectedLocalCopyReplacesEnabledRemoteCopy() {
        val remote = listOf(patch("official"))
        val local = listOf(patch("official"))
        val dependency = listOf(patch("library"))
        assertEquals(listOf(1, 2), ReseamBundleSelection.select(listOf(remote, local, dependency), setOf(1)))
    }

    @Test fun selectedRemoteCopyReplacesEnabledLocalCopy() {
        assertEquals(listOf(0), ReseamBundleSelection.select(
            listOf(listOf(patch("official")), listOf(patch("official"))), setOf(0)
        ))
    }

    @Test fun distinctSelectedBundlesKeepTheirDependencySources() {
        val catalogs = listOf(listOf(patch("first")), listOf(patch("library")), listOf(patch("second")))
        assertEquals(listOf(0, 1, 2), ReseamBundleSelection.select(catalogs, setOf(0, 2)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun selectedCopiesCannotMixDifferentPatchesFromTheSameBundle() {
        ReseamBundleSelection.select(
            listOf(listOf(patch("official", "first")), listOf(patch("official", "second"))), setOf(0, 1)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun ambiguousDependencyVersionsRequireExplicitSourceChoice() {
        ReseamBundleSelection.select(
            listOf(listOf(patch("selected")), listOf(patch("library")), listOf(patch("library"))), setOf(0)
        )
    }
}
