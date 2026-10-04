package app.urv.manager.ui.component.bundle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundleChangelogFormatTest {
    @Test
    fun exposesDisclosureSummaryAndAllReleaseNotes() {
        val source = """
            <details>
            <summary>Full changelog</summary>

            - First change
            - Last change
            </details>
        """.trimIndent()
        val result = source.sanitizePatchChangelogMarkdown()
        assertTrue("**Full changelog**" in result)
        assertTrue("- First change" in result)
        assertTrue("- Last change" in result)
        assertTrue("<details>" !in result)
        assertTrue("</details>" !in result)
    }

    @Test
    fun preservesHtmlExamplesInFencedCode() {
        val source = "~~~html\n<details>\n<summary>Example</summary>\n</details>\n~~~"
        assertEquals(source, source.sanitizePatchChangelogMarkdown())
    }

    @Test
    fun preservesIndentedListsAndLinksInsideCodeFences() {
        val source = "~~~markdown\n    - Code example\n[[label](https://example.com)]\n~~~"
        assertEquals(source, source.sanitizePatchChangelogMarkdown())
    }

    @Test
    fun handlesInlineDisclosureHeaderAndNestedDetails() {
        val source = "<details open><summary>Outer</summary>\n<details>\n<summary>Inner</summary>\n\nNotes\n</details>\n</details>"
        val result = source.sanitizePatchChangelogMarkdown()
        assertTrue("**Outer**" in result)
        assertTrue("**Inner**" in result)
        assertTrue("Notes" in result)
        assertTrue("<details" !in result)
    }
}
