package app.revanced.manager.patcher.util

import app.revanced.manager.patcher.logger.Logger
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.Charset
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Cleans decoded manifest entries that break repackaging, removes unsupported raw
 * shared-library resource references, and normalizes raw decimal resource ids like
 * @1427046400 into @0x550f0000.
 */
object ManifestDecimalResourceReferenceSanitizer {
    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private val decimalResourceReferenceRegex = Regex("=(\\s*)(['\"])@(\\d+)\\2")
    private val rawResourceElementRegex = Regex(
        """<(meta-data|property)\b[^>]*?\bandroid:resource\s*=\s*(['"])(@(?:0x[0-9a-fA-F]+|\d+))\2[^>]*?(?:/>|>[\s\S]*?</\1\s*>)""",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val androidNameAttributeRegex = Regex("""\b(?:android:)?name\s*=\s*(['"])([^'"]+)\1""")

    fun sanitize(apkDir: File, logger: Logger? = null): Int {
        if (!apkDir.exists()) return 0

        val manifest = apkDir.resolve("AndroidManifest.xml")
        if (!manifest.isFile) return 0

        val source = readXmlText(manifest) ?: return 0
        val textCleaned = stripUnsupportedRawResourceElements(source.text)
        val cleaned = removeUnsupportedManifestEntries(textCleaned.text)
        val rewritten = rewriteDecimalResourceReferences(cleaned.text)
        val removedEntries = textCleaned.removedEntries + cleaned.removedEntries
        if (removedEntries.isEmpty() && rewritten.count == 0) return 0

        writeXmlText(
            file = manifest,
            text = rewritten.text,
            charset = source.charset,
            hasUtf8Bom = source.hasUtf8Bom
        )
        if (removedEntries.isNotEmpty()) {
            logger?.warn(
                "Removed ${removedEntries.size} unsupported decoded manifest entr" +
                    if (removedEntries.size == 1) "y" else "ies" +
                    " in ${manifest.absolutePath}: " +
                    removedEntries.joinToString()
            )
        }
        if (rewritten.count > 0) {
            logger?.warn(
                "Normalized ${rewritten.count} decimal manifest resource reference(s) in " +
                    "${manifest.absolutePath}: " +
                    rewritten.replacements.joinToString { "${it.first} -> ${it.second}" }
            )
        }
        return removedEntries.size + rewritten.count
    }

    private fun rewriteDecimalResourceReferences(text: String): RewriteResult {
        var replaced = 0
        val replacements = mutableListOf<Pair<String, String>>()
        val rewritten = decimalResourceReferenceRegex.replace(text) { match ->
            val decimalValue = match.groupValues[3].toLongOrNull() ?: return@replace match.value
            if (decimalValue < 0) return@replace match.value

            val normalized = "@0x${decimalValue.toString(16).padStart(8, '0')}"
                .lowercase(Locale.ROOT)
            replaced += 1
            replacements += "@${match.groupValues[3]}" to normalized
            "=${match.groupValues[1]}${match.groupValues[2]}$normalized${match.groupValues[2]}"
        }
        return RewriteResult(rewritten, replaced, replacements)
    }

    private fun stripUnsupportedRawResourceElements(text: String): CleanupResult {
        val removedEntries = mutableListOf<String>()
        val rewritten = rawResourceElementRegex.replace(text) { match ->
            val resourceValue = match.groupValues[3]
            if (!isUnsupportedRawResourceReference(resourceValue)) return@replace match.value

            val tagName = match.groupValues[1]
            val nameValue = androidNameAttributeRegex.find(match.value)?.groupValues?.getOrNull(2).orEmpty()
            removedEntries += buildString {
                append(tagName)
                if (nameValue.isNotBlank()) {
                    append(':')
                    append(nameValue)
                }
                append("@android:resource=")
                append(resourceValue)
            }
            ""
        }
        return CleanupResult(rewritten, removedEntries)
    }

    private fun removeUnsupportedManifestEntries(text: String): CleanupResult {
        val document = parseXml(text) ?: return CleanupResult(text, emptyList())
        val removedEntries = mutableListOf<String>()
        val manifest = document.documentElement ?: return CleanupResult(text, emptyList())
        val application = directChildElement(manifest, "application")

        listOf(manifest, application).forEach { element ->
            element ?: return@forEach
            removedEntries += removeNamedAttributes(
                element,
                "splitName",
                "split",
                "requiredSplitTypes",
                "splitTypes",
                "isSplitRequired"
            )
        }

        removedEntries += removeElementsByTagName(manifest, "uses-split")
        if (application != null) {
            removedEntries += removeUnsupportedMetadata(application)
            removedEntries += removeUnsupportedRawResourceEntries(application)
        }
        if (removedEntries.isEmpty()) return CleanupResult(text, emptyList())

        return CleanupResult(serializeXml(document), removedEntries)
    }

    private fun removeUnsupportedMetadata(application: Element): List<String> {
        val removedEntries = mutableListOf<String>()
        val nodes = application.childNodes
        for (index in nodes.length - 1 downTo 0) {
            val child = nodes.item(index) as? Element ?: continue
            if (!child.tagNameMatches("meta-data")) continue
            val nameValue = child.getAttributeNS(ANDROID_NAMESPACE, "name")
                .ifBlank { child.getAttribute("android:name") }
                .ifBlank { child.getAttribute("name") }
                .ifBlank { continue }
            val shouldRemove = when {
                nameValue == "com.android.dynamic.apk.fused.modules" -> {
                    val value = child.getAttributeNS(ANDROID_NAMESPACE, "value")
                        .ifBlank { child.getAttribute("android:value") }
                        .ifBlank { child.getAttribute("value") }
                    value == "base"
                }
                nameValue.startsWith("com.android.vending.") -> true
                nameValue.startsWith("com.android.stamp.") -> true
                else -> false
            }
            if (!shouldRemove) continue
            removedEntries += "meta-data:$nameValue"
            application.removeChild(child)
        }
        return removedEntries
    }

    private fun removeUnsupportedRawResourceEntries(root: Element): List<String> {
        val removedEntries = mutableListOf<String>()
        descendantElements(root)
            .asReversed()
            .forEach { element ->
                val resourceValue = androidAttributeValue(element, "resource").ifBlank { return@forEach }
                if (!isUnsupportedRawResourceReference(resourceValue)) return@forEach

                val parent = element.parentNode
                val descriptor = elementResourceDescriptor(element, resourceValue)
                if (element.tagNameMatches("meta-data") || element.tagNameMatches("property")) {
                    if (parent != null) {
                        parent.removeChild(element)
                        removedEntries += descriptor
                    }
                    return@forEach
                }

                if (removeAndroidAttribute(element, "resource")) {
                    removedEntries += descriptor
                }
            }
        return removedEntries
    }

    private fun descendantElements(root: Element): List<Element> {
        val elements = mutableListOf<Element>()

        fun collect(element: Element) {
            elements += element
            val nodes = element.childNodes
            for (index in 0 until nodes.length) {
                val child = nodes.item(index) as? Element ?: continue
                collect(child)
            }
        }

        collect(root)
        return elements
    }

    private fun elementResourceDescriptor(element: Element, resourceValue: String): String {
        val nameValue = androidAttributeValue(element, "name")
        return buildString {
            append(element.tagName)
            if (nameValue.isNotBlank()) {
                append(':')
                append(nameValue)
            }
            append("@android:resource=")
            append(resourceValue)
        }
    }

    private fun androidAttributeValue(element: Element, name: String): String =
        element.getAttributeNS(ANDROID_NAMESPACE, name)
            .ifBlank { element.getAttribute("android:$name") }
            .ifBlank { element.getAttribute(name) }

    private fun removeAndroidAttribute(element: Element, name: String): Boolean {
        var removed = false
        if (element.hasAttributeNS(ANDROID_NAMESPACE, name)) {
            element.removeAttributeNS(ANDROID_NAMESPACE, name)
            removed = true
        }
        if (element.hasAttribute("android:$name")) {
            element.removeAttribute("android:$name")
            removed = true
        }
        if (element.hasAttribute(name)) {
            element.removeAttribute(name)
            removed = true
        }
        return removed
    }

    private fun isUnsupportedRawResourceReference(value: String): Boolean {
        val parsed = parseRawResourceReference(value) ?: return false
        val packageId = ((parsed ushr 24) and 0xFF).toInt()
        return packageId != 0x01 && packageId != 0x7f
    }

    private fun parseRawResourceReference(value: String): Long? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("@")) return null
        val body = trimmed.removePrefix("@")
        if (body.contains('/') || body.contains(':') || body.contains('?')) return null
        return when {
            body.startsWith("0x", ignoreCase = true) -> body.substring(2).toLongOrNull(16)
            body.all(Char::isDigit) -> body.toLongOrNull()
            else -> null
        }?.takeIf { it in 0..0xFFFF_FFFFL }
    }

    private fun removeNamedAttributes(element: Element, vararg names: String): List<String> {
        val removedEntries = mutableListOf<String>()
        names.forEach { name ->
            if (element.hasAttributeNS(ANDROID_NAMESPACE, name)) {
                element.removeAttributeNS(ANDROID_NAMESPACE, name)
                removedEntries += "${element.tagName}:android:$name"
            }
            if (element.hasAttribute(name)) {
                element.removeAttribute(name)
                removedEntries += "${element.tagName}:$name"
            }
        }
        return removedEntries
    }

    private fun removeElementsByTagName(root: Element, tagName: String): List<String> {
        val removedEntries = mutableListOf<String>()
        val nodes = root.childNodes
        for (index in nodes.length - 1 downTo 0) {
            val child = nodes.item(index) as? Element ?: continue
            if (!child.tagNameMatches(tagName)) continue
            removedEntries += child.tagName
            root.removeChild(child)
        }
        return removedEntries
    }

    private fun directChildElement(parent: Element, tagName: String): Element? {
        val nodes = parent.childNodes
        for (index in 0 until nodes.length) {
            val child = nodes.item(index) as? Element ?: continue
            if (child.tagNameMatches(tagName)) {
                return child
            }
        }
        return null
    }

    private fun Element.tagNameMatches(expected: String): Boolean {
        if (tagName == expected) return true
        val local = localName
        return local != null && local == expected
    }

    private fun parseXml(text: String): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            setExpandEntityReferences(false)
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
    }.getOrNull()

    private fun serializeXml(document: Document): String {
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.INDENT, "no")
        }.transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
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
        val count: Int,
        val replacements: List<Pair<String, String>>
    )

    private data class CleanupResult(
        val text: String,
        val removedEntries: List<String>
    )
}
