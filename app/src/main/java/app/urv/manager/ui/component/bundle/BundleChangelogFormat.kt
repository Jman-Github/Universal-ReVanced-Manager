package app.urv.manager.ui.component.bundle

private val doubleBracketLinkRegex =
    Regex("""\[\[([^\]]+)]\(([^)]+)\)]""")

private val listItemMarkerRegex =
    Regex("""^(?:[-+*]|\d+[.)])\s+""")

internal fun String.sanitizePatchChangelogMarkdown(): String =
    transformChangelogProse { prose ->
        val expanded = prose.lineSequence().joinToString("\n") { line ->
            line.replace(detailsTagRegex, "")
                .replace(summaryTagRegex) { match -> "\n**${match.groupValues[1]}**\n" }
        }
        doubleBracketLinkRegex.replace(expanded.normalizeCodeIndentedListBlocks()) { match ->
            val label = match.groupValues[1]
            val link = match.groupValues[2]
            "[\\[$label\\]]($link)"
        }
    }

private fun String.normalizeCodeIndentedListBlocks(): String {
    val lines = split('\n')
    val normalized = mutableListOf<String>()
    var index = 0

    while (index < lines.size) {
        if (lines.startsCodeIndentedListBlockAt(index)) {
            val blockEnd = lines.nextBlankLineIndex(index)
            for (blockIndex in index until blockEnd) {
                normalized += lines[blockIndex].removeCodeBlockIndent()
            }
            index = blockEnd
        } else {
            normalized += lines[index]
            index++
        }
    }

    return normalized.joinToString("\n")
}

private fun List<String>.startsCodeIndentedListBlockAt(index: Int): Boolean {
    val line = get(index)
    val lineIndent = line.listItemIndentWidth() ?: return false
    if (lineIndent < CODE_BLOCK_INDENT_WIDTH) return false
    if (!line.removeCodeBlockIndent().startsWithListItemMarker()) return false

    val previousListIndent = previousNonBlankLine(index)?.listItemIndentWidth()
    if (previousListIndent != null && previousListIndent < lineIndent) return false

    return subList(index, nextBlankLineIndex(index)).all { candidate ->
        candidate.isBlank() || candidate.leadingIndentWidth() >= CODE_BLOCK_INDENT_WIDTH
    }
}

private fun List<String>.nextBlankLineIndex(startIndex: Int): Int {
    var index = startIndex
    while (index < size && this[index].isNotBlank()) {
        index++
    }
    return index
}

private fun List<String>.previousNonBlankLine(startIndex: Int): String? =
    asSequence()
        .take(startIndex)
        .toList()
        .asReversed()
        .firstOrNull { it.isNotBlank() }

private fun String.listItemIndentWidth(): Int? {
    val contentStart = indexOfFirst { it != ' ' && it != '\t' }
        .takeUnless { it == -1 }
        ?: return null
    return if (substring(contentStart).startsWithListItemMarker()) {
        substring(0, contentStart).indentWidth()
    } else {
        null
    }
}

private fun String.leadingIndentWidth(): Int {
    val contentStart = indexOfFirst { it != ' ' && it != '\t' }
        .takeUnless { it == -1 }
        ?: length
    return substring(0, contentStart).indentWidth()
}

private fun String.indentWidth(): Int =
    sumOf { char -> if (char == '\t') CODE_BLOCK_INDENT_WIDTH else 1 }

private fun String.startsWithListItemMarker(): Boolean =
    listItemMarkerRegex.containsMatchIn(this)

private fun String.removeCodeBlockIndent(): String {
    var columns = 0
    var index = 0
    while (index < length && columns < CODE_BLOCK_INDENT_WIDTH) {
        when (this[index]) {
            ' ' -> {
                columns++
                index++
            }
            '\t' -> {
                columns = CODE_BLOCK_INDENT_WIDTH
                index++
            }
            else -> return this
        }
    }
    return substring(index)
}

private const val CODE_BLOCK_INDENT_WIDTH = 4

/**
 * The renderer does not render HTML blocks. Expose GitHub disclosure contents as
 * ordinary Markdown, while leaving fenced code examples untouched.
 */
private fun String.transformChangelogProse(transform: (String) -> String): String {
    val output = mutableListOf<String>()
    val prose = mutableListOf<String>()
    var fenceChar: Char? = null
    var fenceLength = 0

    fun flushProse() {
        if (prose.isNotEmpty()) {
            output += transform(prose.joinToString("\n"))
            prose.clear()
        }
    }

    split('\n').forEach { line ->
        val trimmed = line.trimStart(' ')
        val marker = trimmed.firstOrNull()
        val markerLength = trimmed.takeWhile { it == marker }.length
        val isFence = line.length - trimmed.length <= 3 &&
            (marker == '`' || marker == '~') && markerLength >= 3
        if (fenceChar != null) {
            output += line
            if (isFence && marker == fenceChar && markerLength >= fenceLength &&
                trimmed.drop(markerLength).isBlank()
            ) {
                fenceChar = null
            }
        } else if (isFence) {
            flushProse()
            fenceChar = marker
            fenceLength = markerLength
            output += line
        } else {
            prose += line
        }
    }
    flushProse()
    return output.joinToString("\n")
}

private val detailsTagRegex =
    Regex("""^ {0,3}</?details\b[^>]*>[ \t]*|[ \t]*</details>[ \t]*$""", RegexOption.IGNORE_CASE)
private val summaryTagRegex =
    Regex("""^ {0,3}<summary\b[^>]*>(.*?)</summary>[ \t]*$""", RegexOption.IGNORE_CASE)
