package com.codereview.editor

import com.codereview.service.ReviewSessionService
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Startup activity that registers an [EditorFactoryListener] to reapply
 * review comments whenever a new editor is created.
 *
 * We register programmatically rather than via XML `applicationListeners`
 * because IntelliJ 2024.1+ changed the dispatch signature from
 * `editorCreated(EditorFactoryEvent)` to `editorCreated(Editor)`.
 * Programmatic registration via [EditorFactory.addEditorFactoryListener]
 * works across all platform versions.
 */
class ReviewEditorListenerStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val listener = ReviewEditorFactoryListenerImpl()
        EditorFactory.getInstance().addEditorFactoryListener(listener, project)
    }
}

private class ReviewEditorFactoryListenerImpl : EditorFactoryListener {

    private val log = Logger.getInstance(ReviewEditorFactoryListenerImpl::class.java)

    companion object {
        private val CLICK_HANDLER_INSTALLED = Key.create<Boolean>("codereview.clickHandlerInstalled")
        private val HOVER_HANDLER_INSTALLED = Key.create<Boolean>("codereview.hoverHandlerInstalled")
        private val BUTTON_HOVER_HANDLER_INSTALLED = Key.create<Boolean>("codereview.buttonHoverHandlerInstalled")
        private val HANDLED = Key.create<Boolean>("codereview.editorCreatedHandled")
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        handleEditorCreated(event.editor)
    }

    private fun handleEditorCreated(editor: Editor) {
        if (editor.getUserData(HANDLED) == true) return
        editor.putUserData(HANDLED, true)

        val project = editor.project ?: run {
            log.warn("[CodeReview] editorCreated: SKIP — editor.project is null, editor=${System.identityHashCode(editor)}, docClass=${editor.document::class.java.name}")
            return
        }
        val projectBasePath = project.basePath ?: return
        val session = ReviewSessionService.getInstance(project).currentSession

        val vfile = FileDocumentManager.getInstance().getFile(editor.document)
        log.warn("[CodeReview] editorCreated ENTER: editor=${System.identityHashCode(editor)}, document=${System.identityHashCode(editor.document)}, docClass=${editor.document::class.java.name}, vfile=${vfile?.path}, vfileClass=${vfile?.javaClass?.name}, isDisposed=${editor.isDisposed}, totalComments=${session.comments.size}")

        // Reverse lookup: find which comment filePath matches this editor's document
        val commentPaths = session.comments
            .mapNotNull { it.filePath }
            .distinct()

        var matchedPath: String? = null
        for (relativePath in commentPaths) {
            val fullPath = "$projectBasePath/$relativePath"
            val vf = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: continue
            val doc = FileDocumentManager.getInstance().getDocument(vf) ?: continue
            log.warn("[CodeReview] editorCreated path check: relativePath=$relativePath, docFromVf=${System.identityHashCode(doc)}, editorDoc=${System.identityHashCode(editor.document)}, same=${doc === editor.document}")
            if (doc === editor.document) {
                matchedPath = relativePath
                break
            }
        }

        // Fallback for regular editors (also handles files with no comments yet)
        if (matchedPath == null) {
            val file = FileDocumentManager.getInstance().getFile(editor.document)
            if (file == null) {
                log.warn("[CodeReview] editorCreated: SKIP — FileDocumentManager.getFile() returned null for document ${System.identityHashCode(editor.document)}")
                return
            }
            matchedPath = file.path.let {
                if (it.startsWith(projectBasePath)) it.removePrefix(projectBasePath).removePrefix("/") else it
            }
        }

        // A diff view's "before" side is backed by a synthetic in-memory VirtualFile
        // (e.g. DiffContentFactoryImpl's light file), not the real file on disk — it
        // can fire editorCreated before the "after"/live side does. Only the real,
        // on-disk file is eligible to consume a pending scroll request: otherwise the
        // before-side editor (wrong content, possibly different line numbers) steals it
        // and the live editor the user is actually looking at never gets scrolled.
        val isLocalFile = vfile?.isInLocalFileSystem() == true
        log.warn("[CodeReview] editorCreated: matchedPath=$matchedPath, editor=${System.identityHashCode(editor)}, commentsForPath=${session.comments.count { it.filePath == matchedPath }}, isLocalFile=$isLocalFile")
        InlineCommentManager.reapplyInlineComments(project, editor, matchedPath, allowScroll = isLocalFile)

        if (editor.getUserData(CLICK_HANDLER_INSTALLED) != true) {
            editor.addEditorMouseListener(InlineCommentClickHandler(project))
            editor.putUserData(CLICK_HANDLER_INSTALLED, true)
        }

        if (editor.getUserData(HOVER_HANDLER_INSTALLED) != true) {
            editor.addEditorMouseMotionListener(AddCommentGutterHoverHandler(project))
            editor.putUserData(HOVER_HANDLER_INSTALLED, true)
        }

        if (editor.getUserData(BUTTON_HOVER_HANDLER_INSTALLED) != true) {
            editor.addEditorMouseMotionListener(InlineCommentHoverHandler())
            editor.putUserData(BUTTON_HOVER_HANDLER_INSTALLED, true)
        }
    }
}
