package com.codereview.editor

import com.codereview.model.CommentType
import com.codereview.model.ReviewComment
import com.codereview.ui.MarkdownRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.*
import javax.swing.JEditorPane

enum class HoveredButton { NONE, EDIT, PREVIEW, COLLAPSE, DELETE }

class InlineCommentRenderer(
    val comment: ReviewComment,
    val previewMode: Boolean = false
) : EditorCustomElementRenderer {

    var editLinkBounds: Rectangle? = null
        private set
    var previewLinkBounds: Rectangle? = null
        private set
    var deleteLinkBounds: Rectangle? = null
        private set
    var collapseLinkBounds: Rectangle? = null
        private set
    var hoveredButton: HoveredButton = HoveredButton.NONE

    private val accentColor: Color
        get() = when (comment.type) {
            CommentType.ISSUE -> JBColor(Color(220, 50, 50), Color(220, 80, 80))
            CommentType.SUGGESTION -> JBColor(Color(50, 100, 220), Color(80, 130, 255))
            CommentType.NOTE -> JBColor(Color(200, 160, 0), Color(220, 180, 50))
        }

    private val backgroundColor: Color
        get() = when (comment.type) {
            CommentType.ISSUE -> JBColor(Color(255, 235, 235), Color(60, 30, 30))
            CommentType.SUGGESTION -> JBColor(Color(235, 240, 255), Color(30, 35, 60))
            CommentType.NOTE -> JBColor(Color(255, 250, 230), Color(55, 50, 30))
        }

    companion object {
        private const val ACCENT_BAR_WIDTH = 3
        private const val PADDING_LEFT = 12
        private const val PADDING_RIGHT = 12
        private const val PADDING_TOP = 6
        private const val PADDING_BOTTOM = 6
        private const val BADGE_HEIGHT = 16
        private const val BADGE_PADDING_H = 6
        private const val GAP_BADGE_TEXT = 6
        private const val ICON_BTN_PAD = 3
        private const val ICON_GAP = 4
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val visibleWidth = inlay.editor.scrollingModel.visibleArea.width
        return (if (visibleWidth > 0) visibleWidth else inlay.editor.component.width).coerceAtLeast(400)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val availableWidth = calcWidthInPixels(inlay) - ACCENT_BAR_WIDTH - PADDING_LEFT - PADDING_RIGHT - 10
        val contentHeight = if (previewMode) {
            buildPreviewPane(availableWidth.coerceAtLeast(100)).preferredSize.height
        } else {
            val font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
            val metrics = editor.component.getFontMetrics(font)
            val wrappedLineCount = wrapText(comment.text, metrics, availableWidth.coerceAtLeast(100))
            wrappedLineCount * metrics.height
        }
        return PADDING_TOP + BADGE_HEIGHT + GAP_BADGE_TEXT + contentHeight + PADDING_BOTTOM
    }

    // ponytail: renders markdown preview via a detached JEditorPane painted onto the inlay's
    // Graphics2D (EditorCustomElementRenderer has no live Swing component tree to embed into,
    // so JCEFHtmlPanel — used elsewhere for markdown preview — isn't paintable here).
    private fun buildPreviewPane(width: Int): JEditorPane {
        val pane = JEditorPane("text/html", MarkdownRenderer.toThemedHtml(comment.text, backgroundColor))
        pane.isOpaque = false
        pane.setSize(width, Short.MAX_VALUE.toInt())
        return pane
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val editor = inlay.editor
        val font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
        val metrics = g2.getFontMetrics(font)

        val x = targetRegion.x
        val y = targetRegion.y
        val width = targetRegion.width
        val height = targetRegion.height

        // Background
        g2.color = backgroundColor
        g2.fillRect(x, y, width, height)

        // Accent bar
        g2.color = accentColor
        g2.fillRect(x, y, ACCENT_BAR_WIDTH, height)

        val contentX = x + ACCENT_BAR_WIDTH + PADDING_LEFT
        val availableWidth = width - ACCENT_BAR_WIDTH - PADDING_LEFT - PADDING_RIGHT - 10
        var currentY = y + PADDING_TOP

        // Type badge
        val badgeText = comment.type.name
        g2.font = font.deriveFont(Font.BOLD, font.size2D - 1)
        val badgeMetrics = g2.fontMetrics
        val badgeTextWidth = badgeMetrics.stringWidth(badgeText)
        val badgeWidth = badgeTextWidth + BADGE_PADDING_H * 2

        g2.color = accentColor
        g2.fillRoundRect(contentX, currentY, badgeWidth, BADGE_HEIGHT, 6, 6)
        g2.color = Color.WHITE
        g2.drawString(badgeText, contentX + BADGE_PADDING_H, currentY + badgeMetrics.ascent + (BADGE_HEIGHT - badgeMetrics.height) / 2)

        // Action icon buttons (top-right after badge)
        val editIcon = AllIcons.General.Inline_edit
        val deleteIcon = AllIcons.Actions.GC
        val iconW = editIcon.iconWidth
        val iconH = editIcon.iconHeight
        val iconBtnW = iconW + ICON_BTN_PAD * 2
        val iconOffsetY = (BADGE_HEIGHT - iconH) / 2
        val hoverColor = JBColor(Color(0, 0, 0, 30), Color(255, 255, 255, 30))

        val editBtnX = contentX + badgeWidth + GAP_BADGE_TEXT * 2
        if (hoveredButton == HoveredButton.EDIT) {
            g2.color = hoverColor
            g2.fillRoundRect(editBtnX, currentY, iconBtnW, BADGE_HEIGHT, 4, 4)
        }
        editIcon.paintIcon(inlay.editor.component, g2, editBtnX + ICON_BTN_PAD, currentY + iconOffsetY)
        editLinkBounds = Rectangle(editBtnX, currentY - y, iconBtnW, BADGE_HEIGHT)

        val previewIcon = if (previewMode) AllIcons.General.LayoutEditorOnly else AllIcons.General.LayoutPreviewOnly
        val previewBtnX = editBtnX + iconBtnW + ICON_GAP
        if (hoveredButton == HoveredButton.PREVIEW) {
            g2.color = hoverColor
            g2.fillRoundRect(previewBtnX, currentY, iconBtnW, BADGE_HEIGHT, 4, 4)
        }
        previewIcon.paintIcon(inlay.editor.component, g2, previewBtnX + ICON_BTN_PAD, currentY + iconOffsetY)
        previewLinkBounds = Rectangle(previewBtnX, currentY - y, iconBtnW, BADGE_HEIGHT)

        val collapseIcon = AllIcons.Actions.Collapseall
        val collapseBtnX = previewBtnX + iconBtnW + ICON_GAP
        if (hoveredButton == HoveredButton.COLLAPSE) {
            g2.color = hoverColor
            g2.fillRoundRect(collapseBtnX, currentY, iconBtnW, BADGE_HEIGHT, 4, 4)
        }
        collapseIcon.paintIcon(inlay.editor.component, g2, collapseBtnX + ICON_BTN_PAD, currentY + iconOffsetY)
        collapseLinkBounds = Rectangle(collapseBtnX, currentY - y, iconBtnW, BADGE_HEIGHT)

        val deleteBtnX = x + width - PADDING_RIGHT - iconBtnW
        if (hoveredButton == HoveredButton.DELETE) {
            g2.color = hoverColor
            g2.fillRoundRect(deleteBtnX, currentY, iconBtnW, BADGE_HEIGHT, 4, 4)
        }
        deleteIcon.paintIcon(inlay.editor.component, g2, deleteBtnX + ICON_BTN_PAD, currentY + iconOffsetY)
        deleteLinkBounds = Rectangle(deleteBtnX, currentY - y, iconBtnW, BADGE_HEIGHT)

        currentY += BADGE_HEIGHT + GAP_BADGE_TEXT

        if (previewMode) {
            val pane = buildPreviewPane(availableWidth.coerceAtLeast(100))
            pane.setSize(pane.width, pane.preferredSize.height)
            val paneGraphics = g2.create(contentX, currentY, pane.width, pane.height) as Graphics2D
            pane.paint(paneGraphics)
            paneGraphics.dispose()
        } else {
            // Wrapped comment text
            g2.font = font
            g2.color = JBColor(Color(50, 50, 50), Color(200, 200, 200))
            val lines = wrapTextToLines(comment.text, metrics, availableWidth.coerceAtLeast(100))
            for (line in lines) {
                g2.drawString(line, contentX, currentY + metrics.ascent)
                currentY += metrics.height
            }
        }

        g2.dispose()
    }

    private fun wrapText(text: String, metrics: FontMetrics, maxWidth: Int): Int {
        return wrapTextToLines(text, metrics, maxWidth).size
    }

    private fun wrapTextToLines(text: String, metrics: FontMetrics, maxWidth: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isEmpty()) {
                result.add("")
                continue
            }
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                if (metrics.stringWidth(remaining) <= maxWidth) {
                    result.add(remaining)
                    break
                }
                var breakIndex = remaining.length
                for (i in remaining.indices) {
                    if (metrics.stringWidth(remaining.substring(0, i + 1)) > maxWidth) {
                        breakIndex = i
                        break
                    }
                }
                // Try to break at a space
                val spaceIndex = remaining.lastIndexOf(' ', breakIndex - 1)
                val actualBreak = if (spaceIndex > 0) spaceIndex else breakIndex.coerceAtLeast(1)
                result.add(remaining.substring(0, actualBreak))
                remaining = remaining.substring(actualBreak).trimStart()
            }
        }
        return result.ifEmpty { listOf("") }
    }
}
