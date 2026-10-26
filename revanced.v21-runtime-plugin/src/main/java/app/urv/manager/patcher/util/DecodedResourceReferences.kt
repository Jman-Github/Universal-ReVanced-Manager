package app.urv.manager.patcher.util

import java.io.File
import java.io.IOException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Index declarations before checking uses, on both the Android and JVM hosts. */
internal object DecodedResourceReferences {
    private val reference = Regex("^@(?:(\\*?[a-zA-Z0-9_.]+):)?([a-zA-Z0-9_]+)/([a-zA-Z0-9_.]+)$")
    private val idDeclaration = Regex("^@\\+id/([a-zA-Z0-9_.]+)$")
    private data class Use(val location: String, val type: String, val name: String)

    fun findMissing(apkDirectory: File): List<String> {
        val resources = apkDirectory.resolve("res")
        val manifest = apkDirectory.resolve("AndroidManifest.xml")
        if (!manifest.isFile || !resources.isDirectory) return emptyList()
        val index = mutableMapOf<String, MutableSet<String>>()
        val xmlFiles = mutableListOf<File>()
        val uses = mutableListOf<Use>()
        fun add(type: String, name: String) {
            if (type.isNotBlank() && name.isNotBlank()) index.getOrPut(type) { mutableSetOf() }.add(name)
        }
        resources.listFiles().orEmpty().filter(File::isDirectory).forEach { directory ->
            val type = directory.name.substringBefore('-')
            directory.listFiles().orEmpty().filter(File::isFile).forEach { file ->
                if (type != "values") {
                    val name = if (file.name.endsWith(".9.png", true)) file.name.dropLast(6)
                        else file.name.substringBeforeLast('.', file.name)
                    add(type, name)
                }
                // raw XML is arbitrary application data, not an Android resource XML document.
                if (file.extension.equals("xml", true) && type != "raw") xmlFiles += file
            }
        }
        for (file in xmlFiles + manifest) {
            val values = file.parentFile?.name?.substringBefore('-') == "values"
            readXml(file) { parser ->
                if (values && parser.depth == 2) {
                    val name = parser.getAttributeValue(null, "name").orEmpty()
                    val type = when {
                        parser.name == "item" -> parser.getAttributeValue(null, "type").orEmpty()
                        // Public IDs have no file payload. Other public entries alone do not prove a resource exists.
                        parser.name == "public" -> if (parser.getAttributeValue(null, "type") == "id") "id" else ""
                        parser.name == "declare-styleable" -> ""
                        parser.name.endsWith("-array") -> "array"
                        else -> parser.name
                    }
                    add(type, name)
                }
                if (values && parser.name == "attr") {
                    parser.getAttributeValue(null, "name")?.takeUnless { ':' in it }?.let { add("attr", it) }
                }
                for (i in 0 until parser.attributeCount) {
                    val value = parser.getAttributeValue(i).trim()
                    idDeclaration.matchEntire(value)?.groupValues?.get(1)?.let { add("id", it) }
                    if (values) continue
                    val match = reference.matchEntire(value) ?: continue
                    if (match.groupValues[1].isNotEmpty()) continue // Framework or another package.
                    val element = listOfNotNull(parser.prefix?.takeIf(String::isNotEmpty), parser.name).joinToString(":")
                    val attribute = listOfNotNull(parser.getAttributePrefix(i)?.takeIf(String::isNotEmpty),
                        parser.getAttributeName(i)).joinToString(":")
                    uses += Use("${file.relativeTo(apkDirectory).invariantSeparatorsPath}: $element@$attribute",
                        match.groupValues[2], match.groupValues[3])
                }
            }
        }
        return uses.filter { index[it.type]?.contains(it.name) != true }
            .map { "${it.location} -> @${it.type}/${it.name}" }.distinct().sorted()
    }

    private fun readXml(file: File, onElement: (XmlPullParser) -> Unit) {
        try {
            file.inputStream().buffered().use { input ->
                val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
                parser.setInput(input, null) // Preserve XML encoding detection, including UTF-16.
                while (true) {
                    when (parser.nextToken()) {
                        XmlPullParser.END_DOCUMENT -> return
                        // Reject declarations before any entity can be used, without JVM-only JAXP features.
                        XmlPullParser.DOCDECL -> throw IOException("DTD declarations are not supported")
                        XmlPullParser.START_TAG -> onElement(parser)
                    }
                }
            }
        } catch (error: Exception) {
            throw IOException("Could not parse decoded resource XML: ${file.path}", error)
        }
    }
}
