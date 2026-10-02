package com.codereview.toolwindow

import com.codereview.editor.InlineCommentManager
import com.codereview.model.CommentScope
import com.codereview.model.CommentType
import com.codereview.model.ReviewComment
import com.codereview.service.ReviewSessionService
import com.codereview.ui.MarkdownEditorPanel
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.diagnostic.Logger
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionListener

class ReviewToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReviewPanel(project)

        val actionGroup = ActionManager.getInstance()
            .getAction("CodeReview.ToolWindowToolbar") as? com.intellij.openapi.actionSystem.ActionGroup
        val commentsWrapper = JPanel(BorderLayout())
        if (actionGroup != null) {
            val toolbar = ActionManager.getInstance()
                .createActionToolbar(ActionPlaces.TOOLBAR, actionGroup, true)
            toolbar.targetComponent = panel.commentsComponent
            commentsWrapper.add(toolbar.component, BorderLayout.NORTH)
        }
        commentsWrapper.add(panel.commentsComponent, BorderLayout.CENTER)

        val contentFactory = ContentFactory.getInstance()
        val commentsContent = contentFactory.createContent(commentsWrapper, "Comments", false)
        val summaryContent = contentFactory.createContent(panel.summaryComponent, "Review Summary", false)
        toolWindow.contentManager.addContent(commentsContent)
        toolWindow.contentManager.addContent(summaryContent)
        panels[project] = panel
    }

    companion object {
        private val log = Logger.getInstance(ReviewToolWindowFactory::class.java)
        private val panels = mutableMapOf<Project, ReviewPanel>()

        fun refreshPanel(project: Project) {
            val panel = panels[project]
            log.warn("[CodeReview] refreshPanel: found=${panel != null}")
            panel?.refresh()
        }
    }
}

private sealed interface ReviewListItem {
    data class Header(val groupTitle: String) : ReviewListItem
    data class Comment(val comment: ReviewComment) : ReviewListItem
}

class ReviewPanel(private val project: Project) {

    private val listModel = DefaultListModel<ReviewListItem>()
    private val commentList = JBList<ReviewListItem>(listModel)
    private val summaryEditor = MarkdownEditorPanel(project).apply {
        textArea.border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
        textArea.toolTipText = "Review summary"
    }
    private var updatingSummary = false

    val commentsComponent: JComponent = JBScrollPane(commentList)
    lateinit var summaryComponent: JComponent
        private set

