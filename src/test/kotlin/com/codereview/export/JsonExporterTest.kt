package com.codereview.export

import com.codereview.model.*
import org.junit.Assert.*
import org.junit.Test

class JsonExporterTest {

    @Test
    fun `export empty session returns empty summary and findings`() {
        val session = ReviewSession()
        assertEquals("{\n  \"summary\": \"\",\n  \"findings\": []\n}\n", JsonExporter.export(session))
    }

    @Test
    fun `export uses the session summary field`() {
        val session = ReviewSession(
            summary = "Overall clean",
            comments = mutableListOf(
                ReviewComment(scope = CommentScope.LINE, type = CommentType.ISSUE, text = "Bug", filePath = "a.go", lineStart = 1)
            )
        )
        val result = JsonExporter.export(session)
        assertTrue(result.contains("\"summary\": \"Overall clean\""))
    }

    @Test
    fun `export line comment`() {
        val session = ReviewSession(
            comments = mutableListOf(
                ReviewComment(scope = CommentScope.LINE, type = CommentType.ISSUE, text = "Race condition", filePath = "src/handler.go", lineStart = 10)
            )
        )
        val result = JsonExporter.export(session)
        assertTrue(result.contains("\"file\": \"src/handler.go\""))
        assertTrue(result.contains("\"line\": 10"))
        assertTrue(result.contains("\"type\": \"ISSUE\""))
        assertTrue(result.contains("\"text\": \"Race condition\""))
    }

    @Test
    fun `export escapes quotes and newlines in text`() {
        val session = ReviewSession(
            comments = mutableListOf(
                ReviewComment(scope = CommentScope.FILE, type = CommentType.NOTE, text = "Says \"fix\"\nnow", filePath = "a.go")
            )
        )
        val result = JsonExporter.export(session)
        assertTrue(result.contains("\"text\": \"Says \\\"fix\\\"\\nnow\""))
    }

    @Test
    fun `export null line for file-scope comment`() {
        val session = ReviewSession(
            comments = mutableListOf(
                ReviewComment(scope = CommentScope.FILE, type = CommentType.NOTE, text = "Missing tests", filePath = "a.go")
            )
        )
        val result = JsonExporter.export(session)
        assertTrue(result.contains("\"line\": null"))
    }

    @Test
    fun `export excludes review-scope comments from findings and sorts remaining`() {
        val session = ReviewSession(
            comments = mutableListOf(
                ReviewComment(scope = CommentScope.REVIEW, type = CommentType.NOTE, text = "Legacy review comment"),
                ReviewComment(scope = CommentScope.LINE, type = CommentType.ISSUE, text = "Bug", filePath = "z.go", lineStart = 5),
                ReviewComment(scope = CommentScope.FILE, type = CommentType.SUGGESTION, text = "Refactor", filePath = "a.go")
            )
        )
        val result = JsonExporter.export(session)
        assertFalse(result.contains("Legacy review comment"))
        assertTrue(result.indexOf("Refactor") < result.indexOf("Bug"))
    }
}
