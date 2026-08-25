package saien.someday.ui.notes

import saien.someday.domain.media.SomedayAssetUri

enum class MarkdownToolbarAction(
    val label: String,
    val syntaxName: String,
) {
    Heading(label = "Heading", syntaxName = "heading"),
    Bold(label = "Bold", syntaxName = "bold"),
    Italic(label = "Italic", syntaxName = "italic"),
    List(label = "List", syntaxName = "list"),
    Quote(label = "Quote", syntaxName = "quote"),
    CodeBlock(label = "Code block", syntaxName = "code-block"),
    Link(label = "Link", syntaxName = "link"),
    Image(label = "Image", syntaxName = "image"),
}

val markdownToolbarActions: List<MarkdownToolbarAction> = listOf(
    MarkdownToolbarAction.Heading,
    MarkdownToolbarAction.Bold,
    MarkdownToolbarAction.Italic,
    MarkdownToolbarAction.List,
    MarkdownToolbarAction.Quote,
    MarkdownToolbarAction.CodeBlock,
    MarkdownToolbarAction.Link,
    MarkdownToolbarAction.Image,
)

data class MarkdownEditResult(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
) {
    val selectedText: String
        get() {
            val start = selectionStart.coerceIn(0, text.length)
            val end = selectionEnd.coerceIn(start, text.length)
            return text.substring(start, end)
        }
}

fun applyMarkdownToolbarAction(
    source: String,
    selectionStart: Int,
    selectionEnd: Int,
    action: MarkdownToolbarAction,
): MarkdownEditResult {
    val selection = MarkdownTextSelection(selectionStart, selectionEnd).coerceTo(source.length)
    return when (action) {
        MarkdownToolbarAction.Heading -> prefixSelection(source, selection, "# ", "Heading")
        MarkdownToolbarAction.Bold -> wrapSelection(source, selection, "**", "**", "bold text")
        MarkdownToolbarAction.Italic -> wrapSelection(source, selection, "*", "*", "italic text")
        MarkdownToolbarAction.List -> prefixSelection(source, selection, "- ", "List item")
        MarkdownToolbarAction.Quote -> prefixSelection(source, selection, "> ", "Quoted text")
        MarkdownToolbarAction.CodeBlock -> wrapSelection(source, selection, "```\n", "\n```", "code")
        MarkdownToolbarAction.Link -> linkSelection(source, selection)
        // Image insertion needs a durable imported identity and is handled by
        // insertImportedMarkdownImage after MediaImportRunner succeeds.
        MarkdownToolbarAction.Image -> MarkdownEditResult(
            text = source,
            selectionStart = selection.start,
            selectionEnd = selection.end,
        )
    }
}

/** Inserts a durable app-owned image reference as a standalone Markdown block. */
fun insertImportedMarkdownImage(
    source: String,
    selectionStart: Int,
    selectionEnd: Int,
    assetUri: SomedayAssetUri,
    suggestedAltText: String = "",
): MarkdownEditResult {
    val selection = MarkdownTextSelection(selectionStart, selectionEnd).coerceTo(source.length)
    val selectedAltText = source.substring(selection.start, selection.end)
    val altText = escapeMarkdownImageAltText(
        selectedAltText.takeIf { it.isNotBlank() } ?: suggestedAltText,
    )
    val markdown = "![$altText]($assetUri)"
    val prefix = if (selection.start > 0 && source[selection.start - 1] != '\n') "\n" else ""
    val suffix = if (selection.end < source.length && source[selection.end] != '\n') "\n" else ""
    val replacement = prefix + markdown + suffix
    val text = source.replaceRange(selection.start, selection.end, replacement)
    val cursor = selection.start + replacement.length
    return MarkdownEditResult(
        text = text,
        selectionStart = cursor,
        selectionEnd = cursor,
    )
}

fun markdownEditorCapabilityLog(): String =
    "markdown-source=plain-text preview=toggle " +
        "toolbar=${markdownToolbarActions.joinToString("|") { it.syntaxName }} " +
        "wysiwyg-assist=live-edit-preview+selection-aware-toolbar+preview-feedback " +
        "images=app-owned-assets+local-preview+user-requested-materialization"

enum class MarkdownInlineKind {
    Text,
    Bold,
    Italic,
    Link,
}

data class MarkdownPreviewInline(
    val kind: MarkdownInlineKind,
    val text: String,
    val destination: String? = null,
)

sealed interface MarkdownPreviewBlock {
    val plainText: String

