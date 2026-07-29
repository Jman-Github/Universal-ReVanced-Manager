package app.urv.manager.domain.installer.root

internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

internal object RootPaths {
    const val ROOT = "/data/adb/urv"
    const val MODULES = "/data/adb/modules"
    const val LEGACY = "/data/adb/revanced"
    const val LEGACY_SERVICE = "/data/adb/service.d"

    fun lock(packageName: String) = "$ROOT/locks/$packageName.lock.d"
    fun lockOwner(packageName: String) = "${lock(packageName)}/owner"
    fun transaction(packageName: String) = "$ROOT/transactions/$packageName"
    fun active(packageName: String) = "${transaction(packageName)}/active.json"
    fun committed(packageName: String) = "${transaction(packageName)}/committed.json"
    fun diagnostics(packageName: String) = "${transaction(packageName)}/diagnostics.log"
    fun backup(packageName: String) = "${transaction(packageName)}/backup"
    fun legacyPackage(packageName: String) = "$LEGACY/$packageName"
    fun legacyService(packageName: String) = "$LEGACY_SERVICE/urv-$packageName.sh"
    fun module(packageName: String) = "$MODULES/$packageName-revanced"
    fun moduleApk(packageName: String) = "${module(packageName)}/$packageName.apk"
    fun moduleStockApk(packageName: String) = "${module(packageName)}/$packageName-stock.apk"
    fun rollbackModule(packageName: String) = "$MODULES/.$packageName-revanced.urv-rollback"
    fun stagingModule(packageName: String, transactionId: String) =
        "$MODULES/.$packageName-revanced.urv-stage-$transactionId"
}
