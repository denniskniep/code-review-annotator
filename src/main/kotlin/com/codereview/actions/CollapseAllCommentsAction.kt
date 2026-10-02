package com.codereview.actions

import com.codereview.editor.InlineCommentManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class CollapseAllCommentsAction : AnAction("Collapse All Comments", "Collapse all inline review comment boxes", AllIcons.Actions.Collapseall) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        InlineCommentManager.collapseAll(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
