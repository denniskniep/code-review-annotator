package com.codereview.actions

import com.codereview.export.JsonImporter
import com.codereview.model.ReviewComment
import com.codereview.service.ReviewSessionService
import com.codereview.toolwindow.ReviewToolWindowFactory
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.nio.charset.StandardCharsets

class ImportJsonFromFileAction : AnAction(
    "Load Review from JSON File...",
    "Load review comments from a JSON file into the current session",
    AllIcons.Actions.Upload
) {
    private val log = Logger.getInstance(ImportJsonFromFileAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        val toSelect = project.basePath?.let { basePath ->
            val defaultFile = File("$basePath/.pr/review.json")
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(defaultFile)
                ?: LocalFileSystem.getInstance().refreshAndFindFileByIoFile(defaultFile.parentFile)
        }
        val file = FileChooser.chooseFile(descriptor, project, toSelect) ?: return
        log.warn("[CodeReview] import SELECTED: path=${file.path}")

        val json = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
        val imported = try {
            JsonImporter.import(json)
        } catch (ex: JsonImporter.InvalidReviewJsonException) {
            log.warn("[CodeReview] import PARSE_FAILED: ${ex.message}")
            notify(project, "Failed to load review JSON: ${ex.message}", NotificationType.ERROR)
            return
        }
        log.warn("[CodeReview] import PARSED: comments=${imported.comments.size}, summaryLen=${imported.summary.length}")

        val service = ReviewSessionService.getInstance(project)
        val current = service.currentSession
        val hasExisting = current.comments.isNotEmpty() || current.summary.isNotEmpty()
        log.warn("[CodeReview] import CURRENT_SESSION: id=${current.id}, comments=${current.comments.size}, hasExisting=$hasExisting")

        val merge = if (hasExisting) {
            when (Messages.showYesNoCancelDialog(
                project,
                "You have existing review comments. Clear them before loading, or keep them and merge in the loaded ones?",
                "Load Review from JSON",
                "Clear Existing",
                "Keep Existing",
                "Cancel",
                Messages.getQuestionIcon()
            )) {
                Messages.YES -> false
                Messages.NO -> true
                else -> {
                    log.warn("[CodeReview] import CANCELLED by user at merge dialog")
                    return
                }
            }
        } else false
        log.warn("[CodeReview] import MERGE_DECISION: merge=$merge")

        val newSession = if (merge) {
            val existingKeys = current.comments.map { it.dedupeKey() }.toHashSet()
            val newComments = imported.comments.filter { existingKeys.add(it.dedupeKey()) }
            log.warn("[CodeReview] import MERGE: existingKeys=${existingKeys.size}, newComments=${newComments.size} (of ${imported.comments.size} parsed)")
            current.copy(
                summary = mergeSummaries(current.summary, imported.summary),
                comments = (current.comments + newComments).toMutableList()
            )
        } else {
            imported
        }
        log.warn("[CodeReview] import NEW_SESSION: id=${newSession.id}, comments=${newSession.comments.size}")

        service.loadSession(newSession)
        service.lastLoadedJsonPath = file.path
        log.warn("[CodeReview] import AFTER_LOAD_SESSION: service.currentSession.id=${service.currentSession.id}, comments=${service.currentSession.comments.size}")

        ReviewToolWindowFactory.refreshPanel(project)

        notify(project, "Loaded review with ${imported.comments.size} comment(s) from JSON.", NotificationType.INFORMATION)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun ReviewComment.dedupeKey() = listOf(scope, type, text, filePath, lineStart, lineEnd)

    private fun mergeSummaries(existing: String, imported: String): String = when {
        existing.isBlank() -> imported
        imported.isBlank() || existing.contains(imported) -> existing
        else -> "$existing\n\n$imported"
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeReviewAnnotator")
            .createNotification(message, type)
            .notify(project)
    }
}