    data class Heading(
        val level: Int,
        val inlines: List<MarkdownPreviewInline>,
    ) : MarkdownPreviewBlock {
        override val plainText: String = inlines.toPlainText()
    }

    data class Paragraph(
        val inlines: List<MarkdownPreviewInline>,
    ) : MarkdownPreviewBlock {
        override val plainText: String = inlines.toPlainText()
    }

    data class ListItem(
        val inlines: List<MarkdownPreviewInline>,
    ) : MarkdownPreviewBlock {
        override val plainText: String = inlines.toPlainText()
    }

    data class Quote(
        val inlines: List<MarkdownPreviewInline>,
    ) : MarkdownPreviewBlock {
        override val plainText: String = inlines.toPlainText()
    }

    data class CodeBlock(
        val code: String,
    ) : MarkdownPreviewBlock {
        override val plainText: String = code
    }

    /**
     * A standalone Markdown image. The destination remains opaque at this
     * layer: product code resolves `someday-asset://` references, while remote
     * URLs can be rejected or rendered as an unavailable placeholder without
     * making the parser perform IO.
     */
    data class Image(
        val altText: String,
        val destination: String,
    ) : MarkdownPreviewBlock {
        override val plainText: String = altText
        val localAssetUri: SomedayAssetUri? = SomedayAssetUri.parseOrNull(destination)
    }
}

enum class MarkdownEditSpanKind {
    Syntax,
    Heading1,
    Heading2,
    Heading3,
    Bold,
    Italic,
    LinkLabel,
    LinkDestination,
    QuoteText,
    InlineCode,
    CodeFence,
    CodeBlock,
}

data class MarkdownEditSpan(
    val kind: MarkdownEditSpanKind,
    val start: Int,
    val end: Int,
)

fun markdownEditPreviewSpans(source: String): List<MarkdownEditSpan> {
    if (source.isEmpty()) {
        return emptyList()
    }

    val spans = mutableListOf<MarkdownEditSpan>()
    var lineStart = 0
    var insideCodeBlock = false

    while (lineStart < source.length) {
        val newline = source.indexOf('\n', startIndex = lineStart)
        val lineEnd = if (newline == -1) source.length else newline
        val line = source.substring(lineStart, lineEnd)
        val contentOffset = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: line.length
        val contentStart = lineStart + contentOffset
        val trimmed = line.drop(contentOffset)

        when {
            trimmed.startsWith("```") -> {
                spans.addEditSpan(MarkdownEditSpanKind.CodeFence, contentStart, lineEnd)
                insideCodeBlock = !insideCodeBlock
            }

            insideCodeBlock -> spans.addEditSpan(MarkdownEditSpanKind.CodeBlock, lineStart, lineEnd)

            headingLevelFor(trimmed) != null -> {
                val level = checkNotNull(headingLevelFor(trimmed))
                val textStart = (contentStart + level + 1).coerceAtMost(lineEnd)
                spans.addEditSpan(MarkdownEditSpanKind.Syntax, contentStart, textStart)
                spans.addEditSpan(level.toMarkdownEditHeadingKind(), textStart, lineEnd)
                spans.addInlineMarkdownSpans(source, textStart, lineEnd)
            }

            trimmed.startsWith("> ") -> {
                val textStart = (contentStart + 2).coerceAtMost(lineEnd)
                spans.addEditSpan(MarkdownEditSpanKind.Syntax, contentStart, textStart)
                spans.addEditSpan(MarkdownEditSpanKind.QuoteText, textStart, lineEnd)
                spans.addInlineMarkdownSpans(source, textStart, lineEnd)
            }

            unorderedListMarkerLength(trimmed) != null -> {
                val markerLength = checkNotNull(unorderedListMarkerLength(trimmed))
                val textStart = (contentStart + markerLength).coerceAtMost(lineEnd)
                spans.addEditSpan(MarkdownEditSpanKind.Syntax, contentStart, textStart)
                spans.addInlineMarkdownSpans(source, textStart, lineEnd)
            }

            orderedListMarkerLength(trimmed) != null -> {
                val markerLength = checkNotNull(orderedListMarkerLength(trimmed))
                val textStart = (contentStart + markerLength).coerceAtMost(lineEnd)
                spans.addEditSpan(MarkdownEditSpanKind.Syntax, contentStart, textStart)
                spans.addInlineMarkdownSpans(source, textStart, lineEnd)
            }

            else -> spans.addInlineMarkdownSpans(source, lineStart, lineEnd)
        }

        if (newline == -1) {
            break
        }
        lineStart = newline + 1
    }

    return spans
}

