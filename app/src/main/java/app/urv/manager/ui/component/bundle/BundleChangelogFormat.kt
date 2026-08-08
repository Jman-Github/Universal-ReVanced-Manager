package app.urv.manager.ui.component.bundle

private val doubleBracketLinkRegex =
    Regex("""\[\[([^\]]+)]\(([^)]+)\)]""")

private val listItemMarkerRegex =
    Regex("""^(?:[-+*]|\d+[.)])\s+""")

internal fun String.sanitizePatchChangelogMarkdown(): String {
    val normalizedLists = normalizeCodeIndentedListBlocks()
    return doubleBracketLinkRegex.replace(normalizedLists) { match ->
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
