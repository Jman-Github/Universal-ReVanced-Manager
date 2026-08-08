package app.urv.manager.domain.installer.root

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class RootModuleStoreContractTest {
    @Test
    fun `rollback restores only a verified previous payload`() {
        val source = source().readText()
        val restore = source.substringAfter("override suspend fun restorePrevious(")
            .substringBefore("override suspend fun enable(")

        assertTrue(restore.contains("val backupPayload = \"\$backup/\$packageName.apk\""))
        assertTrue(restore.contains("val rollbackPayload = \"\$rollback/\$packageName.apk\""))
        assertTrue(restore.contains("if [ -f \${shellQuote(backupPayload)} ]"))
        assertTrue(restore.contains("elif [ -f \${shellQuote(rollbackPayload)} ]"))
        assertTrue(restore.contains("else rm -rf \${shellQuote(active)} \${shellQuote(rollback)}"))
    }

    @Test
    fun `successful commit removes temporary rollback module`() {
        val source = source().readText()
        val commit = source.substringAfter("override suspend fun commitSnapshot(")
            .substringBefore("override suspend fun stageAndActivate(")

        assertTrue(commit.contains("RootPaths.rollbackModule(packageName)"))
    }

    private fun source(): File = sequenceOf(
        File("app/src/main/java/app/urv/manager/domain/installer/root/RootModuleStore.kt"),
        File("src/main/java/app/urv/manager/domain/installer/root/RootModuleStore.kt")
    ).first { it.isFile }
}
