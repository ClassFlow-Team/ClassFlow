package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * 更新说明 Markdown 渲染。
 *
 * 块级结构由 [MarkdownBlockParser] 解析；这里负责 Compose 排版与行内语法：
 * `**粗体**`、`*斜体*`、`~~删除线~~`、`` `代码` ``、`[链接](url)`、`![图片](url)`、
 * `<https://...>` 自动链接，以及 GFM 表格、代码块、引用、嵌套列表与任务列表。
 */
@Composable
fun MarkdownReleaseNotes(markdown: String) {
    val blocks = remember(markdown) { MarkdownBlockParser.parse(markdown) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> MarkdownInlineText(
                    text = block.text,
                    style = headingStyle(block.level),
                    color = MaterialTheme.colorScheme.onSurface,
                    linkColor = linkColor,
                    codeBackground = codeBackground,
                    fontWeight = FontWeight.SemiBold
                )

                is MarkdownBlock.Paragraph -> MarkdownInlineText(
                    text = block.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    linkColor = linkColor,
                    codeBackground = codeBackground
                )

                is MarkdownBlock.ListItem -> {
                    val marker = when {
                        block.task -> block.marker
                        block.marker == "-" || block.marker == "*" || block.marker == "+" -> "•"
                        else -> block.marker
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (block.depth * 12).dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = marker,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 16.dp)
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            MarkdownInlineText(
                                text = block.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                linkColor = linkColor,
                                codeBackground = codeBackground
                            )
                        }
                    }
                }

                is MarkdownBlock.Quote -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    MarkdownInlineText(
                        text = block.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        linkColor = linkColor,
                        codeBackground = codeBackground
                    )
                }

                is MarkdownBlock.Code -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                ) {
                    Text(
                        text = block.code,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                MarkdownBlock.Divider -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp
                )

                is MarkdownBlock.Table -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(4.dp)
                ) {
                    MarkdownTableRow(
                        cells = block.header,
                        isHeader = true,
                        linkColor = linkColor,
                        codeBackground = codeBackground
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                    block.rows.forEach { row ->
                        MarkdownTableRow(
                            cells = row,
                            isHeader = false,
                            linkColor = linkColor,
                            codeBackground = codeBackground
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    isHeader: Boolean,
    linkColor: Color,
    codeBackground: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isHeader) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        cells.forEach { cell ->
            Box(modifier = Modifier.weight(1f).padding(horizontal = 2.dp)) {
                MarkdownInlineText(
                    text = cell,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isHeader) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    linkColor = linkColor,
                    codeBackground = codeBackground,
                    fontWeight = if (isHeader) FontWeight.SemiBold else null
                )
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleMedium
    3 -> MaterialTheme.typography.titleSmall
    else -> MaterialTheme.typography.labelLarge
}

@Composable
private fun MarkdownInlineText(
    text: String,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    codeBackground: Color,
    fontWeight: FontWeight? = null
) {
    val annotated = remember(text, linkColor, codeBackground) {
        buildAnnotatedString {
            appendInlineMarkdown(text, linkColor, codeBackground)
        }
    }
    Text(
        text = annotated,
        style = style,
        color = color,
        fontWeight = fontWeight
    )
}

private enum class InlineType {
    CODE, IMAGE, LINK, STRONG, STRONG_UNDERSCORE, STRIKE, EM, EM_UNDERSCORE, AUTOLINK
}

private data class InlineRule(val type: InlineType, val regex: Regex)

private val INLINE_RULES = listOf(
    InlineRule(InlineType.CODE, Regex("`([^`]+)`")),
    InlineRule(InlineType.IMAGE, Regex("!\\[([^]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")),
    InlineRule(InlineType.LINK, Regex("\\[([^]]+)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")),
    InlineRule(InlineType.STRONG, Regex("\\*\\*([\\s\\S]+?)\\*\\*")),
    InlineRule(InlineType.STRONG_UNDERSCORE, Regex("__([\\s\\S]+?)__")),
    InlineRule(InlineType.STRIKE, Regex("~~([\\s\\S]+?)~~")),
    InlineRule(InlineType.EM, Regex("\\*([^*\\n]+?)\\*")),
    InlineRule(InlineType.EM_UNDERSCORE, Regex("_([^_\\n]+?)_")),
    InlineRule(InlineType.AUTOLINK, Regex("<((?:https?|mailto):[^>]+)>"))
)

private data class InlineMatch(val match: MatchResult, val type: InlineType)

private fun findInlineMatch(text: String, start: Int): InlineMatch? {
    var best: InlineMatch? = null
    INLINE_RULES.forEach { rule ->
        var match = rule.regex.find(text, start)
        // 反斜杠转义的标记不参与行内语法解析（例如 `\[1.1.1\]`、`\*literal\*`）
        while (match != null && isEscaped(text, match.range.first)) {
            match = rule.regex.find(text, match.range.first + 1)
        }
        match ?: return@forEach
        val current = best
        if (current == null || match.range.first < current.match.range.first) {
            best = InlineMatch(match, rule.type)
        }
    }
    return best
}

private fun isEscaped(text: String, index: Int): Boolean {
    var backslashes = 0
    var i = index - 1
    while (i >= 0 && text[i] == '\\') {
        backslashes++
        i--
    }
    return backslashes % 2 == 1
}

private fun AnnotatedString.Builder.appendInlineMarkdown(
    text: String,
    linkColor: Color,
    codeBackground: Color
) {
    var cursor = 0

    while (cursor < text.length) {
        val best = findInlineMatch(text, cursor)
        if (best == null) {
            append(unescapeMarkdown(unescapeHtml(text.substring(cursor))))
            return
        }

        val match = best.match
        if (match.range.first > cursor) {
            append(unescapeMarkdown(unescapeHtml(text.substring(cursor, match.range.first))))
        }

        when (best.type) {
            InlineType.CODE -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground
                )
            ) {
                append(match.groupValues[1])
            }

            InlineType.IMAGE -> withLink(linkAnnotation(unescapeMarkdown(match.groupValues[2]), linkColor)) {
                append(unescapeMarkdown(unescapeHtml(match.groupValues[1].ifBlank { "🖼" })))
            }

            InlineType.LINK -> withLink(linkAnnotation(unescapeMarkdown(match.groupValues[2]), linkColor)) {
                appendInlineMarkdown(match.groupValues[1], linkColor, codeBackground)
            }

            InlineType.STRONG,
            InlineType.STRONG_UNDERSCORE -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlineMarkdown(match.groupValues[1], linkColor, codeBackground)
            }

            InlineType.STRIKE -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInlineMarkdown(match.groupValues[1], linkColor, codeBackground)
            }

            InlineType.EM,
            InlineType.EM_UNDERSCORE -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlineMarkdown(match.groupValues[1], linkColor, codeBackground)
            }

            InlineType.AUTOLINK -> withLink(linkAnnotation(unescapeMarkdown(match.groupValues[1]), linkColor)) {
                append(unescapeMarkdown(match.groupValues[1]))
            }
        }

        cursor = match.range.last + 1
    }
}

private fun linkAnnotation(url: String, color: Color): LinkAnnotation.Url =
    LinkAnnotation.Url(
        url = url,
        styles = TextLinkStyles(
            style = SpanStyle(
                color = color,
                textDecoration = TextDecoration.Underline
            )
        )
    )

private fun unescapeHtml(text: String): String = text
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&nbsp;", " ")

/** 去掉 Markdown 的反斜杠转义，例如 `\[1.1.1\]` -> `[1.1.1]`。 */
private val MARKDOWN_ESCAPE = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>~|])")

private fun unescapeMarkdown(text: String): String =
    MARKDOWN_ESCAPE.replace(text) { it.groupValues[1] }
