package com.codereview.editor

import com.codereview.model.CommentType
import com.codereview.model.ReviewComment
import com.codereview.service.ReviewSessionService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import java.awt.Dimension
import java.awt.Point
import javax.swing.Icon

object InlineCommentManager {

    private val log = Logger.getInstance(InlineCommentManager::class.java)
    private val inlays = mutableMapOf<String, Inlay<InlineCommentRenderer>>()
    private val previewedCommentIds = mutableSetOf<String>()
    private val collapsedCommentIds = mutableSetOf<String>()
    private data class CollapsedGutterEntry(val editor: Editor, val highlighter: RangeHighlighter)
    private val collapsedGutterHighlighters = mutableMapOf<String, CollapsedGutterEntry>()
    private var currentEditorPopup: JBPopup? = null

    // Set by navigateToComment before it opens/shows the target editor (plain file or
    // diff view). We can't get a synchronous Editor handle back from DiffManager.showDiff,
    // so instead we scroll from here, the moment the comment's inlay actually lands on a
    // real editor — which works uniformly for a freshly-created editor (diff or plain) and
    // is re-checked directly by scrollToComment for the already-open-editor case.
    @Volatile
    private var pendingScrollCommentId: String? = null

    fun requestScroll(commentId: String) {
        pendingScrollCommentId = commentId
    }

    fun scrollToComment(editor: Editor, comment: ReviewComment): Boolean {
        // Computed straight from the comment's own line range rather than the inlay: the
        // inlay can legitimately not exist yet (e.g. a diff-side editor whose document is
        // still being populated when editorCreated fires), which used to mean "no scroll at
        // all". The offset here matches exactly what addInlineComment would anchor to, so
        // positioning stays correct whether or not the inlay has actually been created.
        val document = editor.document
        val lineStart = (comment.lineStart ?: return false) - 1
        if (lineStart < 0 || lineStart >= document.lineCount) return false
        if (pendingScrollCommentId == comment.id) pendingScrollCommentId = null

        // Land one line above the comment's line so it isn't flush with the viewport edge;
        // floors at the document's first line when the comment itself is on line 1.
        val targetLine = (lineStart - 1).coerceAtLeast(0)
        val offset = document.getLineStartOffset(targetLine)

        // Deferred twice: the platform's own post-open scroll/caret positioning (from
        // OpenFileDescriptor navigation or the diff viewer's default view) runs via its own
        // invokeLater *after* this method is called from editorCreated/reapplyInlineComments.
        // A single invokeLater can still land before that. Queuing a second invokeLater from
        // within the first pushes us behind it, so our top-alignment is the final scroll applied.
        val app = ApplicationManager.getApplication()
        app.invokeLater {
            app.invokeLater {
                val topY = editor.offsetToXY(offset).y
                editor.scrollingModel.scrollVertically(topY)
            }
        }
        return true
    }

    fun togglePreview(project: Project, editor: Editor, comment: ReviewComment) {
        if (!previewedCommentIds.add(comment.id)) previewedCommentIds.remove(comment.id)
        updateInlineComment(project, editor, comment)
    }

    fun setCollapsed(project: Project, editor: Editor, comment: ReviewComment, collapsed: Boolean) {
        if (collapsed) collapsedCommentIds.add(comment.id) else collapsedCommentIds.remove(comment.id)
        updateInlineComment(project, editor, comment)
    }

    fun collapseAll(project: Project) = setAllCollapsed(project, true)

    fun expandAll(project: Project) = setAllCollapsed(project, false)

    private fun setAllCollapsed(project: Project, collapsed: Boolean) {
        val commentsById = ReviewSessionService.getInstance(project).currentSession.comments.associateBy { it.id }
        if (collapsed) collapsedCommentIds.addAll(commentsById.keys) else collapsedCommentIds.removeAll(commentsById.keys)

        // A comment is currently represented either by a visible inlay or by a collapsed
        // gutter icon (never both) — gather editors from whichever one exists.
        val editorsByCommentId = mutableMapOf<String, Editor>()
        inlays.forEach { (id, inlay) -> if (inlay.isValid) editorsByCommentId[id] = inlay.editor }
        collapsedGutterHighlighters.forEach { (id, entry) -> editorsByCommentId.putIfAbsent(id, entry.editor) }

        editorsByCommentId.forEach { (id, editor) ->
            val comment = commentsById[id] ?: return@forEach
            updateInlineComment(project, editor, comment)
        }
    }

