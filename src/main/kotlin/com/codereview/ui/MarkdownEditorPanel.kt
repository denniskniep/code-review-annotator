package com.codereview.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JCEFHtmlPanel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.UIManager

/**
 * A markdown text box: a [JTextArea] that swaps for a rendered preview via
 * [toggleButton]. The button is exposed rather than laid out internally so
 * callers can place it in their own toolbar (e.g. next to a Save button).
 * Rendering uses commonmark (a plain library, not the Markdown plugin's
 * internal/unstable preview classes) plus the platform's own [JCEFHtmlPanel],
 * falling back to a [JEditorPane] when JCEF isn't available.
 *
 * [parentDisposable] controls the JCEF browser's lifetime — pass something
 * scoped to how long this panel is shown (a popup's close event, a
 * DialogWrapper, or the project) so the browser instance is released.
 */
class MarkdownEditorPanel(
    parentDisposable: Disposable,
    initialText: String = ""
) : JPanel(BorderLayout()) {

    val textArea: JTextArea = JTextArea(initialText).apply {
        lineWrap = true
        wrapStyleWord = true
        font = UIManager.getFont("EditorPane.font") ?: font
    }

    private val jcefPanel: JCEFHtmlPanel? = if (JBCefApp.isSupported()) {
        JCEFHtmlPanel("about:blank").also { Disposer.register(parentDisposable, it) }
    } else {
        null
    }
    private val fallbackPane = JEditorPane("text/html", "")

    private val previewComponent: JComponent = jcefPanel?.component ?: JBScrollPane(fallbackPane)

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout).apply {
        add(JBScrollPane(textArea), EDIT_CARD)
        add(previewComponent, PREVIEW_CARD)
    }

    val toggleButton = JButton(AllIcons.General.LayoutPreviewOnly).apply {
        toolTipText = "Preview"
        addActionListener { toggle() }
    }

    private var showingPreview = false

    var text: String
        get() = textArea.text
        set(value) {
            textArea.text = value
        }

    init {
        add(cardPanel, BorderLayout.CENTER)
    }

    private fun toggle() {
        showingPreview = !showingPreview
        if (showingPreview) {
            refreshPreview()
            toggleButton.icon = AllIcons.General.LayoutEditorOnly
            toggleButton.toolTipText = "Edit"
        } else {
            toggleButton.icon = AllIcons.General.LayoutPreviewOnly
            toggleButton.toolTipText = "Preview"
        }
        cardLayout.show(cardPanel, if (showingPreview) PREVIEW_CARD else EDIT_CARD)
    }

    private fun refreshPreview() {
        val html = MarkdownRenderer.toThemedHtml(textArea.text)
        if (jcefPanel != null) {
            jcefPanel.setHtml(html)
        } else {
            fallbackPane.text = html
        }
    }

    companion object {
        private const val EDIT_CARD = "edit"
        private const val PREVIEW_CARD = "preview"
    }
}
