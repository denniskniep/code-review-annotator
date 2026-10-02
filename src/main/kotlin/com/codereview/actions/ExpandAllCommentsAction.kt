package com.codereview.actions

import com.codereview.editor.InlineCommentManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class ExpandAllCommentsAction : AnAction("Expand All Comments", "Expand all inline review comment boxes", AllIcons.Actions.Expandall) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        InlineCommentManager.expandAll(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
