package com.github.unscientificjszhai.idecontextforcodex.context

import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.IpcConstants
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class IdeContextProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    suspend fun snapshot(workspaceRoot: Path): IdeContext {
        coroutineScope.coroutineContext.ensureActive()
        val canonicalWorkspaceRoot = withContext(Dispatchers.IO) {
            canonicalizeAbsolutePath(workspaceRoot)
        } ?: return EMPTY_CONTEXT

        val application = ApplicationManager.getApplication()
        val rawSnapshot = if (application.isDispatchThread) {
            captureRawSnapshot()
        } else {
            withContext(Dispatchers.EDT) {
                captureRawSnapshot()
            }
        }

        return withContext(Dispatchers.IO) {
            coroutineScope.coroutineContext.ensureActive()
            buildContext(rawSnapshot, canonicalWorkspaceRoot)
        }
    }

    private fun captureRawSnapshot(): RawSnapshot {
        if (project.isDisposed) return RawSnapshot(null, emptyList())

        val editorManager = FileEditorManager.getInstance(project)
        val editor = selectEditor(editorManager.focusedEditor, editorManager.selectedTextEditor)
        val activeEditor = editor?.let { currentEditor ->
            val file = FileDocumentManager.getInstance().getFile(currentEditor.document)
            val rawFile = file?.let(::captureRawFile)
            rawFile?.let {
                val primaryCaret = currentEditor.caretModel.primaryCaret
                RawActiveEditor(
                    file = it,
                    selection = primaryCaret.toRawRange(currentEditor.document),
                    selectedText = primaryCaret.captureSelectedText(currentEditor.document),
                    selections = buildList {
                        add(primaryCaret.toRawRange(currentEditor.document))
                        currentEditor.caretModel.allCarets.forEach { caret ->
                            if (caret !== primaryCaret) {
                                add(caret.toRawRange(currentEditor.document))
                            }
                        }
                    },
                )
            }
        }

        return RawSnapshot(
            activeEditor = activeEditor,
            openFiles = editorManager.openFiles.mapNotNull(::captureRawFile),
        )
    }

    private fun captureRawFile(file: com.intellij.openapi.vfs.VirtualFile): RawFile? {
        if (!file.isValid || !file.isInLocalFileSystem || file.isDirectory) return null
        val path = runCatching { file.toNioPath() }.getOrNull() ?: return null
        return RawFile(file.name, path)
    }

    private fun Caret.captureSelectedText(document: Document): String? {
        if (!hasSelection()) return null
        val start = selectionStart.coerceIn(0, document.textLength)
        val originalEnd = selectionEnd.coerceIn(start, document.textLength)
        var end = minOf(originalEnd, start + IpcConstants.MAX_SELECTION_CHARS)
        if (
            end < originalEnd &&
            end > start &&
            Character.isHighSurrogate(document.charsSequence[end - 1]) &&
            Character.isLowSurrogate(document.charsSequence[end])
        ) {
            end -= 1
        }
        return document.getText(TextRange(start, end))
    }

    private fun Caret.toRawRange(document: Document): IdeRange {
        val startOffset = if (hasSelection()) selectionStart else offset
        val endOffset = if (hasSelection()) selectionEnd else offset
        return IdeRange(
            start = DocumentPositionMapper.toPosition(document, startOffset),
            end = DocumentPositionMapper.toPosition(document, endOffset),
        )
    }

    companion object {
        private val EMPTY_CONTEXT = IdeContext(activeFile = null, openTabs = emptyList())

        internal fun selectEditor(
            focusedEditor: FileEditor?,
            selectedTextEditor: Editor?,
        ): Editor? = (focusedEditor as? TextEditor)?.editor ?: selectedTextEditor

        internal fun buildContext(rawSnapshot: RawSnapshot, workspaceRoot: Path): IdeContext {
            val activeFile = rawSnapshot.activeEditor?.let { activeEditor ->
                WorkspacePathMapper.toProtocolPath(activeEditor.file.path, workspaceRoot)?.let { protocolPath ->
                    IdeActiveFile(
                        label = activeEditor.file.label,
                        path = protocolPath,
                        selection = activeEditor.selection,
                        activeSelectionContent = truncateSelection(activeEditor.selectedText),
                        selections = activeEditor.selections,
                    )
                }
            }

            val activeDescriptor = activeFile?.let { IdeFileDescriptor(it.label, it.path) }
            val openTabs = sequence {
                activeDescriptor?.let { yield(it) }
                rawSnapshot.openFiles.forEach { rawFile ->
                    WorkspacePathMapper.toProtocolPath(rawFile.path, workspaceRoot)?.let { protocolPath ->
                        yield(IdeFileDescriptor(rawFile.label, protocolPath))
                    }
                }
            }
                .distinctBy(IdeFileDescriptor::path)
                .take(IpcConstants.MAX_OPEN_TABS)
                .toList()

            return IdeContext(activeFile = activeFile, openTabs = openTabs)
        }

        internal fun truncateSelection(content: String?): String? {
            if (content == null || content.length <= IpcConstants.MAX_SELECTION_CHARS) return content
            var end = IpcConstants.MAX_SELECTION_CHARS
            if (
                Character.isHighSurrogate(content[end - 1]) &&
                Character.isLowSurrogate(content[end])
            ) {
                end -= 1
            }
            return content.substring(0, end)
        }
    }
}

internal data class RawSnapshot(
    val activeEditor: RawActiveEditor?,
    val openFiles: List<RawFile>,
)

internal data class RawActiveEditor(
    val file: RawFile,
    val selection: IdeRange,
    val selectedText: String?,
    val selections: List<IdeRange>,
)

internal data class RawFile(
    val label: String,
    val path: Path,
)
