package app.universal.patch.manager.domain.installer.root

object RootInstallCommandEmitter {
    @JvmStatic fun main(args: Array<String>) {
        println(rootInstallWriteCommand(args[0], args[1], args[2], args[3], args[4].toLong()))
    }
}