    // True when the given editor line is showing a collapsed comment's gutter icon — used to
    // suppress the ephemeral "add comment" (+) gutter icon from competing for the same slot.
    fun isLineCollapsed(editor: Editor, line0: Int): Boolean {
        return collapsedGutterHighlighters.values.any { entry ->
            entry.editor == editor && entry.highlighter.isValid &&
                entry.editor.document.getLineNumber(entry.highlighter.startOffset) == line0
        }
    }

    fun addInlineComment(@Suppress("UNUSED_PARAMETER") project: Project, editor: Editor, comment: ReviewComment) {
        val lineStart = (comment.lineStart ?: return) - 1
        val document = editor.document
        if (lineStart < 0 || lineStart >= document.lineCount) {
            log.warn("[CodeReview] addInlineComment SKIP: commentId=${comment.id}, lineStart=$lineStart out of range (lineCount=${document.lineCount})")
            return
        }

        // Collapsed comments have no visual box at all — just the gutter icon next to the
        // line number — so no block element is created for them.
        if (comment.id in collapsedCommentIds) {
            addCollapsedGutterIcon(editor, comment)
            return
        }
        removeCollapsedGutterIcon(comment.id)

        val lineEnd = ((comment.lineEnd ?: comment.lineStart) - 1).coerceIn(0, document.lineCount - 1)
        val offset = document.getLineEndOffset(lineEnd)

        val renderer = InlineCommentRenderer(comment, previewMode = comment.id in previewedCommentIds)
        val properties = InlayProperties()
            .relatesToPrecedingText(true)
            .showAbove(false)
            .priority(0)

        val inlay = editor.inlayModel.addBlockElement(offset, properties, renderer)
        if (inlay == null) {
            log.warn("[CodeReview] addInlineComment FAILED: addBlockElement returned null for commentId=${comment.id}, offset=$offset, editor=${System.identityHashCode(editor)}")
            return
        }
        log.warn("[CodeReview] addInlineComment OK: commentId=${comment.id}, line=${lineStart+1}, editor=${System.identityHashCode(editor)}, inlay=${System.identityHashCode(inlay)}")
        inlays[comment.id] = inlay
    }

    private fun addCollapsedGutterIcon(editor: Editor, comment: ReviewComment) {
        removeCollapsedGutterIcon(comment.id)
        val lineStart = (comment.lineStart ?: return) - 1
        val document = editor.document
        if (lineStart < 0 || lineStart >= document.lineCount) return

        val offset = document.getLineStartOffset(lineStart)
        val highlighter = editor.markupModel.addRangeHighlighter(
            offset, offset,
            HighlighterLayer.LAST,
            null,
            HighlighterTargetArea.LINES_IN_RANGE
        )
        highlighter.gutterIconRenderer = CollapsedCommentGutterIconRenderer(editor, comment)
        collapsedGutterHighlighters[comment.id] = CollapsedGutterEntry(editor, highlighter)
    }

    private fun removeCollapsedGutterIcon(commentId: String) {
        collapsedGutterHighlighters.remove(commentId)?.let { entry ->
            try { entry.editor.markupModel.removeHighlighter(entry.highlighter) } catch (_: Exception) {}
        }
    }

    fun removeInlineComment(commentId: String) {
        inlays.remove(commentId)?.let { inlay ->
            try { inlay.dispose() } catch (_: Exception) {}
        }
        removeCollapsedGutterIcon(commentId)
    }

    fun updateInlineComment(project: Project, editor: Editor, comment: ReviewComment) {
        removeInlineComment(comment.id)
        addInlineComment(project, editor, comment)
    }

    fun clearAllInlineComments(project: Project) {
        val session = ReviewSessionService.getInstance(project).currentSession
        session.comments.forEach { removeInlineComment(it.id) }
        inlays.values.forEach { inlay ->
            try { inlay.dispose() } catch (_: Exception) {}
        }
        inlays.clear()
        previewedCommentIds.clear()
        collapsedCommentIds.clear()
        collapsedGutterHighlighters.values.forEach { entry ->
            try { entry.editor.markupModel.removeHighlighter(entry.highlighter) } catch (_: Exception) {}
        }
        collapsedGutterHighlighters.clear()
    }

