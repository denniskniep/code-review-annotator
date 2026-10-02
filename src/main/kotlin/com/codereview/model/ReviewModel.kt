package com.codereview.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val PUBLISHED_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").withZone(ZoneId.systemDefault())

/** Formats an ISO-8601 instant string (e.g. "2026-07-14T20:48:03.73Z") for display, or null if unparseable. */
fun formatPublishedDate(iso: String?): String? =
    iso?.let { runCatching { PUBLISHED_DATE_FORMATTER.format(Instant.parse(it)) }.getOrNull() }

enum class CommentType { ISSUE, SUGGESTION, NOTE }
enum class CommentScope { REVIEW, FILE, LINE }

data class ReviewComment(
    val id: String = UUID.randomUUID().toString(),
    val scope: CommentScope = CommentScope.REVIEW,
    val type: CommentType = CommentType.NOTE,
    val text: String = "",
    val filePath: String? = null,
    val lineStart: Int? = null,
    val lineEnd: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val author: String? = null,
    /** ISO-8601 instant string, e.g. "2026-07-14T20:48:03.73Z". */
    val publishedDate: String? = null
) {
    fun formattedPublishedDate(): String? = formatPublishedDate(publishedDate)
}

data class ReviewSession(
    val id: String = UUID.randomUUID().toString(),
    val summary: String = "",
    val comments: MutableList<ReviewComment> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    val baseBranch: String? = null
)
