package com.codereview.ui

import com.intellij.util.ui.UIUtil
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.DefaultUrlSanitizer
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.Color

/** Shared commonmark markdown -> theme-styled HTML conversion, used by any panel/renderer that previews markdown. */
object MarkdownRenderer {

    private val parser = Parser.builder().build()
    private val htmlRenderer = HtmlRenderer.builder()
        .escapeHtml(true)
        .sanitizeUrls(true)
        .urlSanitizer(DefaultUrlSanitizer(listOf("http", "https", "mailto")))
        .build()

    fun toThemedHtml(markdown: String, background: Color = UIUtil.getPanelBackground()): String {
        val body = htmlRenderer.render(parser.parse(markdown))
        val bg = background.toCssHex()
        val fg = UIUtil.getLabelForeground().toCssHex()
        val codeBg = UIUtil.getTextFieldBackground().toCssHex()
        return """
            <html><head><style>
                body { background-color: $bg; color: $fg; font-family: sans-serif; margin: 8px; }
                code, pre { background-color: $codeBg; }
            </style></head><body>$body</body></html>
        """.trimIndent()
    }

    private fun Color.toCssHex(): String = "#%06X".format(rgb and 0xFFFFFF)
}
