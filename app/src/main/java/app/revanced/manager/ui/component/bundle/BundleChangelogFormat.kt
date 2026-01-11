package app.revanced.manager.ui.component.bundle

private val doubleBracketLinkRegex =
    Regex("""\[\[([^\]]+)]\(([^)]+)\)]""")

internal fun String.sanitizePatchChangelogMarkdown(): String =
    doubleBracketLinkRegex.replace(this) { match ->
        val label = match.groupValues[1]
        val link = match.groupValues[2]
        "[\\[$label\\]]($link)"
    }
