package com.codereview.actions

import com.codereview.model.CommentScope
import com.codereview.model.CommentType
import com.codereview.model.ReviewComment
import com.codereview.service.ReviewSessionService
import com.codereview.toolwindow.ReviewToolWindowFactory
import com.codereview.ui.MarkdownEditorPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class AddReviewCommentAction : AnAction("Add Review Comment", "Add a review-wide comment", AllIcons.General.Add) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = CommentDialog(project, "Add Review Comment")
        if (dialog.showAndGet()) {
            val comment = ReviewComment(
                scope = CommentScope.REVIEW,
                type = dialog.selectedType,
                text = dialog.commentText
            )
            ReviewSessionService.getInstance(project).addComment(comment)
            ReviewToolWindowFactory.refreshPanel(project)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

class CommentDialog(
    project: Project,
    title: String,
    initialType: CommentType = CommentType.NOTE,
    initialText: String = "",
    private val filePath: String? = null,
    private val lineInfo: String? = null
) : DialogWrapper(project) {

    private val typeCombo = ComboBox(CommentType.entries.toTypedArray()).apply {
        selectedItem = initialType
    }
    private val previewDisposable = Disposer.newDisposable()
    private val markdownEditor = MarkdownEditorPanel(previewDisposable, initialText)

    val selectedType: CommentType get() = typeCombo.selectedItem as CommentType
    val commentText: String get() = markdownEditor.text.trim()

    init {
        this.title = title
        init()
    }

    override fun dispose() {
        Disposer.dispose(previewDisposable)
        super.dispose()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            if (filePath != null) {
                add(JLabel("File: $filePath"))
            }
            if (lineInfo != null) {
                add(JLabel("Lines: $lineInfo"))
            }
            val typeRow = JPanel(BorderLayout(4, 0))
            typeRow.add(JLabel("Type:"), BorderLayout.WEST)
            typeRow.add(typeCombo, BorderLayout.CENTER)
            typeRow.add(markdownEditor.toggleButton, BorderLayout.EAST)
            add(typeRow)
        }
        panel.add(topPanel, BorderLayout.NORTH)

        markdownEditor.preferredSize = Dimension(400, 150)
        panel.add(JLabel("Comment:"), BorderLayout.WEST)
        panel.add(markdownEditor, BorderLayout.CENTER)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = markdownEditor.textArea
}
