package app.universal.patch.manager.domain.installer.root

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class RootInstallCommandsTest {
    private fun shell(command: String): Pair<Int, String> {
        val bash = System.getenv("URV_TEST_SHELL")
            ?: File("C:/Program Files/Git/bin/bash.exe").takeIf { it.isFile }?.path ?: "bash"
        val process = ProcessBuilder(bash, "-s").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(command); it.newLine() }
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test fun `both backends stream exact APK bytes through a pipe with quoted paths`() {
        val root = Files.createTempDirectory("root-install-commands-").toFile()
        try {
            val apk = root.resolve("stock ' quoted.apk")
            val output = root.resolve("received.apk")
            val bytes = ByteArray(8193) { (it * 31).toByte() }
            apk.writeBytes(bytes)
            for (backend in listOf("pm", "cmd package")) {
                val command = rootInstallWriteCommand(backend, "42", "config ' en.apk", apk.invariantSeparatorsPath, bytes.size.toLong())
                val script = """
                    pm() {
                      [ "${'$'}1" = install-write ] && [ "${'$'}2" = -S ] &&
                      [ "${'$'}3" = ${bytes.size} ] && [ "${'$'}4" = 42 ] &&
                      [ "${'$'}5" = "config ' en.apk" ] && [ "${'$'}6" = - ] || return 9
                      cat > ${shellQuote(output.invariantSeparatorsPath)}
                    }
                    cmd() { [ "${'$'}1" = package ] || return 10; shift; pm "${'$'}@"; }
                    $command
                """.trimIndent()
                val result = shell(script)
                assertEquals(0, result.first, result.second)
                assertContentEquals(bytes, output.readBytes())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `APK read failure is not hidden by a successful package manager`() {
        val command = rootInstallWriteCommand("pm", "42", "base.apk", "/does-not-exist/urv.apk", 4096)
        val result = shell("pm() { cat > /dev/null; return 0; }; $command")
        assertNotEquals(0, result.first)
    }

    @Test fun `package manager failure propagates after consuming the payload`() {
        val root = Files.createTempDirectory("root-install-failure-").toFile()
        try {
            val apk = root.resolve("base.apk").apply { writeText("APK") }
            val command = rootInstallWriteCommand("cmd package", "42", "base.apk", apk.invariantSeparatorsPath, apk.length())
            val result = shell("cmd() { cat > /dev/null; return 37; }; $command")
            assertEquals(37, result.first)
        } finally { root.deleteRecursively() }
    }

    @Test fun `invalid session or backend cannot inject shell commands`() {
        assertFailsWith<IllegalArgumentException> { rootInstallWriteCommand("pm; exit 0", "42", "base.apk", "/stock.apk", 1) }
        assertFailsWith<IllegalArgumentException> { rootInstallWriteCommand("pm", "42; exit 0", "base.apk", "/stock.apk", 1) }
        assertFailsWith<IllegalArgumentException> { rootInstallWriteCommand("pm", "42", "base.apk", "/stock.apk", 0) }
    }
}
