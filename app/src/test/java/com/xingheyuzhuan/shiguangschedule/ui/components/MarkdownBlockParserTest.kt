package com.xingheyuzhuan.shiguangschedule.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlockParserTest {

    @Test
    fun parsesHeadingsParagraphsAndDivider() {
        val blocks = MarkdownBlockParser.parse(
            """
            # Changelog

            ## [1.1.1] - 2026-09-06

            Some **bold** text.
            ---
            """.trimIndent()
        )

        assertEquals(MarkdownBlock.Heading(1, "Changelog"), blocks[0])
        assertEquals(MarkdownBlock.Heading(2, "[1.1.1] - 2026-09-06"), blocks[1])
        assertEquals(MarkdownBlock.Paragraph("Some **bold** text."), blocks[2])
        assertEquals(MarkdownBlock.Divider, blocks[3])
    }

    @Test
    fun parsesNestedAndTaskLists() {
        val blocks = MarkdownBlockParser.parse(
            """
            - parent
              - child
            - [x] done
            - [ ] todo
            1. first
            """.trimIndent()
        )

        assertEquals(MarkdownBlock.ListItem("-", "parent", 0), blocks[0])
        assertEquals(MarkdownBlock.ListItem("-", "child", 1), blocks[1])
        assertEquals(MarkdownBlock.ListItem("☑", "done", 0, task = true, checked = true), blocks[2])
        assertEquals(MarkdownBlock.ListItem("☐", "todo", 0, task = true, checked = false), blocks[3])
        assertEquals(MarkdownBlock.ListItem("1.", "first", 0), blocks[4])
    }

    @Test
    fun parsesQuoteAndDropsQuoteDivider() {
        val blocks = MarkdownBlockParser.parse(
            """
            > line one
            > line two

            > ---
            """.trimIndent()
        )

        assertEquals(MarkdownBlock.Quote("line one\nline two"), blocks[0])
        assertEquals(MarkdownBlock.Divider, blocks[1])
    }

    @Test
    fun splitsDividerInsideQuote() {
        val blocks = MarkdownBlockParser.parse(
            """
            > first
            > ---
            > second
            """.trimIndent()
        )

        assertEquals(MarkdownBlock.Quote("first"), blocks[0])
        assertEquals(MarkdownBlock.Divider, blocks[1])
        assertEquals(MarkdownBlock.Quote("second"), blocks[2])
    }

    @Test
    fun parsesFencedCodeBlock() {
        val blocks = MarkdownBlockParser.parse(
            """
            ```kotlin
            val x = 1
            println(x)
            ```
            """.trimIndent()
        )

        assertEquals(MarkdownBlock.Code("val x = 1\nprintln(x)", "kotlin"), blocks[0])
    }

    @Test
    fun parsesGfmTable() {
        val blocks = MarkdownBlockParser.parse(
            """
            | A | B |
            | --- | --- |
            | 1 | 2 |
            | 3 | 4 |
            """.trimIndent()
        )

        val table = blocks.single() as MarkdownBlock.Table
        assertEquals(listOf("A", "B"), table.header)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), table.rows)
    }

    @Test
    fun stripsHtmlTagsAndKeepsText() {
        val blocks = MarkdownBlockParser.parse(
            """
            <details>
            <summary>More</summary>

            Body<br>next line
            </details>
            """.trimIndent()
        )

        assertTrue(blocks.any { it is MarkdownBlock.Paragraph && it.text.contains("More") })
        assertTrue(blocks.any { it is MarkdownBlock.Paragraph && it.text.contains("Body\nnext line") })
    }

    @Test
    fun indentedContinuationBelongsToListItem() {
        val blocks = MarkdownBlockParser.parse(
            """
            - first line
              continued line
            - second
            """.trimIndent()
        )

        assertEquals(MarkdownBlock.ListItem("-", "first line continued line", 0), blocks[0])
        assertEquals(MarkdownBlock.ListItem("-", "second", 0), blocks[1])
    }

    @Test
    fun lazyContinuationStaysInsideListItem() {
        val blocks = MarkdownBlockParser.parse(
            """
            - **修复**: 中文说明
            *English translation.*
            """.trimIndent()
        )

        assertEquals(
            MarkdownBlock.ListItem("-", "**修复**: 中文说明\n*English translation.*", 0),
            blocks.single()
        )
    }
}
