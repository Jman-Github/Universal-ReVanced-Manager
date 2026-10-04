package app.urv.manager.domain.installer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileInstallerPreferenceTest {
    @Test
    fun `explicit auto install overrides the chooser only for a compatible installer`() {
        val cases = listOf(
            Triple(false, false, false) to false,
            Triple(false, false, true) to false,
            Triple(false, true, false) to true,
            Triple(false, true, true) to true,
            Triple(true, false, false) to false,
            Triple(true, false, true) to false,
            Triple(true, true, false) to false,
            Triple(true, true, true) to true
        )
        for ((inputs, expected) in cases) {
            val (choosePerInstall, matchesMode, autoInstall) = inputs
            assertEquals(
                expected,
                shouldApplyProfileInstallerPreference(choosePerInstall, matchesMode, autoInstall),
                "chooser=$choosePerInstall compatible=$matchesMode auto=$autoInstall"
            )
        }
    }

    @Test
    fun `profile auto install survives both parameter handoffs`() {
        val selected = source("SelectedAppInfoViewModel.kt")
        val params = selected.substringAfter("suspend fun getPatcherParams()")
            .substringBefore("private fun ")
        val patcher = source("PatcherViewModel.kt").substringAfter("fun maybeAutoInstall()")
            .substringBefore("fun installWithToken(")
        assertTrue(params.contains("autoInstall = profile.autoInstall"))
        assertTrue(patcher.contains("autoInstall = input.autoInstall"))
        assertTrue(selected.substringAfter("private fun loadProfileConfiguration(")
            .substringBefore("val sourcesList").contains("installerTokenMatchesPatchMode("))
    }

    private fun source(name: String): String = sequenceOf(
        File("app/src/main/java/app/urv/manager/ui/viewmodel/$name"),
        File("src/main/java/app/urv/manager/ui/viewmodel/$name")
    ).first { it.isFile }.readText()
}
