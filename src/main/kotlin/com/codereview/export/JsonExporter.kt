package com.codereview.export

import com.codereview.model.CommentScope
import com.codereview.model.ReviewComment
import com.codereview.model.ReviewSession
import com.google.gson.GsonBuilder

object JsonExporter {

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    private data class Finding(
        val file: String?,
        val line: Int?,
        val type: String,
        val text: String,
        val author: String?,
        val publishedDate: String?
    )

    private data class ExportPayload(val summary: String, val findings: List<Finding>)

    fun export(session: ReviewSession): String {
        val findings = session.comments
            .filter { it.scope != CommentScope.REVIEW }
            .sortedWith(
                compareBy<ReviewComment> { it.scope.ordinal }
                    .thenBy { it.filePath ?: "" }
                    .thenBy { it.lineStart ?: Int.MAX_VALUE }
            )
            .map { Finding(it.filePath, it.lineStart, it.type.name, it.text, it.author, it.publishedDate) }

        return gson.toJson(ExportPayload(session.summary, findings)) + "\n"
    }
}