    init {
        commentList.cellRenderer = ReviewListCellRenderer()
        commentList.fixedCellHeight = -1

        // Suppress selection on header items
        commentList.addListSelectionListener(ListSelectionListener { e ->
            if (e.valueIsAdjusting) return@ListSelectionListener
            val index = commentList.selectedIndex
            if (index >= 0 && listModel.getElementAt(index) is ReviewListItem.Header) {
                commentList.clearSelection()
            }
        })

        commentList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = commentList.locationToIndex(e.point)
                if (index < 0) return
                val cellBounds = commentList.getCellBounds(index, index) ?: return
                if (!cellBounds.contains(e.point)) return

                val item = listModel.getElementAt(index)
                if (item is ReviewListItem.Header) return

                val comment = (item as ReviewListItem.Comment).comment

                // Check if the click is on the delete button
                val relativeX = e.point.x - cellBounds.x
                val relativeY = e.point.y - cellBounds.y
                val renderer = CommentCellPanel()
                renderer.configure(comment, false, commentList)
                renderer.setSize(cellBounds.width, cellBounds.height)
                renderer.doLayout()
                // Force a paint to compute deleteBtnBounds
                val img = java.awt.image.BufferedImage(cellBounds.width, cellBounds.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                renderer.paintComponent(img.createGraphics())

                if (renderer.isDeleteHit(relativeX, relativeY)) {
                    deleteComment(comment)
                } else {
                    navigateToComment(comment)
                }
            }
        })

        commentList.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = commentList.locationToIndex(e.point)
                if (index < 0) {
                    commentList.cursor = Cursor.getDefaultCursor()
                    return
                }
                val cellBounds = commentList.getCellBounds(index, index) ?: return
                if (!cellBounds.contains(e.point)) {
                    commentList.cursor = Cursor.getDefaultCursor()
                    return
                }
                val item = listModel.getElementAt(index)
                if (item is ReviewListItem.Header) {
                    commentList.cursor = Cursor.getDefaultCursor()
                    return
                }
                commentList.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
        })

        // Summary area document listener
        summaryEditor.textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSummaryChanged()
            override fun removeUpdate(e: DocumentEvent) = onSummaryChanged()
            override fun changedUpdate(e: DocumentEvent) = onSummaryChanged()
        })

        val summaryToolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(summaryEditor.toggleButton, BorderLayout.EAST)
        }
        summaryComponent = JPanel(BorderLayout()).apply {
            add(summaryToolbar, BorderLayout.NORTH)
            add(summaryEditor, BorderLayout.CENTER)
        }

        refresh()
    }

    private fun onSummaryChanged() {
        if (updatingSummary) return
        ReviewSessionService.getInstance(project).updateSummary(summaryEditor.text)
    }

    fun refresh() {
        listModel.clear()
        val session = ReviewSessionService.getInstance(project).currentSession
        Logger.getInstance(ReviewPanel::class.java).warn(
            "[CodeReview] ReviewPanel.refresh: sessionId=${session.id}, rawComments=${session.comments.size}"
        )
        val sorted = session.comments
            .filter { it.scope != CommentScope.REVIEW }
            .sortedWith(
                compareBy<ReviewComment> { it.scope.ordinal }
                    .thenBy { it.filePath ?: "" }
                    .thenBy { it.lineStart ?: 0 }
            )

        val grouped = sorted.groupBy { comment ->
            comment.filePath ?: "Unknown"
        }

        for ((groupTitle, comments) in grouped) {
            listModel.addElement(ReviewListItem.Header(groupTitle))
            comments.forEach { listModel.addElement(ReviewListItem.Comment(it)) }
        }
        Logger.getInstance(ReviewPanel::class.java).warn(
            "[CodeReview] ReviewPanel.refresh: afterFilter=${sorted.size}, groups=${grouped.size}, listModelSize=${listModel.size()}"
        )

        // Sync summary text area without triggering the document listener
        updatingSummary = true
        try {
            if (summaryEditor.text != session.summary) {
                summaryEditor.text = session.summary
            }
        } finally {
            updatingSummary = false
        }
    }

    private fun navigateToComment(comment: ReviewComment) {
        val navLog = Logger.getInstance(ReviewPanel::class.java)
        val filePath = comment.filePath ?: return
        val basePath = project.basePath ?: return
        val fullPath = "$basePath/$filePath"
        val vf = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: return
        val line = (comment.lineStart ?: 1) - 1
        val scrollLine = (line - 1).coerceAtLeast(0)

        // DiffManager.showDiff doesn't hand back an Editor to scroll directly, so instead
        // arm InlineCommentManager to scroll to this comment the moment its inlay lands on
        // a real editor (diff-side or plain) — see reapplyInlineComments/scrollToComment.
        InlineCommentManager.requestScroll(comment.id)

        // Try to open diff if file has uncommitted changes
        val change = ChangeListManager.getInstance(project).getChange(vf)
        navLog.warn("[CodeReview] navigateToComment: commentId=${comment.id}, file=$filePath, line=${line + 1}, hasChange=${change != null}")
        if (change != null) {
            val producer = ChangeDiffRequestProducer.create(project, change)
            navLog.warn("[CodeReview] navigateToComment: diff producer=${producer != null}")
            if (producer != null) {
                val chain = ChangeDiffRequestChain(listOf(producer), 0)
                DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
                return
            }
        }

        // Fallback: regular file.
        val descriptor = OpenFileDescriptor(project, vf, scrollLine, 0)
        val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        if (editor == null) {
            navLog.warn("[CodeReview] navigateToComment: openTextEditor returned null")
            return
        }

        // If the editor was already open, no new editorCreated event fired, so the
        // pending-scroll hook above never ran — apply it directly here too (harmless
        // no-op if it already ran).
        val scrolled = InlineCommentManager.scrollToComment(editor, comment)
        navLog.warn("[CodeReview] navigateToComment: direct scrollToComment succeeded=$scrolled")
        if (!scrolled) {
            editor.scrollingModel.scrollTo(LogicalPosition(scrollLine, 0), ScrollType.CENTER)
        }
    }

    private fun deleteComment(comment: ReviewComment) {
        InlineCommentManager.removeInlineComment(comment.id)
        ReviewSessionService.getInstance(project).removeComment(comment.id)
        refresh()
    }
}

private class ReviewListCellRenderer : ListCellRenderer<ReviewListItem> {
    private val headerPanel = HeaderCellPanel()
    private val commentPanel = CommentCellPanel()

