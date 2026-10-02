package com.codereview.export

import com.codereview.model.CommentScope
import com.codereview.model.CommentType
import com.codereview.model.ReviewComment
import com.codereview.model.ReviewSession
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException

object JsonImporter {

    private val gson = GsonBuilder().create()

    private data class Finding(
        val file: String?,
        val line: Int?,
        val type: String?,
        val text: String?,
        val author: String?,
        val publishedDate: String?
    )

    private data class ExportPayload(val summary: String?, val findings: List<Finding>?)

    class InvalidReviewJsonException(message: String) : Exception(message)

    /** Reverses [JsonExporter.export]: findings become LINE/FILE comments, summary is restored as-is. */
    fun import(json: String): ReviewSession {
        val payload = try {
            gson.fromJson(json, ExportPayload::class.java)
        } catch (e: JsonSyntaxException) {
            throw InvalidReviewJsonException(e.message ?: "malformed JSON")
        } ?: throw InvalidReviewJsonException("empty JSON document")

        val comments = (payload.findings ?: emptyList()).map { finding ->
            val type = finding.type?.let { name -> runCatching { CommentType.valueOf(name) }.getOrNull() }
                ?: CommentType.NOTE
            ReviewComment(
                scope = if (finding.line != null) CommentScope.LINE else CommentScope.FILE,
                type = type,
                text = finding.text.orEmpty(),
                filePath = finding.file,
                lineStart = finding.line,
                lineEnd = finding.line,
                author = finding.author,
                publishedDate = finding.publishedDate
            )
        }.toMutableList()

        return ReviewSession(summary = payload.summary.orEmpty(), comments = comments)
    }
}
