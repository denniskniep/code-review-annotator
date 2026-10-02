package com.codereview.export

import com.codereview.model.CommentScope
import com.codereview.model.CommentType
import com.codereview.model.ReviewComment
import com.codereview.model.ReviewSession
import org.junit.Assert.*
import org.junit.Test

class JsonImporterTest {

    @Test
    fun `round trips a session through export and import`() {
        val original = ReviewSession(
            summary = "Overall clean",
            comments = mutableListOf(
                ReviewComment(scope = CommentScope.LINE, type = CommentType.ISSUE, text = "Bug", filePath = "a.go", lineStart = 5),
                ReviewComment(scope = CommentScope.FILE, type = CommentType.SUGGESTION, text = "Refactor", filePath = "b.go")
            )
        )
        val imported = JsonImporter.import(JsonExporter.export(original))

        assertEquals("Overall clean", imported.summary)
        assertEquals(2, imported.comments.size)
        assertTrue(imported.comments.any { it.scope == CommentScope.LINE && it.filePath == "a.go" && it.lineStart == 5 && it.text == "Bug" })
        assertTrue(imported.comments.any { it.scope == CommentScope.FILE && it.filePath == "b.go" && it.lineStart == null && it.text == "Refactor" })
    }

    @Test
    fun `imports summary and findings per the documented export schema`() {
        val json = """
            {
              "summary": "Overall clean",
              "findings": [
                { "file": "a.go", "line": 5, "type": "ISSUE", "text": "Bug" },
                { "file": "b.go", "line": null, "type": "SUGGESTION", "text": "Refactor" }
              ]
            }
        """.trimIndent()
        val imported = JsonImporter.import(json)

        assertEquals("Overall clean", imported.summary)
        assertEquals(2, imported.comments.size)
        assertTrue(imported.comments.any { it.scope == CommentScope.LINE && it.filePath == "a.go" && it.lineStart == 5 && it.text == "Bug" })
        assertTrue(imported.comments.any { it.scope == CommentScope.FILE && it.filePath == "b.go" && it.lineStart == null && it.text == "Refactor" })
    }

    @Test
    fun `missing fields default safely`() {
        val imported = JsonImporter.import("""{"findings":[{"text":"no type or file"}]}""")
        assertEquals("", imported.summary)
        assertEquals(CommentType.NOTE, imported.comments[0].type)
        assertEquals(CommentScope.FILE, imported.comments[0].scope)
    }

    @Test(expected = JsonImporter.InvalidReviewJsonException::class)
    fun `malformed json throws`() {
        JsonImporter.import("not json")
    }
}