    override fun getListCellRendererComponent(
        list: JList<out ReviewListItem>,
        value: ReviewListItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        return when (value) {
            is ReviewListItem.Header -> {
                headerPanel.configure(value.groupTitle)
                headerPanel
            }
            is ReviewListItem.Comment -> {
                commentPanel.configure(value.comment, isSelected, list)
                commentPanel
            }
        }
    }
}

private class HeaderCellPanel : JPanel() {

    private var title: String = ""

    init {
        isOpaque = false
    }

    fun configure(title: String) {
        this.title = title
    }

    override fun getPreferredSize(): Dimension {
        return Dimension(400, 28)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        // Background
        g2.color = JBColor(Color(240, 240, 240), Color(50, 50, 55))
        g2.fillRect(0, 0, width, height)

        // Separator line at bottom
        g2.color = JBColor(Color(220, 220, 220), Color(60, 60, 60))
        g2.fillRect(0, height - 1, width, 1)

        // Title text
        val baseFont = font ?: UIManager.getFont("Label.font") ?: Font("Dialog", Font.PLAIN, 12)
        g2.font = baseFont.deriveFont(Font.BOLD)
        g2.color = JBColor(Color(80, 80, 80), Color(180, 180, 180))
        val fm = g2.fontMetrics
        g2.drawString(title, 10, (height + fm.ascent - fm.descent) / 2)

        g2.dispose()
    }
}

private class CommentCellPanel : JPanel() {

    private var comment: ReviewComment? = null
    private var isSelectedState = false
    private var listRef: JList<*>? = null