fun renderMarkdownPreview(source: String): List<MarkdownPreviewBlock> {
    if (source.isEmpty()) {
        return emptyList()
    }

    val lines = source.split('\n')
    val blocks = mutableListOf<MarkdownPreviewBlock>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()

        when {
            line.isBlank() -> index += 1

            trimmed.startsWith("```") -> {
                val codeLines = mutableListOf<String>()
                index += 1
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                    codeLines += lines[index]
                    index += 1
                }
                if (index < lines.size) {
                    index += 1
                }
                blocks += MarkdownPreviewBlock.CodeBlock(codeLines.joinToString("\n"))
            }

            parseStandaloneMarkdownImage(trimmed) != null -> {
                blocks += checkNotNull(parseStandaloneMarkdownImage(trimmed))
                index += 1
            }

            headingLevelFor(trimmed) != null -> {
                val level = checkNotNull(headingLevelFor(trimmed))
                val headingText = trimmed.drop(level + 1)
                blocks += MarkdownPreviewBlock.Heading(level, parseMarkdownInlines(headingText))
                index += 1
            }

            trimmed.startsWith("- ") -> {
                blocks += MarkdownPreviewBlock.ListItem(parseMarkdownInlines(trimmed.removePrefix("- ")))
                index += 1
            }

            trimmed.startsWith("> ") -> {
                blocks += MarkdownPreviewBlock.Quote(parseMarkdownInlines(trimmed.removePrefix("> ")))
                index += 1
            }

            else -> {
                val paragraphLines = mutableListOf(line)
                index += 1
                while (index < lines.size && !lines[index].isBlank() && !isMarkdownBlockStart(lines[index])) {
                    paragraphLines += lines[index]
                    index += 1
                }
                blocks += MarkdownPreviewBlock.Paragraph(
                    parseMarkdownInlines(paragraphLines.joinToString("\n")),
                )
            }
        }
    }

    return blocks
}

private fun parseStandaloneMarkdownImage(line: String): MarkdownPreviewBlock.Image? {
    if (!line.startsWith("![")) return null
    val labelEnd = line.indexOfUnescaped(']', startIndex = 2)
    if (labelEnd < 2 || line.getOrNull(labelEnd + 1) != '(' || !line.endsWith(')')) return null
    val destination = line.substring(labelEnd + 2, line.lastIndex).trim()
    if (destination.isEmpty() || destination.any { it == '\n' || it == '\r' }) return null
    return MarkdownPreviewBlock.Image(
        altText = unescapeMarkdownImageAltText(line.substring(2, labelEnd)),
        destination = destination,
    )
}

private fun escapeMarkdownImageAltText(value: String): String =
    value
        .replace(Regex("\\s+"), " ")
        .trim()
        .replace("\\", "\\\\")
        .replace("]", "\\]")

private fun unescapeMarkdownImageAltText(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        if (value[index] == '\\' && index + 1 < value.length && value[index + 1] in charArrayOf('\\', ']')) {
            append(value[index + 1])
            index += 2
        } else {
            append(value[index])
            index += 1
        }
    }
}

private fun String.indexOfUnescaped(
    character: Char,
    startIndex: Int,
): Int {
    var escaped = false
    for (index in startIndex until length) {
        val current = this[index]
        if (!escaped && current == character) return index
        escaped = !escaped && current == '\\'
        if (current != '\\') escaped = false
    }
    return -1
}

fun parseMarkdownInlines(text: String): List<MarkdownPreviewInline> {
    val inlines = mutableListOf<MarkdownPreviewInline>()
    var index = 0

    fun addText(value: String) {
        if (value.isNotEmpty()) {
            inlines += MarkdownPreviewInline(MarkdownInlineKind.Text, value)
        }
    }

    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", startIndex = index + 2)
                if (end > index + 2) {
                    inlines += MarkdownPreviewInline(
                        kind = MarkdownInlineKind.Bold,
                        text = text.substring(index + 2, end),
                    )
                    index = end + 2
                } else {
                    addText(text[index].toString())
                    index += 1
                }
            }

            text[index] == '*' -> {
                val end = text.indexOf('*', startIndex = index + 1)
                if (end > index + 1) {
                    inlines += MarkdownPreviewInline(
                        kind = MarkdownInlineKind.Italic,
                        text = text.substring(index + 1, end),
                    )
                    index = end + 1
                } else {
                    addText(text[index].toString())
                    index += 1
                }
            }

            text[index] == '[' -> {
                val labelEnd = text.indexOf(']', startIndex = index + 1)
                val destinationStart = labelEnd + 1
                if (
                    labelEnd > index + 1 &&
                    text.getOrNull(destinationStart) == '('
                ) {
                    val destinationEnd = text.indexOf(')', startIndex = destinationStart + 1)
                    if (destinationEnd > destinationStart + 1) {
                        inlines += MarkdownPreviewInline(
                            kind = MarkdownInlineKind.Link,
                            text = text.substring(index + 1, labelEnd),
                            destination = text.substring(destinationStart + 1, destinationEnd),
                        )
                        index = destinationEnd + 1
                    } else {
                        addText(text[index].toString())
                        index += 1
                    }
                } else {
                    addText(text[index].toString())
                    index += 1
                }
            }

            else -> {
                val nextSpecial = nextSpecialIndex(text, index)
                addText(text.substring(index, nextSpecial))
                index = nextSpecial
            }
        }
    }

    return inlines.mergeAdjacentText()
}