    fun reapplyInlineComments(project: Project, editor: Editor, filePath: String, allowScroll: Boolean = true) {
        val session = ReviewSessionService.getInstance(project).currentSession
        val matching = session.comments.filter { it.filePath == filePath && it.lineStart != null }
        log.warn("[CodeReview] reapplyInlineComments: filePath=$filePath, editor=${System.identityHashCode(editor)}, matchingComments=${matching.size}, totalInlays=${inlays.size}")
        matching.forEach { comment ->
                val existing = inlays[comment.id]
                log.warn("[CodeReview] reapplyInlineComments check: commentId=${comment.id}, existing=${existing != null}, isValid=${existing?.isValid}, existingEditor=${existing?.let { System.identityHashCode(it.editor) }}, currentEditor=${System.identityHashCode(editor)}, sameEditor=${existing?.editor == editor}")
                if (existing == null || !existing.isValid || existing.editor != editor) {
                    log.warn("[CodeReview] reapplyInlineComments RECREATING: commentId=${comment.id}")
                    existing?.let { try { it.dispose() } catch (_: Exception) {} }
                    inlays.remove(comment.id)
                    addInlineComment(project, editor, comment)
                } else {
                    log.warn("[CodeReview] reapplyInlineComments SKIPPED (already valid): commentId=${comment.id}")
                }
                if (allowScroll && pendingScrollCommentId == comment.id) {
                    log.warn("[CodeReview] reapplyInlineComments: consuming pending scroll for commentId=${comment.id}")
                    scrollToComment(editor, comment)
                }
            }
    }

    /**
     * Shows an inline editor popup below the given line for creating or editing a comment.
     * @param line1Based the 1-based line number to anchor below
     * @param existingComment if non-null, pre-fills the editor with this comment's data
     * @param onSave callback receiving (type, text) when the user saves
     */
    fun showEditorPopup(
        editor: Editor,
        line1Based: Int,
        existingComment: ReviewComment?,
        onSave: (CommentType, String) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val project = editor.project ?: return

        // Dismiss any existing editor popup
        currentEditorPopup?.cancel()
        currentEditorPopup = null

        val document = editor.document
        val line0 = (line1Based - 1).coerceIn(0, document.lineCount - 1)
        val offset = document.getLineEndOffset(line0)
        val visualPoint = editor.offsetToXY(offset)
        val lineHeight = editor.lineHeight

        val panel = InlineCommentEditorPanel(
            project = project,
            initialType = existingComment?.type ?: CommentType.ISSUE,
            initialText = existingComment?.text ?: "",
            author = existingComment?.author,
            publishedDate = existingComment?.publishedDate,
            onSave = { type, text ->
                currentEditorPopup?.cancel()
                currentEditorPopup = null
                onSave(type, text)
            },
            onCancel = {
                currentEditorPopup?.cancel()
                currentEditorPopup = null
                onCancel?.invoke()
            }
        )

        // Natural height from the panel's own layout, but width pinned to the editor's
        // visible viewport — NOT contentComponent.width, which is the virtual/scrollable
        // content width (as wide as the longest line). visibleArea is the actual on-screen
        // pane, already scoped to just one side in a diff/split view.
        val popupWidth = editor.scrollingModel.visibleArea.width
        val popupHeight = panel.preferredSize.height

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel.textArea)
            .setRequestFocus(true)
            .setFocusable(true)
            // A title is required for setMovable to have a visible drag handle —
            // without it the header exists but renders no draggable area.
            //.setTitle("Comment")
            .setMovable(true)
            .setResizable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(false) // We handle ESC in the panel
            .createPopup()

        // setPreferredSize on the content panel isn't respected reliably by AbstractPopup
        // (screen-fitting/content-expansion can override it) — set the popup's own size
        // explicitly instead, per JBPopup.setSize(Dimension).
        popup.setSize(Dimension(popupWidth, popupHeight))

        currentEditorPopup = popup
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                Disposer.dispose(panel)
            }
        })

        // Position just below the target line, flush with the visible left edge of the
        // pane (not x=0 of the contentComponent, which is off-screen if scrolled right).
        val showPoint = Point(editor.scrollingModel.visibleArea.x, visualPoint.y + lineHeight)
        popup.show(RelativePoint(editor.contentComponent, showPoint))
    }
}

private class CollapsedCommentGutterIconRenderer(
    private val editor: Editor,
    private val comment: ReviewComment
) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.Actions.Expandall

    override fun getTooltipText(): String = "Expand review comment"

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val project = editor.project ?: return
            InlineCommentManager.setCollapsed(project, editor, comment, false)
        }
    }

    override fun isNavigateAction(): Boolean = false

    override fun equals(other: Any?): Boolean = other is CollapsedCommentGutterIconRenderer && other.comment.id == comment.id

    override fun hashCode(): Int = comment.id.hashCode()
}
