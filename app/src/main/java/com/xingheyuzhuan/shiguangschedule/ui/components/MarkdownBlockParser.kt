package com.xingheyuzhuan.shiguangschedule.ui.components

/**
 * 更新说明（GitHub Release body）的轻量 Markdown 块级解析。
 *
 * 覆盖常见 GitHub Release 写法：标题、段落、引用、有序/无序/嵌套列表、任务列表、
 * 围栏代码块、分隔线、GFM 表格。行内强调/链接/代码由 UI 层继续解析。
 */
internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListItem(
        val marker: String,
        val text: String,
        val depth: Int,
        val task: Boolean = false,
        val checked: Boolean = false
    ) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val code: String, val language: String?) : MarkdownBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

internal object MarkdownBlockParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val FENCE = Regex("^(`{3,}|~{3,})\\s*([A-Za-z0-9_+-]*)\\s*$")
    private val THEMATIC = Regex("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val LIST = Regex("^(\\s*)([-+*]|\\d+[.)])\\s+(.*)$")
    private val TASK = Regex("^\\[([ xX])]\\s*(.*)$")
    private val TABLE_SEPARATOR =
        Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$")
    private val HTML_TAG = Regex("<[^>]+>")

    fun parse(markdown: String): List<MarkdownBlock> {
        val lines = markdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 空行
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // HTML 注释整段跳过
            if (trimmed.startsWith("<!--")) {
                while (i < lines.size && !lines[i].contains("-->")) i++
                if (i < lines.size) i++
                continue
            }

            // 围栏代码块
            val fence = FENCE.find(trimmed)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val language = fence.groupValues[2].ifBlank { null }
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(marker)) {
                    code.appendLine(lines[i])
                    i++
                }
                if (i < lines.size) i++ // 跳过结束围栏
                blocks += MarkdownBlock.Code(code.toString().trimEnd('\n'), language)
                continue
            }

            // GFM 表格
            if (isTableStart(lines, i)) {
                val header = splitTableRow(lines[i])
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitTableRow(lines[i])
                    i++
                }
                blocks += MarkdownBlock.Table(header, rows)
                continue
            }

            // 标题
            val heading = HEADING.find(trimmed)
            if (heading != null) {
                val level = heading.groupValues[1].length.coerceIn(1, 6)
                blocks += MarkdownBlock.Heading(level, cleanInlineHtml(heading.groupValues[2]))
                i++
                continue
            }

            // 分隔线
            if (THEMATIC.matches(trimmed)) {
                blocks += MarkdownBlock.Divider
                i++
                continue
            }

            // 引用：连续多行合并；引用内部的 --- 拆成分隔线
            if (QUOTE.find(line) != null) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size) {
                    val quote = QUOTE.find(lines[i]) ?: break
                    quoteLines += cleanInlineHtml(quote.groupValues[1])
                    i++
                }

                var segment = mutableListOf<String>()
                fun flushQuoteSegment() {
                    val text = segment.joinToString("\n").trim()
                    if (text.isNotEmpty()) blocks += MarkdownBlock.Quote(text)
                    segment = mutableListOf()
                }

                quoteLines.forEach { quoteLine ->
                    if (THEMATIC.matches(quoteLine.trim())) {
                        flushQuoteSegment()
                        blocks += MarkdownBlock.Divider
                    } else {
                        segment += quoteLine
                    }
                }
                flushQuoteSegment()
                continue
            }

            // 列表（含嵌套缩进与任务列表）
            val listMatch = LIST.find(line)
            if (listMatch != null) {
                val indent = listMatch.groupValues[1].length
                val depth = when {
                    indent >= 4 -> indent / 4
                    indent >= 2 -> indent / 2
                    else -> 0
                }.coerceIn(0, 6)
                val rawMarker = listMatch.groupValues[2]
                var content = listMatch.groupValues[3].trim()
                var task = false
                var checked = false

                TASK.find(content)?.let { taskMatch ->
                    task = true
                    checked = taskMatch.groupValues[1].equals("x", ignoreCase = true)
                    content = taskMatch.groupValues[2].trim()
                }

                // 缩进更深的普通文本算续行；没有空行时，紧跟列表项的普通段落也按 CommonMark 懒续行处理
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j]
                    if (next.isBlank()) break
                    if (LIST.find(next) != null) break
                    if (isBlockStart(lines, j)) break
                    val nextIndent = next.indexOfFirst { !it.isWhitespace() }
                        .let { if (it < 0) 0 else it }
                    val separator = if (nextIndent > indent) " " else "\n"
                    content += separator + cleanInlineHtml(next.trim())
                    j++
                }

                blocks += MarkdownBlock.ListItem(
                    marker = if (task) (if (checked) "☑" else "☐") else rawMarker,
                    text = content,
                    depth = depth,
                    task = task,
                    checked = checked
                )
                i = j
                continue
            }

            // 段落：连续普通行合并，保留换行
            val paragraph = StringBuilder()
            while (i < lines.size) {
                val current = lines[i]
                if (current.isBlank() || isBlockStart(lines, i)) break
                val cleaned = cleanInlineHtml(current)
                if (cleaned.isNotBlank()) {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(cleaned)
                }
                i++
            }
            if (paragraph.isNotBlank()) {
                blocks += MarkdownBlock.Paragraph(paragraph.toString())
            } else {
                // 兜底：避免空行/纯 HTML 标签导致死循环
                i++
            }
        }

        return mergeAdjacentQuotes(blocks)
    }

    /** 相邻的引用块（中间只有空行）合并成一个引用，避免 GitHub 的连续 `>` 被拆成多张卡片。 */
    private fun mergeAdjacentQuotes(blocks: List<MarkdownBlock>): List<MarkdownBlock> {
        val merged = mutableListOf<MarkdownBlock>()
        blocks.forEach { block ->
            val last = merged.lastOrNull()
            if (block is MarkdownBlock.Quote && last is MarkdownBlock.Quote) {
                merged[merged.lastIndex] = MarkdownBlock.Quote(last.text + "\n\n" + block.text)
            } else {
                merged += block
            }
        }
        return merged
    }

    private fun isBlockStart(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.startsWith("<!--")) return true
        if (FENCE.matches(trimmed)) return true
        if (HEADING.matches(trimmed)) return true
        if (THEMATIC.matches(trimmed)) return true
        if (QUOTE.matches(line)) return true
        if (LIST.matches(line)) return true
        if (isTableStart(lines, index)) return true
        return false
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        val header = lines[index]
        if (!header.contains('|')) return false
        return TABLE_SEPARATOR.matches(lines[index + 1])
    }

    private fun splitTableRow(line: String): List<String> {
        var value = line.trim()
        if (value.startsWith("|")) value = value.substring(1)
        if (value.endsWith("|")) value = value.dropLast(1)

        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        value.forEach { ch ->
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' -> escaped = true
                ch == '|' -> {
                    cells += current.toString().trim()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        cells += current.toString().trim()
        return cells
    }

    /** 去掉常见 HTML 标签（保留文本），并把 <br> 变成换行。 */
    private fun cleanInlineHtml(raw: String): String = raw
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(HTML_TAG, "")
        .trim()
}
