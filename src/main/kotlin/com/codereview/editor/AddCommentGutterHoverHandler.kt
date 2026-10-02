package com.codereview.editor

import com.codereview.model.CommentScope
import com.codereview.model.ReviewComment
import com.codereview.service.ReviewSessionService
import com.codereview.toolwindow.ReviewToolWindowFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.icons.AllIcons
import javax.swing.Icon

class AddCommentGutterHoverHandler(private val project: Project) : EditorMouseMotionListener {

    private var currentHighlighter: RangeHighlighter? = null
    private var currentLine: Int = -1

    override fun mouseMoved(e: EditorMouseEvent) {
        val editor = e.editor
        if (editor.isDisposed) return

        val logicalPosition = editor.xyToLogicalPosition(e.mouseEvent.point)
        val line = logicalPosition.line
        val document = editor.document

        if (line < 0 || line >= document.lineCount) {
            clearHighlighter(editor)
            return
        }

        if (InlineCommentManager.isLineCollapsed(editor, line)) {
            clearHighlighter(editor)
            return
        }

        if (line == currentLine) return

        clearHighlighter(editor)
        currentLine = line

        val offset = document.getLineStartOffset(line)
        val highlighter = editor.markupModel.addRangeHighlighter(
            offset, offset,
            HighlighterLayer.LAST,
            null,
            HighlighterTargetArea.LINES_IN_RANGE
        )
        highlighter.gutterIconRenderer = AddCommentGutterIconRenderer(editor, project, line)
        currentHighlighter = highlighter
    }

    fun clearHighlighter(editor: Editor) {
        currentHighlighter?.let {
            try { editor.markupModel.removeHighlighter(it) } catch (_: Exception) {}
        }
        currentHighlighter = null
        currentLine = -1
    }
}

private class AddCommentGutterIconRenderer(
    private val editor: Editor,
    private val project: Project,
    private val line0: Int
) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.General.InlineAdd

    override fun getTooltipText(): String = "Add review comment"

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val document = editor.document
            if (line0 < 0 || line0 >= document.lineCount) return

            val filePath = FileDocumentManager.getInstance().getFile(document)?.path ?: return
            val projectBasePath = project.basePath ?: ""
            val relativePath = if (filePath.startsWith(projectBasePath))
                filePath.removePrefix(projectBasePath).removePrefix("/")
            else filePath

            val line1Based = line0 + 1
            InlineCommentManager.showEditorPopup(
                editor = editor,
                line1Based = line1Based,
                existingComment = null,
                onSave = { type, text ->
                    val comment = ReviewComment(
                        scope = CommentScope.LINE,
                        type = type,
                        text = text,
                        filePath = relativePath,
                        lineStart = line1Based,
                        lineEnd = null
                    )
                    ReviewSessionService.getInstance(project).addComment(comment)
                    InlineCommentManager.addInlineComment(project, editor, comment)
                    ReviewToolWindowFactory.refreshPanel(project)
                }
            )
        }
    }

    override fun isNavigateAction(): Boolean = false

    override fun equals(other: Any?): Boolean = other is AddCommentGutterIconRenderer && other.line0 == line0

    override fun hashCode(): Int = line0
}
