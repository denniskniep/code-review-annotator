package com.codereview.editor

import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import java.awt.Rectangle

class InlineCommentHoverHandler : EditorMouseMotionListener {

    private var lastHoveredInlay: Inlay<*>? = null
    private var lastHoveredButton: HoveredButton = HoveredButton.NONE

    override fun mouseMoved(e: EditorMouseEvent) {
        val editor = e.editor
        if (editor.isDisposed) return
        val point = e.mouseEvent.point

        val inlays = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)

        var newHoveredInlay: Inlay<*>? = null
        var newHoveredButton = HoveredButton.NONE

        for (inlay in inlays) {
            val renderer = inlay.renderer as? InlineCommentRenderer ?: continue
            val bounds = inlay.bounds ?: continue
            if (!bounds.contains(point)) continue

            val editRect = renderer.editLinkBounds?.let { Rectangle(it.x, bounds.y + it.y, it.width, it.height) }
            val previewRect = renderer.previewLinkBounds?.let { Rectangle(it.x, bounds.y + it.y, it.width, it.height) }
            val collapseRect = renderer.collapseLinkBounds?.let { Rectangle(it.x, bounds.y + it.y, it.width, it.height) }
            val deleteRect = renderer.deleteLinkBounds?.let { Rectangle(it.x, bounds.y + it.y, it.width, it.height) }

            newHoveredButton = when {
                editRect?.contains(point) == true -> HoveredButton.EDIT
                previewRect?.contains(point) == true -> HoveredButton.PREVIEW
                collapseRect?.contains(point) == true -> HoveredButton.COLLAPSE
                deleteRect?.contains(point) == true -> HoveredButton.DELETE
                else -> HoveredButton.NONE
            }
            newHoveredInlay = inlay
            break
        }

        if (lastHoveredInlay != newHoveredInlay || lastHoveredButton != newHoveredButton) {
            val prevInlay = lastHoveredInlay
            if (prevInlay != null && prevInlay.isValid) {
                val renderer = prevInlay.renderer as? InlineCommentRenderer
                if (renderer != null && renderer.hoveredButton != HoveredButton.NONE) {
                    renderer.hoveredButton = HoveredButton.NONE
                    try { prevInlay.repaint() } catch (_: Exception) {}
                }
            }

            if (newHoveredInlay != null && newHoveredButton != HoveredButton.NONE) {
                val renderer = newHoveredInlay.renderer as? InlineCommentRenderer
                if (renderer != null) {
                    renderer.hoveredButton = newHoveredButton
                    try { newHoveredInlay.repaint() } catch (_: Exception) {}
                }
            }

            lastHoveredInlay = newHoveredInlay
            lastHoveredButton = newHoveredButton
        }
    }
}