private fun MutableList<MarkdownEditSpan>.addInlineMarkdownSpans(
    source: String,
    start: Int,
    end: Int,
) {
    var index = start
    while (index < end) {
        when {
            source.startsWith("**", index) -> {
                val close = source.indexOf("**", startIndex = index + 2).takeIf { it in (index + 3) until end }
                if (close != null) {
                    addEditSpan(MarkdownEditSpanKind.Syntax, index, index + 2)
                    addEditSpan(MarkdownEditSpanKind.Bold, index + 2, close)
                    addEditSpan(MarkdownEditSpanKind.Syntax, close, close + 2)
                    index = close + 2
                } else {
                    index += 2
                }
            }

            source[index] == '*' -> {
                val close = source.indexOf('*', startIndex = index + 1).takeIf { it in (index + 2) until end }
                if (close != null) {
                    addEditSpan(MarkdownEditSpanKind.Syntax, index, index + 1)
                    addEditSpan(MarkdownEditSpanKind.Italic, index + 1, close)
                    addEditSpan(MarkdownEditSpanKind.Syntax, close, close + 1)
                    index = close + 1
                } else {
                    index += 1
                }
            }

            source[index] == '`' -> {
                val close = source.indexOf('`', startIndex = index + 1).takeIf { it in (index + 2) until end }
                if (close != null) {
                    addEditSpan(MarkdownEditSpanKind.Syntax, index, index + 1)
                    addEditSpan(MarkdownEditSpanKind.InlineCode, index + 1, close)
                    addEditSpan(MarkdownEditSpanKind.Syntax, close, close + 1)
                    index = close + 1
                } else {
                    index += 1
                }
            }

            source[index] == '[' -> {
                val labelEnd = source.indexOf(']', startIndex = index + 1)
                val destinationStart = labelEnd + 1
                val destinationEnd = source.indexOf(')', startIndex = destinationStart + 1)
                if (
                    labelEnd in (index + 2) until end &&
                    source.getOrNull(destinationStart) == '(' &&
                    destinationEnd in (destinationStart + 2) until end
                ) {
                    addEditSpan(MarkdownEditSpanKind.Syntax, index, index + 1)
                    addEditSpan(MarkdownEditSpanKind.LinkLabel, index + 1, labelEnd)
                    addEditSpan(MarkdownEditSpanKind.Syntax, labelEnd, destinationStart + 1)
                    addEditSpan(MarkdownEditSpanKind.LinkDestination, destinationStart + 1, destinationEnd)
                    addEditSpan(MarkdownEditSpanKind.Syntax, destinationEnd, destinationEnd + 1)
                    index = destinationEnd + 1
                } else {
                    index += 1
                }
            }

            else -> index += 1
        }
    }
}

private fun MutableList<MarkdownEditSpan>.addEditSpan(
    kind: MarkdownEditSpanKind,
    start: Int,
    end: Int,
) {
    if (start < end) {
        add(MarkdownEditSpan(kind, start, end))
    }
}

private fun Int.toMarkdownEditHeadingKind(): MarkdownEditSpanKind =
    when (this) {
        1 -> MarkdownEditSpanKind.Heading1
        2 -> MarkdownEditSpanKind.Heading2
        else -> MarkdownEditSpanKind.Heading3
    }

private fun unorderedListMarkerLength(trimmedLine: String): Int? =
    if (
        trimmedLine.length >= 2 &&
        (trimmedLine[0] == '-' || trimmedLine[0] == '*') &&
        trimmedLine[1] == ' '
    ) {
        2
    } else {
        null
    }

