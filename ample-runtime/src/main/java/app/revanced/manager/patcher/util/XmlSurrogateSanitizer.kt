package app.revanced.manager.patcher.util

import app.revanced.manager.patcher.logger.Logger
import java.io.File
import java.nio.charset.Charset

/**
 * Backport of revanced-patcher PR #339 behavior for runtimes pinned to older patcher versions.
 * Rewrites surrogate-pair numeric entities (for example, &#55357;&#56842;) into a single codepoint entity.
 */
object XmlSurrogateSanitizer {
    private val surrogateEntityPairRegex = Regex("&#(x[0-9a-fA-F]+|\\d+);\\s*&#(x[0-9a-fA-F]+|\\d+);")

    fun sanitize(apkDir: File, logger: Logger? = null): Int {
        if (!apkDir.exists()) return 0

        val xmlFiles = mutableListOf<File>()
        val manifest = apkDir.resolve("AndroidManifest.xml")
        if (manifest.exists()) xmlFiles += manifest

        val resDir = apkDir.resolve("res")
        if (resDir.exists()) {
            xmlFiles += resDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
                .toList()
        }

        var touchedFiles = 0
        var replacementCount = 0
        xmlFiles.forEach { file ->
            val source = readXmlText(file) ?: return@forEach
            val rewritten = rewriteSurrogateEntities(source.text)
            if (rewritten.count == 0) return@forEach
            writeXmlText(
                file = file,
                text = rewritten.text,
                charset = source.charset,
                hasUtf8Bom = source.hasUtf8Bom
            )
            touchedFiles += 1
            replacementCount += rewritten.count
        }

        if (replacementCount > 0) {
            logger?.info(
                "Normalized $replacementCount surrogate numeric character reference(s) " +
                    "across $touchedFiles XML file(s)"
            )
        }
        return replacementCount
    }

    private fun rewriteSurrogateEntities(text: String): RewriteResult {
        var replaced = 0
        val rewritten = surrogateEntityPairRegex.replace(text) { match ->
            val high = parseEntity(match.groupValues[1]) ?: return@replace match.value
            val low = parseEntity(match.groupValues[2]) ?: return@replace match.value
            if (high !in 0..0xFFFF || low !in 0..0xFFFF) return@replace match.value

            val highChar = high.toChar()
            val lowChar = low.toChar()
            if (!Character.isHighSurrogate(highChar) || !Character.isLowSurrogate(lowChar)) {
                return@replace match.value
            }

            replaced += 1
            val codePoint = Character.toCodePoint(highChar, lowChar)
            "&#$codePoint;"
        }
        return RewriteResult(rewritten, replaced)
    }

    private fun parseEntity(raw: String): Int? = if (raw.startsWith("x", ignoreCase = true)) {
        raw.substring(1).toIntOrNull(16)
    } else {
        raw.toIntOrNull(10)
    }

    private fun readXmlText(file: File): XmlText? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return XmlText("", Charsets.UTF_8, false)

        val charset = detectXmlCharset(bytes) ?: Charsets.UTF_8
        val hasUtf8Bom =
            charset == Charsets.UTF_8 &&
                bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
        val payload = if (hasUtf8Bom) bytes.copyOfRange(3, bytes.size) else bytes
        val text = runCatching { payload.toString(charset) }.getOrNull() ?: return null
        return XmlText(text, charset, hasUtf8Bom)
    }

    private fun writeXmlText(file: File, text: String, charset: Charset, hasUtf8Bom: Boolean) {
        val contentBytes = text.toByteArray(charset)
        val output = if (hasUtf8Bom && charset == Charsets.UTF_8) {
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + contentBytes
        } else {
            contentBytes
        }
        file.writeBytes(output)
    }

    private fun detectXmlCharset(bytes: ByteArray): Charset? {
        if (bytes.size >= 2) {
            val b0 = bytes[0]
            val b1 = bytes[1]
            if (b0 == 0xFE.toByte() && b1 == 0xFF.toByte()) return Charsets.UTF_16BE
            if (b0 == 0xFF.toByte() && b1 == 0xFE.toByte()) return Charsets.UTF_16LE
            if (b0 == 0x00.toByte() && b1 == 0x3C.toByte()) return Charsets.UTF_16BE
            if (b0 == 0x3C.toByte() && b1 == 0x00.toByte()) return Charsets.UTF_16LE
        }
        return null
    }

    private data class XmlText(
        val text: String,
        val charset: Charset,
        val hasUtf8Bom: Boolean
    )

    private data class RewriteResult(
        val text: String,
        val count: Int
    )
}
