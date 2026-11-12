package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ComicBlack
import com.example.ui.theme.ComicMutedInk
import com.example.ui.theme.ComicPanelGray
import com.example.ui.theme.ComicWhite

/**
 * Structured representation of parsed Markdown blocks for rendering in both
 * Jetpack Compose (in-app preview) and Jetpack Glance (home screen widget).
 */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class Blockquote(val text: String) : MarkdownBlock
    data class BulletListItem(val indentLevel: Int, val text: String) : MarkdownBlock
    data class OrderedListItem(val numberLabel: String, val indentLevel: Int, val text: String) : MarkdownBlock
    data class TaskListItem(val isChecked: Boolean, val text: String) : MarkdownBlock
    data class TableRowBlock(val cells: List<String>, val isHeader: Boolean) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
}

object MarkdownHelper {

    private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+?)\s*#*$""")
    private val HR_REGEX = Regex("""^\s*(?:-\s*-\s*-[-*\s]*|\*\s*\*\s*\*[-*\s]*|_\s*_\s*_[\s_]*)$""")
    private val TASK_ITEM_REGEX = Regex("""^(\s*)[-*+]\s+\[([ xX])]\s+(.*)$""")
    private val BULLET_ITEM_REGEX = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val ORDERED_ITEM_REGEX = Regex("""^(\s*)(\d+[.)])\s+(.*)$""")
    private val TABLE_SEPARATOR_REGEX = Regex("""^\s*\|?(?:\s*:?-{2,}:?\s*\|)+\s*:?-{2,}:?\s*\|?\s*$""")

    private val INLINE_TOKEN_REGEX = Regex(
        """(`[^`]+`)|(\*\*\*[^*]+\*\*\*|___[^_]+___)|(\*\*[^*]+\*\*|__[^_]+__)|(\*[^*\n]+\*|_[^_\n]+_)|(~~[^~]+~~)|(!?\[[^\]]+]\([^)]+\))"""
    )

    /**
     * Parses raw Markdown text into a list of structured [MarkdownBlock] elements.
     */
    fun parseMarkdownBlocks(rawMarkdown: String): List<MarkdownBlock> {
        val normalized = rawMarkdown.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lines()
        val blocks = mutableListOf<MarkdownBlock>()

        var i = 0
        var inCodeBlock = false
        var codeLanguage = ""
        val codeLines = mutableListOf<String>()
        var previousWasTable = false

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 1. Fenced code block start/end (``` or ~~~)
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val fenceMarker = if (trimmed.startsWith("```")) "```" else "~~~"
                if (!inCodeBlock) {
                    inCodeBlock = true
                    codeLanguage = trimmed.removePrefix(fenceMarker).trim()
                    codeLines.clear()
                } else {
                    inCodeBlock = false
                    blocks.add(
                        MarkdownBlock.CodeBlock(
                            language = codeLanguage,
                            code = codeLines.joinToString("\n")
                        )
                    )
                    codeLanguage = ""
                    codeLines.clear()
                }
                previousWasTable = false
                i++
                continue
            }

            if (inCodeBlock) {
                codeLines.add(line)
                i++
                continue
            }

            // 2. Blank line
            if (trimmed.isEmpty()) {
                previousWasTable = false
                i++
                continue
            }

            // 3. Horizontal Rule (---, ***, ___)
            if (HR_REGEX.matches(trimmed)) {
                blocks.add(MarkdownBlock.HorizontalRule)
                previousWasTable = false
                i++
                continue
            }

            // 4. ATX Heading (# H1 .. ###### H6)
            val headingMatch = HEADING_REGEX.matchEntire(trimmed)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length.coerceIn(1, 6)
                val headingText = headingMatch.groupValues[2].trim()
                blocks.add(MarkdownBlock.Heading(level = level, text = headingText))
                previousWasTable = false
                i++
                continue
            }

            // 5. Blockquote (> ...)
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trimStart())
                    i++
                }
                blocks.add(MarkdownBlock.Blockquote(quoteLines.joinToString("\n")))
                previousWasTable = false
                continue
            }

            // 6. Pipe Table (| col1 | col2 |)
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.count { it == '|' } >= 2) {
                if (TABLE_SEPARATOR_REGEX.matches(trimmed)) {
                    i++
                    continue
                }
                val cells = trimmed
                    .removePrefix("|")
                    .removeSuffix("|")
                    .split("|")
                    .map { it.trim() }
                val isHeader = !previousWasTable
                blocks.add(MarkdownBlock.TableRowBlock(cells = cells, isHeader = isHeader))
                previousWasTable = true
                i++
                continue
            }
            previousWasTable = false

            // 7. Task list item (- [ ] or - [x])
            val taskMatch = TASK_ITEM_REGEX.matchEntire(line)
            if (taskMatch != null) {
                val stateChar = taskMatch.groupValues[2]
                val itemText = taskMatch.groupValues[3].trim()
                blocks.add(
                    MarkdownBlock.TaskListItem(
                        isChecked = stateChar.equals("x", ignoreCase = true),
                        text = itemText
                    )
                )
                i++
                continue
            }

            // 8. Unordered bullet list item (-, *, +)
            val bulletMatch = BULLET_ITEM_REGEX.matchEntire(line)
            if (bulletMatch != null) {
                val indent = (bulletMatch.groupValues[1].length / 2).coerceIn(0, 3)
                val itemText = bulletMatch.groupValues[2].trim()
                blocks.add(MarkdownBlock.BulletListItem(indentLevel = indent, text = itemText))
                i++
                continue
            }

            // 9. Ordered list item (1. or 1))
            val orderedMatch = ORDERED_ITEM_REGEX.matchEntire(line)
            if (orderedMatch != null) {
                val indent = (orderedMatch.groupValues[1].length / 2).coerceIn(0, 3)
                val numberLabel = orderedMatch.groupValues[2]
                val itemText = orderedMatch.groupValues[3].trim()
                blocks.add(
                    MarkdownBlock.OrderedListItem(
                        numberLabel = numberLabel,
                        indentLevel = indent,
                        text = itemText
                    )
                )
                i++
                continue
            }

            // 10. Regular paragraph (collect consecutive plain lines)
            val paragraphLines = mutableListOf<String>()
            while (i < lines.size) {
                val nextLine = lines[i]
                val nextTrimmed = nextLine.trim()
                if (
                    nextTrimmed.isEmpty() ||
                    nextTrimmed.startsWith("```") ||
                    nextTrimmed.startsWith("~~~") ||
                    nextTrimmed.startsWith("#") ||
                    nextTrimmed.startsWith(">") ||
                    HR_REGEX.matches(nextTrimmed) ||
                    TASK_ITEM_REGEX.matches(nextLine) ||
                    BULLET_ITEM_REGEX.matches(nextLine) ||
                    ORDERED_ITEM_REGEX.matches(nextLine) ||
                    (nextTrimmed.startsWith("|") && nextTrimmed.endsWith("|"))
                ) {
                    break
                }
                paragraphLines.add(nextTrimmed)
                i++
            }
            if (paragraphLines.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString(" ")))
            } else {
                i++
            }
        }

        // Flush unclosed code block if file ended before closing fence
        if (inCodeBlock && codeLines.isNotEmpty()) {
            blocks.add(
                MarkdownBlock.CodeBlock(
                    language = codeLanguage,
                    code = codeLines.joinToString("\n")
                )
            )
        }

        return blocks
    }

    /**
     * Converts inline Markdown (`**bold**`, `*italic*`, `` `code` ``, `~~strike~~`, `[link](url)`)
     * into a styled Jetpack Compose [AnnotatedString].
     */
    fun parseInlineMarkdownToAnnotatedString(rawText: String): AnnotatedString {
        return buildAnnotatedString {
            var lastIndex = 0
            for (match in INLINE_TOKEN_REGEX.findAll(rawText)) {
                if (match.range.first > lastIndex) {
                    append(rawText.substring(lastIndex, match.range.first))
                }
                val token = match.value
                when {
                    token.startsWith("`") && token.endsWith("`") && token.length >= 2 -> {
                        val content = token.substring(1, token.length - 1)
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                background = ComicPanelGray,
                                color = ComicBlack
                            )
                        ) {
                            append(" $content ")
                        }
                    }

                    (token.startsWith("***") && token.endsWith("***")) ||
                        (token.startsWith("___") && token.endsWith("___")) -> {
                        val content = token.substring(3, token.length - 3)
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                fontStyle = FontStyle.Italic
                            )
                        ) {
                            append(content)
                        }
                    }

                    (token.startsWith("**") && token.endsWith("**")) ||
                        (token.startsWith("__") && token.endsWith("__")) -> {
                        val content = token.substring(2, token.length - 2)
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(content)
                        }
                    }

                    (token.startsWith("*") && token.endsWith("*")) ||
                        (token.startsWith("_") && token.endsWith("_")) -> {
                        val content = token.substring(1, token.length - 1)
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(content)
                        }
                    }

                    token.startsWith("~~") && token.endsWith("~~") -> {
                        val content = token.substring(2, token.length - 2)
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(content)
                        }
                    }

                    token.contains("](") && token.endsWith(")") -> {
                        val isImage = token.startsWith("![")
                        val labelStart = if (isImage) 2 else 1
                        val labelEnd = token.indexOf("](")
                        val label = if (labelEnd > labelStart) {
                            token.substring(labelStart, labelEnd)
                        } else {
                            token
                        }
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append(if (isImage) "[IMG: $label]" else label)
                        }
                    }

                    else -> append(token)
                }
                lastIndex = match.range.last + 1
            }
            if (lastIndex < rawText.length) {
                append(rawText.substring(lastIndex))
            }
        }
    }

    /**
     * Strips raw inline Markdown symbols (`**`, `*`, `` ` ``, `~~`, `[text](url)`) for Glance
     * `Text` views where `AnnotatedString` is not supported, preserving clean readable text.
     */
    fun formatInlineMarkdownForGlance(rawText: String): String {
        return INLINE_TOKEN_REGEX.replace(rawText) { match ->
            val token = match.value
            when {
                token.startsWith("`") && token.endsWith("`") && token.length >= 2 ->
                    token.substring(1, token.length - 1)

                (token.startsWith("***") && token.endsWith("***")) ||
                    (token.startsWith("___") && token.endsWith("___")) ->
                    token.substring(3, token.length - 3)

                (token.startsWith("**") && token.endsWith("**")) ||
                    (token.startsWith("__") && token.endsWith("__")) ->
                    token.substring(2, token.length - 2)

                (token.startsWith("*") && token.endsWith("*")) ||
                    (token.startsWith("_") && token.endsWith("_")) ->
                    token.substring(1, token.length - 1)

                token.startsWith("~~") && token.endsWith("~~") ->
                    token.substring(2, token.length - 2)

                token.contains("](") && token.endsWith(")") -> {
                    val isImage = token.startsWith("![")
                    val labelStart = if (isImage) 2 else 1
                    val labelEnd = token.indexOf("](")
                    val label = if (labelEnd > labelStart) {
                        token.substring(labelStart, labelEnd)
                    } else {
                        token
                    }
                    if (isImage) "[IMG: $label]" else label
                }

                else -> token
            }
        }
    }
}

