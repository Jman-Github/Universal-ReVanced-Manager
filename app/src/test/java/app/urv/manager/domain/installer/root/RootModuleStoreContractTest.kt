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
        assertTrue(restore.contains("payload.previous"))
        assertTrue(restore.contains("mv \${shellQuote(canonicalPrevious)} \${shellQuote(canonical)}"))
    }

    @Test
    fun `enabling a saved module refreshes changed runtime scripts without rewriting identical files`() {
        val source = source().readText()
        val enable = source.substringAfter("override suspend fun enable(")
            .substringBefore("override suspend fun disable(")

        assertTrue(enable.contains("copyAsset(\"root/post-fs-data.sh\""))
        assertTrue(enable.contains("copyAsset(\"root/service.sh\""))
        assertTrue(enable.contains("val serviceNext = \"\$serviceTarget.urv-next\""))
        assertTrue(enable.contains("sha256sum"))
        assertTrue(enable.contains("[ -L \${shellQuote(serviceTarget)} ]"))
        assertTrue(enable.contains("mv -f"))
        assertTrue(enable.contains("rm -f \${shellQuote(\"\$module/disable\")}"))
        assertTrue(enable.contains("repair_payload()"))
        assertTrue(enable.contains("RootPaths.canonicalPatched(packageName)"))
        assertTrue(enable.contains("URV_PATCHED_SHA256"))
        assertTrue(enable.contains("URV_STOCK_SHADOW_SHA256"))
        assertTrue(enable.contains("val payloadMaintenance = if (repairPayloads)"))
    }

    @Test
    fun `enabling a legacy module seeds verified canonical recovery payloads`() {
        val source = source().readText()
        val enable = source.substringAfter("override suspend fun enable(")
            .substringBefore("override suspend fun disable(")

        assertTrue(enable.contains("RootPaths.canonicalPayload(packageName)"))
        assertTrue(enable.contains("ensure_canonical_payload()"))
        assertTrue(enable.contains("[ ! -L \${shellQuote(canonicalPayload)} ]"))
        assertTrue(enable.contains("chmod 700 \${shellQuote(backupRoot)} \${shellQuote(canonicalPayload)}"))
        assertTrue(enable.contains("ensure_canonical_payload \\\"\${'$'}URV_PATCHED_PATH\\\""))
        assertTrue(enable.contains("ensure_canonical_payload \\\"\${'$'}URV_STOCK_SHADOW_PATH\\\""))
        assertTrue(enable.contains("chmod 600 \\\"\${'$'}target\\\""))
        assertTrue(enable.contains("sync -f \${shellQuote(canonicalPayload)}"))
        assertTrue(
            enable.indexOf("repair_payload \${shellQuote(canonicalPatched)}") <
                enable.indexOf("ensure_canonical_payload \\\"\${'$'}URV_PATCHED_PATH\\\"")
        )
    }

    @Test
    fun `staging preserves verified canonical payload copies outside the module`() {
        val source = source().readText()
        val stage = source.substringAfter("override suspend fun stageAndActivate(")
            .substringBefore("override suspend fun updateState(")

        assertTrue(stage.contains("RootPaths.canonicalPayload(packageName)"))
        assertTrue(stage.contains("val canonicalPatched = \"\$canonicalNext/patched\""))
        assertTrue(stage.contains("val canonicalStock = \"\$canonicalNext/stock\""))
        assertTrue(stage.contains("sha256sum \${shellQuote(canonicalPatched)}"))
        assertTrue(stage.contains("sha256sum \${shellQuote(canonicalStock)}"))
        assertTrue(stage.contains("mv \${shellQuote(canonicalNext)} \${shellQuote(canonical)}"))
    }

    @Test
    fun `successful commit removes temporary rollback module`() {
        val source = source().readText()
        val commit = source.substringAfter("override suspend fun commitSnapshot(")
            .substringBefore("override suspend fun stageAndActivate(")

        assertTrue(commit.contains("RootPaths.rollbackModule(packageName)"))
        assertTrue(commit.contains("payload.previous"))
    }

    private fun source(): File = sequenceOf(
        File("app/src/main/java/app/urv/manager/domain/installer/root/RootModuleStore.kt"),
        File("src/main/java/app/urv/manager/domain/installer/root/RootModuleStore.kt")
    ).first { it.isFile }
}
