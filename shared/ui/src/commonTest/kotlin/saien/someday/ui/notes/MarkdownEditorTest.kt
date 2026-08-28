package saien.someday.ui.notes

import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.media.SomedayAssetUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownEditorTest {
    @Test
    fun toolbarActionsCoverCommonMarkdownSyntaxAndImportedImages() {
        assertEquals(
            listOf(
                MarkdownToolbarAction.Heading,
                MarkdownToolbarAction.Bold,
                MarkdownToolbarAction.Italic,
                MarkdownToolbarAction.List,
                MarkdownToolbarAction.Quote,
                MarkdownToolbarAction.CodeBlock,
                MarkdownToolbarAction.Link,
                MarkdownToolbarAction.Image,
            ),
            markdownToolbarActions,
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

        val imageRequiresImportedIdentity = applyMarkdownToolbarAction(
            "unchanged",
            0,
            9,
            MarkdownToolbarAction.Image,
        )
        assertEquals("unchanged", imageRequiresImportedIdentity.text)
    }

    @Test
    fun importedImageInsertionUsesCanonicalIdentityAndCurrentSelection() {
        val assetUri = SomedayAssetUri(MediaAssetId.fromCanonicalValue("ab".repeat(32)))

        val selectedAlt = insertImportedMarkdownImage(
            source = "Before sunset after",
            selectionStart = 7,
            selectionEnd = 13,
            assetUri = assetUri,
            suggestedAltText = "ignored.jpg",
        )

        assertEquals(
            "Before \n![sunset](someday-asset://${"ab".repeat(32)})\n after",
            selectedAlt.text,
        )
        assertEquals(selectedAlt.text.length - " after".length, selectedAlt.selectionStart)
        assertEquals(selectedAlt.selectionStart, selectedAlt.selectionEnd)

        val suggestedAlt = insertImportedMarkdownImage(
            source = "",
            selectionStart = 0,
            selectionEnd = 0,
            assetUri = assetUri,
            suggestedAltText = "Family ] photo.jpg",
        )
        assertEquals(
            "![Family \\] photo.jpg](someday-asset://${"ab".repeat(32)})",
            suggestedAlt.text,
        )
        assertEquals("Family ] photo.jpg", renderMarkdownPreview(suggestedAlt.text).single().plainText)
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
    fun previewParsesStandaloneOpaqueAssetImagesWithoutPerformingIo() {
        val blocks = renderMarkdownPreview(
            "![Sunset](someday-asset://${"01".repeat(32)})",
        )

        val image = assertIs<MarkdownPreviewBlock.Image>(blocks.single())
        assertEquals("Sunset", image.altText)
        assertEquals("someday-asset://${"01".repeat(32)}", image.destination)
        assertEquals("Sunset", image.plainText)
        assertEquals("01".repeat(32), image.localAssetUri?.assetId?.value)

        val remote = assertIs<MarkdownPreviewBlock.Image>(
            renderMarkdownPreview("![Remote](https://example.com/image.jpg)").single(),
        )
        assertEquals(null, remote.localAssetUri)
    }

    @Test
    fun previewKeepsToolbarInsertedImageStandaloneAfterText() {
        val assetUri = SomedayAssetUri(MediaAssetId.fromCanonicalValue("02".repeat(32)))
        val inserted = insertImportedMarkdownImage(
            source = "Before image",
            selectionStart = "Before image".length,
            selectionEnd = "Before image".length,
            assetUri = assetUri,
            suggestedAltText = "Photo",
        )

        val blocks = renderMarkdownPreview(inserted.text)

        assertEquals("Before image", assertIs<MarkdownPreviewBlock.Paragraph>(blocks[0]).plainText)
        val image = assertIs<MarkdownPreviewBlock.Image>(blocks[1])
        assertEquals("Photo", image.altText)
        assertEquals(assetUri, image.localAssetUri)
    }

    @Test
    fun malformedImageSyntaxRemainsPlainText() {
        val block = assertIs<MarkdownPreviewBlock.Paragraph>(
            renderMarkdownPreview("![missing destination]()").single(),
        )

        assertEquals("![missing destination]()", block.plainText)
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
    fun markdownEditorCapabilitiesAdvertiseImportedAssetImages() {
        val log = markdownEditorCapabilityLog()

        assertTrue(log.contains("markdown-source=plain-text"))
        assertTrue(log.contains("preview=toggle"))
        assertTrue(log.contains("toolbar=heading|bold|italic|list|quote|code-block|link|image"))
        assertTrue(log.contains("wysiwyg-assist=live-edit-preview+selection-aware-toolbar+preview-feedback"))
        assertTrue(log.contains("images=app-owned-assets+local-preview+user-requested-materialization"))
        assertFalse(log.contains("attachments=absent"))
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