/**
 * Renders a single [MarkdownBlock] with authentic Markdown styling inside the Jetpack Compose
 * In-App Preview / Viewer.
 */
@Composable
fun ComposeMarkdownBlockItem(block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.Heading -> {
            val annotated = remember(block.text) {
                MarkdownHelper.parseInlineMarkdownToAnnotatedString(block.text)
            }
            val fontSize = when (block.level) {
                1 -> 20.sp
                2 -> 17.sp
                3 -> 15.sp
                else -> 14.sp
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp)
            ) {
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        lineHeight = (fontSize.value + 5f).sp
                    ),
                    color = ComicBlack
                )
                if (block.level <= 2) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (block.level == 1) 2.dp else 1.dp)
                            .background(ComicBlack)
                    )
                }
            }
        }

        is MarkdownBlock.Paragraph -> {
            val annotated = remember(block.text) {
                MarkdownHelper.parseInlineMarkdownToAnnotatedString(block.text)
            }
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                ),
                color = ComicBlack,
                modifier = Modifier.fillMaxWidth()
            )
        }

        is MarkdownBlock.CodeBlock -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, ComicBlack)
                    .background(ComicPanelGray)
                    .padding(10.dp)
            ) {
                if (block.language.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .background(ComicBlack)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = block.language.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = ComicWhite
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = block.code,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        ),
                        color = ComicBlack
                    )
                }
            }
        }

        is MarkdownBlock.Blockquote -> {
            val annotated = remember(block.text) {
                MarkdownHelper.parseInlineMarkdownToAnnotatedString(block.text)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .background(ComicPanelGray)
                    .border(1.dp, ComicBlack)
            ) {
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .fillMaxHeight()
                        .background(ComicBlack)
                )
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontStyle = FontStyle.Italic,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    ),
                    color = ComicBlack,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }

        is MarkdownBlock.BulletListItem -> {
            val annotated = remember(block.text) {
                MarkdownHelper.parseInlineMarkdownToAnnotatedString(block.text)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (block.indentLevel * 12).dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = if (block.indentLevel == 0) "•" else "◦",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    ),
                    color = ComicBlack,
                    modifier = Modifier.width(18.dp)
                )
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    color = ComicBlack,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is MarkdownBlock.OrderedListItem -> {
            val annotated = remember(block.text) {
                MarkdownHelper.parseInlineMarkdownToAnnotatedString(block.text)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (block.indentLevel * 12).dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = block.numberLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    color = ComicBlack,
                    modifier = Modifier.width(26.dp)
                )
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    color = ComicBlack,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is MarkdownBlock.TaskListItem -> {
            val annotated = remember(block.text) {
                MarkdownHelper.parseInlineMarkdownToAnnotatedString(block.text)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = if (block.isChecked) "☑" else "☐",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    ),
                    color = ComicBlack,
                    modifier = Modifier.width(22.dp)
                )
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textDecoration = if (block.isChecked) {
                            TextDecoration.LineThrough
                        } else {
                            TextDecoration.None
                        }
                    ),
                    color = if (block.isChecked) ComicMutedInk else ComicBlack,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        is MarkdownBlock.TableRowBlock -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, ComicBlack)
                    .background(if (block.isHeader) ComicPanelGray else ComicWhite)
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                block.cells.forEach { cellText ->
                    val annotated = remember(cellText) {
                        MarkdownHelper.parseInlineMarkdownToAnnotatedString(cellText)
                    }
                    Text(
                        text = annotated,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (block.isHeader) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        ),
                        color = ComicBlack,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        is MarkdownBlock.HorizontalRule -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .height(2.dp)
                    .background(ComicBlack)
            )
        }
    }
}
