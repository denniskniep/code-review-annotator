package com.codereview.actions

import com.codereview.export.JsonExporter
import com.codereview.model.CommentScope
import com.codereview.service.ReviewSessionService
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

class SaveJsonToFileAction : AnAction(
    "Save Review as JSON...",
    "Save all review comments as JSON to a file",
    AllIcons.Actions.Download
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ReviewSessionService.getInstance(project)
        val session = service.currentSession

        if (session.comments.isEmpty() && session.summary.isEmpty()) {
            notify(project, "No review content to save.", NotificationType.WARNING)
            return
        }

        val basePath = project.basePath ?: return
        val defaultFile = File(service.lastLoadedJsonPath ?: "$basePath/.pr/review.json")
        defaultFile.parentFile?.mkdirs()
        val defaultDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(defaultFile.parentFile) ?: return

        val descriptor = FileSaverDescriptor("Save Review as JSON", "Choose where to save the review JSON", "json")
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(defaultDir, defaultFile.name) ?: return

        val commentCount = session.comments.count { it.scope != CommentScope.REVIEW }
        val target = wrapper.file
        target.writeText(JsonExporter.export(session))
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)

        notify(project, "Saved review with $commentCount comment(s) as JSON to ${target.name}.", NotificationType.INFORMATION)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeReviewAnnotator")
            .createNotification(message, type)
            .notify(project)
    }
}
