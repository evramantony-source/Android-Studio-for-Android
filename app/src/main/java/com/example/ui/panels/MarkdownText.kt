package com.example.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.ui.theme.JetBrainsMonoFontFamily

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletItem(val marker: String, val text: String, val indentLevel: Int = 0) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class CodeFence(val lang: String, val code: String) : MdBlock
    data object Divider : MdBlock
}

@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    isUser: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val codeBg = if (isUser) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val codeFg = if (isUser) color else MaterialTheme.colorScheme.primary
    val accentColor = if (isUser) color.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        else -> MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = renderInlineMarkdown(block.text, color, codeBg, codeFg),
                        style = style,
                        color = color,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text = renderInlineMarkdown(block.text, color, codeBg, codeFg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                    )
                }

                is MdBlock.BulletItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (block.indentLevel * 12 + 4).dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = block.marker,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            text = renderInlineMarkdown(block.text, color, codeBg, codeFg),
                            style = MaterialTheme.typography.bodyMedium,
                            color = color,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.Quote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(codeBg.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .background(accentColor, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = renderInlineMarkdown(block.text, color.copy(alpha = 0.9f), codeBg, codeFg),
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = color.copy(alpha = 0.9f),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.CodeFence -> {
                    Surface(
                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            if (block.lang.isNotBlank()) {
                                Text(
                                    text = block.lang.uppercase(),
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                            Text(
                                text = block.code,
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }

                MdBlock.Divider -> {
                    HorizontalDivider(
                        color = color.copy(alpha = 0.18f),
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

private fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val lines = raw.replace("\r\n", "\n").lines()
    val blocks = mutableListOf<MdBlock>()
    val paragraphBuffer = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphBuffer.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paragraphBuffer.joinToString("\n")))
            paragraphBuffer.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Fenced code block
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            val codeText = codeLines.joinToString("\n").trimEnd()
            if (codeText.isNotBlank()) {
                blocks.add(MdBlock.CodeFence(lang = lang, code = codeText))
            }
            i++
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        // Horizontal rule
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushParagraph()
            blocks.add(MdBlock.Divider)
            i++
            continue
        }

        // Headings
        val headingMatch = Regex("""^(#{1,4})\s+(.+)$""").matchEntire(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            blocks.add(
                MdBlock.Heading(
                    level = headingMatch.groupValues[1].length,
                    text = headingMatch.groupValues[2].trim(),
                ),
            )
            i++
            continue
        }

        // Blockquotes
        if (trimmed.startsWith("> ")) {
            flushParagraph()
            blocks.add(MdBlock.Quote(trimmed.removePrefix("> ").trim()))
            i++
            continue
        }

        // Bullet lists
        val bulletMatch = Regex("""^(\s*)[-*+]\s+(.+)$""").matchEntire(line)
        if (bulletMatch != null) {
            flushParagraph()
            val indent = (bulletMatch.groupValues[1].length / 2).coerceIn(0, 3)
            blocks.add(MdBlock.BulletItem(marker = "•", text = bulletMatch.groupValues[2].trim(), indentLevel = indent))
            i++
            continue
        }

        // Numbered lists
        val orderedMatch = Regex("""^(\s*)(\d+\.)\s+(.+)$""").matchEntire(line)
        if (orderedMatch != null) {
            flushParagraph()
            val indent = (orderedMatch.groupValues[1].length / 2).coerceIn(0, 3)
            blocks.add(
                MdBlock.BulletItem(
                    marker = orderedMatch.groupValues[2],
                    text = orderedMatch.groupValues[3].trim(),
                    indentLevel = indent,
                ),
            )
            i++
            continue
        }

        paragraphBuffer.add(trimmed)
        i++
    }
    flushParagraph()
    return blocks
}

private fun renderInlineMarkdown(
    text: String,
    baseColor: Color,
    inlineCodeBg: Color,
    inlineCodeFg: Color,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            // Bold **...**
            text.startsWith("**", i) -> {
                val close = text.indexOf("**", i + 2)
                if (close > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                        append(text.substring(i + 2, close))
                    }
                    i = close + 2
                } else {
                    append(text[i])
                    i++
                }
            }
            // Inline code `...`
            text[i] == '`' -> {
                val close = text.indexOf('`', i + 1)
                if (close > i + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = inlineCodeFg,
                            background = inlineCodeBg,
                        ),
                    ) {
                        append(" ${text.substring(i + 1, close)} ")
                    }
                    i = close + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            // Italic *...*
            text[i] == '*' && (i + 1 < n && text[i + 1] != '*') -> {
                val close = text.indexOf('*', i + 1)
                if (close > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)) {
                        append(text.substring(i + 1, close))
                    }
                    i = close + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}