private fun orderedListMarkerLength(trimmedLine: String): Int? {
    val digitCount = trimmedLine.takeWhile { it.isDigit() }.length
    return if (
        digitCount > 0 &&
        trimmedLine.getOrNull(digitCount) == '.' &&
        trimmedLine.getOrNull(digitCount + 1) == ' '
    ) {
        digitCount + 2
    } else {
        null
    }
}

private data class MarkdownTextSelection(
    val start: Int,
    val end: Int,
) {
    fun coerceTo(length: Int): MarkdownTextSelection {
        val coercedStart = start.coerceIn(0, length)
        val coercedEnd = end.coerceIn(0, length)
        return if (coercedStart <= coercedEnd) {
            MarkdownTextSelection(coercedStart, coercedEnd)
        } else {
            MarkdownTextSelection(coercedEnd, coercedStart)
        }
    }
}

private val MarkdownTextSelection.isCollapsed: Boolean
    get() = start == end

private fun wrapSelection(
    source: String,
    selection: MarkdownTextSelection,
    prefix: String,
    suffix: String,
    placeholder: String,
): MarkdownEditResult {
    val selected = source.substring(selection.start, selection.end)
    val content = selected.ifEmpty { placeholder }
    val replacement = "$prefix$content$suffix"
    val text = source.replaceRange(selection.start, selection.end, replacement)
    val selectedStart = selection.start + prefix.length
    return MarkdownEditResult(
        text = text,
        selectionStart = selectedStart,
        selectionEnd = selectedStart + content.length,
    )
}

private fun prefixSelection(
    source: String,
    selection: MarkdownTextSelection,
    prefix: String,
    placeholder: String,
): MarkdownEditResult {
    if (selection.isCollapsed) {
        val replacement = "$prefix$placeholder"
        val text = source.replaceRange(selection.start, selection.end, replacement)
        val selectedStart = selection.start + prefix.length
        return MarkdownEditResult(
            text = text,
            selectionStart = selectedStart,
            selectionEnd = selectedStart + placeholder.length,
        )
    }

    val selected = source.substring(selection.start, selection.end)
    val prefixed = selected
        .split('\n')
        .joinToString("\n") { line ->
            if (line.startsWith(prefix)) line else "$prefix$line"
        }
    val text = source.replaceRange(selection.start, selection.end, prefixed)
    val selectedStart = selection.start + prefix.length
    return MarkdownEditResult(
        text = text,
        selectionStart = selectedStart,
        selectionEnd = selection.start + prefixed.length,
    )
}

private fun linkSelection(
    source: String,
    selection: MarkdownTextSelection,
): MarkdownEditResult {
    val selected = source.substring(selection.start, selection.end)
    val label = selected.ifEmpty { "link text" }
    val replacement = "[$label](https://example.com)"
    val text = source.replaceRange(selection.start, selection.end, replacement)
    val labelStart = selection.start + 1
    return MarkdownEditResult(
        text = text,
        selectionStart = labelStart,
        selectionEnd = labelStart + label.length,
    )
}

private fun headingLevelFor(trimmedLine: String): Int? {
    val level = trimmedLine.takeWhile { it == '#' }.length
    return level.takeIf {
        it in 1..6 && trimmedLine.getOrNull(it) == ' '
    }
}

private fun isMarkdownBlockStart(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("```") ||
        headingLevelFor(trimmed) != null ||
        trimmed.startsWith("- ") ||
        trimmed.startsWith("> ")
}

private fun nextSpecialIndex(
    text: String,
    startIndex: Int,
): Int {
    var next = text.length
    listOf("**", "*", "[").forEach { marker ->
        val candidate = text.indexOf(marker, startIndex = startIndex)
        if (candidate >= 0 && candidate < next) {
            next = candidate
        }
    }
    return if (next == startIndex) startIndex + 1 else next
}

private fun List<MarkdownPreviewInline>.toPlainText(): String =
    joinToString(separator = "") { it.text }

private fun List<MarkdownPreviewInline>.mergeAdjacentText(): List<MarkdownPreviewInline> {
    val merged = mutableListOf<MarkdownPreviewInline>()
    forEach { inline ->
        val previous = merged.lastOrNull()
        if (previous?.kind == MarkdownInlineKind.Text && inline.kind == MarkdownInlineKind.Text) {
            merged[merged.lastIndex] = previous.copy(text = previous.text + inline.text)
        } else {
            merged += inline
        }
    }
    return merged
}