    private var deleteBtnBounds: Rectangle? = null

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, 0, 1, 0) // 1px separator
    }

    fun configure(comment: ReviewComment, isSelected: Boolean, list: JList<*>) {
        this.comment = comment
        this.isSelectedState = isSelected
        this.listRef = list
    }

    fun isDeleteHit(x: Int, y: Int): Boolean {
        val bounds = deleteBtnBounds ?: return false
        return bounds.contains(x, y)
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font ?: UIManager.getFont("Label.font") ?: Font("Dialog", Font.PLAIN, 12))
        // Badge line height + comment text line + padding
        val lineHeight = fm.height
        val totalHeight = PADDING_TOP + BADGE_HEIGHT + GAP + lineHeight + PADDING_BOTTOM
        return Dimension(400, totalHeight)
    }

    public override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val comment = this.comment ?: return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val w = width
        val h = height
        val baseFont = font ?: UIManager.getFont("Label.font") ?: Font("Dialog", Font.PLAIN, 12)

        // Background
        if (isSelectedState) {
            g2.color = listRef?.selectionBackground ?: UIManager.getColor("List.selectionBackground")
        } else {
            g2.color = bgColor(comment.type)
        }
        g2.fillRect(0, 0, w, h)

        // Accent bar
        g2.color = accentColor(comment.type)
        g2.fillRect(0, 0, ACCENT_WIDTH, h)

        // Separator line at bottom
        g2.color = JBColor(Color(220, 220, 220), Color(60, 60, 60))
        g2.fillRect(0, h - 1, w, 1)

        val contentX = ACCENT_WIDTH + PADDING_LEFT
        var currentY = PADDING_TOP

        // --- First row: Type badge + location + delete icon ---
        val badgeFont = baseFont.deriveFont(Font.BOLD, baseFont.size2D - 1f)
        g2.font = badgeFont
        val badgeFm = g2.fontMetrics
        val badgeText = comment.type.name
        val badgeTextW = badgeFm.stringWidth(badgeText)
        val badgeW = badgeTextW + BADGE_PAD_H * 2

        // Draw badge pill
        g2.color = accentColor(comment.type)
        g2.fill(RoundRectangle2D.Float(
            contentX.toFloat(), currentY.toFloat(),
            badgeW.toFloat(), BADGE_HEIGHT.toFloat(), 6f, 6f
        ))
        g2.color = Color.WHITE
        val badgeTextY = currentY + badgeFm.ascent + (BADGE_HEIGHT - badgeFm.height) / 2
        g2.drawString(badgeText, contentX + BADGE_PAD_H, badgeTextY)

        // Location text
        val locationFont = baseFont.deriveFont(Font.PLAIN, baseFont.size2D - 1f)
        g2.font = locationFont
        val locFm = g2.fontMetrics
        val locationText = formatLocation(comment) + formatAuthorAndDate(comment)
        if (isSelectedState) {
            g2.color = listRef?.selectionForeground ?: UIManager.getColor("List.selectionForeground")
        } else {
            g2.color = JBColor(Color(120, 120, 120), Color(160, 160, 160))
        }
        val locX = contentX + badgeW + 8
        val locY = currentY + locFm.ascent + (BADGE_HEIGHT - locFm.height) / 2
        g2.drawString(locationText, locX, locY)

        // Delete button (right side) — small "x" icon
        val deleteFont = baseFont.deriveFont(Font.BOLD, baseFont.size2D)
        g2.font = deleteFont
        val delFm = g2.fontMetrics
        val delText = "\u00D7" // multiplication sign as close icon
        val delTextW = delFm.stringWidth(delText)
        val delBtnSize = BADGE_HEIGHT + 2
        val delX = w - PADDING_RIGHT - delBtnSize
        val delY = currentY + (BADGE_HEIGHT - delBtnSize) / 2

        // Delete button background
        g2.color = JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0)) // transparent by default
        g2.fill(RoundRectangle2D.Float(delX.toFloat(), delY.toFloat(), delBtnSize.toFloat(), delBtnSize.toFloat(), 4f, 4f))
        g2.color = JBColor(Color(180, 80, 80), Color(220, 100, 100))
        val delTextX = delX + (delBtnSize - delTextW) / 2
        val delTextY = delY + delFm.ascent + (delBtnSize - delFm.height) / 2
        g2.drawString(delText, delTextX, delTextY)
        deleteBtnBounds = Rectangle(delX, delY, delBtnSize, delBtnSize)

        currentY += BADGE_HEIGHT + GAP

        // --- Second row: Comment text (truncated to single line) ---
        g2.font = baseFont
        val textFm = g2.fontMetrics
        val maxTextW = w - contentX - PADDING_RIGHT
        val displayText = truncateText(comment.text.replace("\n", " "), textFm, maxTextW)

        if (isSelectedState) {
            g2.color = listRef?.selectionForeground ?: UIManager.getColor("List.selectionForeground")
        } else {
            g2.color = JBColor(Color(50, 50, 50), Color(210, 210, 210))
        }
        g2.drawString(displayText, contentX, currentY + textFm.ascent)

        g2.dispose()
    }

    companion object {
        private const val ACCENT_WIDTH = 3
        private const val PADDING_LEFT = 10
        private const val PADDING_RIGHT = 10
        private const val PADDING_TOP = 8
        private const val PADDING_BOTTOM = 8
        private const val BADGE_HEIGHT = 18
        private const val BADGE_PAD_H = 6
        private const val GAP = 4

        private fun accentColor(type: CommentType): Color = when (type) {
            CommentType.ISSUE -> JBColor(Color(220, 50, 50), Color(220, 80, 80))
            CommentType.SUGGESTION -> JBColor(Color(50, 100, 220), Color(80, 130, 255))
            CommentType.NOTE -> JBColor(Color(200, 160, 0), Color(220, 180, 50))
        }

        private fun bgColor(type: CommentType): Color = when (type) {
            CommentType.ISSUE -> JBColor(Color(255, 245, 245), Color(45, 25, 25))
            CommentType.SUGGESTION -> JBColor(Color(245, 247, 255), Color(25, 28, 45))
            CommentType.NOTE -> JBColor(Color(255, 252, 240), Color(42, 40, 25))
        }

        private fun formatAuthorAndDate(comment: ReviewComment): String {
            val parts = listOfNotNull(comment.author, comment.formattedPublishedDate())
            return if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
        }

        private fun formatLocation(comment: ReviewComment): String = when (comment.scope) {
            CommentScope.LINE -> {
                val range = if (comment.lineEnd != null && comment.lineEnd != comment.lineStart) {
                    "L${comment.lineStart}-${comment.lineEnd}"
                } else {
                    "L${comment.lineStart}"
                }
                range
            }
            CommentScope.FILE -> "Whole file"
            CommentScope.REVIEW -> "Review"
        }

        private fun truncateText(text: String, fm: FontMetrics, maxWidth: Int): String {
            if (text.isEmpty()) return ""
            if (fm.stringWidth(text) <= maxWidth) return text
            val ellipsis = "\u2026"
            val ellipsisW = fm.stringWidth(ellipsis)
            var end = text.length
            while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisW > maxWidth) {
                end--
            }
            return text.substring(0, end.coerceAtLeast(1)) + ellipsis
        }
    }
}
