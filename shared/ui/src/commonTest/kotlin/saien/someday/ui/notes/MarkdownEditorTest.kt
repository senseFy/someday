package saien.someday.ui.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownEditorTest {
    @Test
    fun toolbarActionsCoverCommonMarkdownSyntaxAndExcludeAttachments() {
        assertEquals(
            listOf(
                MarkdownToolbarAction.Heading,
                MarkdownToolbarAction.Bold,
                MarkdownToolbarAction.Italic,
                MarkdownToolbarAction.List,
                MarkdownToolbarAction.Quote,
                MarkdownToolbarAction.CodeBlock,
                MarkdownToolbarAction.Link,
            ),
            markdownToolbarActions,
        )

        val excludedAttachmentTerms = listOf("attachment", "attach", "image", "file", "upload", "photo")
        assertFalse(
            markdownToolbarActions.any { action ->
                excludedAttachmentTerms.any { term -> action.label.contains(term, ignoreCase = true) }
            },
            "The editor toolbar must not expose image/file attachment insertion",
        )
    }

    @Test
    fun toolbarWrapsSelectionAndInsertsCursorMarkdownWithoutLosingContent() {
        val bold = applyMarkdownToolbarAction("dear diary", 5, 10, MarkdownToolbarAction.Bold)
        assertEquals("dear **diary**", bold.text)
        assertEquals("diary", bold.selectedText)

        val italic = applyMarkdownToolbarAction("quiet morning", 0, 5, MarkdownToolbarAction.Italic)
        assertEquals("*quiet* morning", italic.text)
        assertEquals("quiet", italic.selectedText)

        val heading = applyMarkdownToolbarAction("Daily note", 0, 10, MarkdownToolbarAction.Heading)
        assertEquals("# Daily note", heading.text)
        assertEquals("Daily note", heading.selectedText)

        val list = applyMarkdownToolbarAction("milk\nbread", 0, 10, MarkdownToolbarAction.List)
        assertEquals("- milk\n- bread", list.text)

        val quote = applyMarkdownToolbarAction("remember this", 0, 13, MarkdownToolbarAction.Quote)
        assertEquals("> remember this", quote.text)

        val codeBlock = applyMarkdownToolbarAction("  indented()", 0, 12, MarkdownToolbarAction.CodeBlock)
        assertEquals("```\n  indented()\n```", codeBlock.text)
        assertEquals("  indented()", codeBlock.selectedText)

        val link = applyMarkdownToolbarAction("Someday", 0, 7, MarkdownToolbarAction.Link)
        assertEquals("[Someday](https://example.com)", link.text)
        assertEquals("Someday", link.selectedText)

        val insertedHeading = applyMarkdownToolbarAction("", 0, 0, MarkdownToolbarAction.Heading)
        assertEquals("# Heading", insertedHeading.text)
        assertEquals("Heading", insertedHeading.selectedText)
    }

    @Test
    fun previewParsesCommonSyntaxWithSemanticBlocksAndInlineAssistance() {
        val source = """
            |# Today
            |A **bold** and *gentle* [link](https://example.com).
            |- first memory
            |> quoted reminder
            |```
            |  val x = 1
            |```
        """.trimMargin()

        val blocks = renderMarkdownPreview(source)

        val heading = assertIs<MarkdownPreviewBlock.Heading>(blocks[0])
        assertEquals(1, heading.level)
        assertEquals("Today", heading.plainText)

        val paragraph = assertIs<MarkdownPreviewBlock.Paragraph>(blocks[1])
        assertTrue(paragraph.inlines.any { it.kind == MarkdownInlineKind.Bold && it.text == "bold" })
        assertTrue(paragraph.inlines.any { it.kind == MarkdownInlineKind.Italic && it.text == "gentle" })
        assertTrue(
            paragraph.inlines.any {
                it.kind == MarkdownInlineKind.Link &&
                    it.text == "link" &&
                    it.destination == "https://example.com"
            },
        )

        assertEquals("first memory", assertIs<MarkdownPreviewBlock.ListItem>(blocks[2]).plainText)
        assertEquals("quoted reminder", assertIs<MarkdownPreviewBlock.Quote>(blocks[3]).plainText)
        assertEquals("  val x = 1", assertIs<MarkdownPreviewBlock.CodeBlock>(blocks[4]).code)
    }

    @Test
    fun editPreviewSpansStyleMarkdownWithoutChangingSourceOffsets() {
        val source = """
            |# Today
            |A **bold** and *gentle* [link](https://example.com) with `code`.
            |1. ordered memory
            |- first memory
            |> quoted reminder
            |```
            |  val x = 1
            |```
        """.trimMargin()

        val spans = markdownEditPreviewSpans(source)

        assertHasSpan(source, spans, MarkdownEditSpanKind.Syntax, "# ")
        assertHasSpan(source, spans, MarkdownEditSpanKind.Heading1, "Today")
        assertHasSpan(source, spans, MarkdownEditSpanKind.Bold, "bold")
        assertHasSpan(source, spans, MarkdownEditSpanKind.Italic, "gentle")
        assertHasSpan(source, spans, MarkdownEditSpanKind.LinkLabel, "link")
        assertHasSpan(source, spans, MarkdownEditSpanKind.LinkDestination, "https://example.com")
        assertHasSpan(source, spans, MarkdownEditSpanKind.InlineCode, "code")
        assertHasSpan(source, spans, MarkdownEditSpanKind.Syntax, "1. ")
        assertHasSpan(source, spans, MarkdownEditSpanKind.Syntax, "- ")
        assertHasSpan(source, spans, MarkdownEditSpanKind.QuoteText, "quoted reminder")
        assertHasSpan(source, spans, MarkdownEditSpanKind.CodeFence, "```")
        assertHasSpan(source, spans, MarkdownEditSpanKind.CodeBlock, "  val x = 1")
        assertTrue(spans.all { it.start in 0..source.length && it.end in 0..source.length && it.start < it.end })
    }

    @Test
    fun markdownEditorCapabilitiesAdvertisePreviewToolbarAndNoAttachments() {
        val log = markdownEditorCapabilityLog()

        assertTrue(log.contains("markdown-source=plain-text"))
        assertTrue(log.contains("preview=toggle"))
        assertTrue(log.contains("toolbar=heading|bold|italic|list|quote|code-block|link"))
        assertTrue(log.contains("wysiwyg-assist=live-edit-preview+selection-aware-toolbar+preview-feedback"))
        assertTrue(log.contains("attachments=absent"))
    }

    private fun assertHasSpan(
        source: String,
        spans: List<MarkdownEditSpan>,
        kind: MarkdownEditSpanKind,
        text: String,
    ) {
        val start = source.indexOf(text)
        assertTrue(start >= 0, "Expected source to contain $text")
        assertTrue(
            spans.any { span ->
                span.kind == kind &&
                    span.start <= start &&
                    span.end >= start + text.length
            },
            "Expected $kind span to cover $text",
        )
    }
}
