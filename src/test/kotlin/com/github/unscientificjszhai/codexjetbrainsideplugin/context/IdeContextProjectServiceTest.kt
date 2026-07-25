package com.github.unscientificjszhai.codexjetbrainsideplugin.context

import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.IpcConstants
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class IdeContextProjectServiceTest : BasePlatformTestCase() {
    private val temporaryDirectories = mutableListOf<Path>()

    fun testNoEditorReturnsEmptyContext() {
        val workspace = createWorkspace()
        closeAllFiles()

        val context = snapshot(workspace)

        assertNull(context.activeFile)
        assertEmpty(context.openTabs)
    }

    fun testUnsavedSelectionAndMultipleCaretsAreCaptured() {
        val workspace = createWorkspace()
        val editor = openLocalTextFile(workspace, "src/Unicode.kt", "disk")
        runWriteAction {
            editor.document.setText("示例\n😀\nend")
            editor.caretModel.primaryCaret.setSelection(0, 2)
            val secondary = editor.caretModel.addCaret(editor.offsetToVisualPosition(3))
            assertNotNull(secondary)
            secondary!!.setSelection(3, 5)
        }

        val context = snapshot(workspace)

        assertNotNull(context.activeFile)
        val activeFile = context.activeFile!!
        assertEquals("src/Unicode.kt", activeFile.path)
        // IntelliJ 新增 caret 后会把新 caret 设为 primary。
        assertEquals("😀", activeFile.activeSelectionContent)
        assertEquals(
            IdeRange(IdePosition(1, 0), IdePosition(1, 2)),
            activeFile.selection,
        )
        assertEquals(
            listOf(
                IdeRange(IdePosition(0, 0), IdePosition(0, 2)),
                IdeRange(IdePosition(1, 0), IdePosition(1, 2)),
            ),
            activeFile.selections,
        )
    }

    fun testNoSelectionUsesPrimaryCaretZeroLengthRange() {
        val workspace = createWorkspace()
        val editor = openLocalTextFile(workspace, "Sample.kt", "abc")
        editor.caretModel.moveToOffset(2)

        val activeFile = snapshot(workspace).activeFile
        assertNotNull(activeFile)

        val expected = IdeRange(IdePosition(0, 2), IdePosition(0, 2))
        assertEquals(expected, activeFile!!.selection)
        assertEquals(listOf(expected), activeFile.selections)
        assertNull(activeFile.activeSelectionContent)
    }

    fun testTabsFilterExternalFilesAndPutActiveFileFirst() {
        val workspace = createWorkspace()
        val outsideWorkspace = createWorkspace()
        openLocalTextFile(workspace, "First.kt", "first")
        openLocalTextFile(workspace, "Second.kt", "second")
        openLocalTextFile(outsideWorkspace, "Outside.kt", "outside")

        val externalActiveContext = snapshot(workspace)
        assertNull(externalActiveContext.activeFile)
        assertEquals(
            listOf("First.kt", "Second.kt"),
            externalActiveContext.openTabs.map(IdeFileDescriptor::path),
        )

        openLocalTextFile(workspace, "First.kt", "first")
        val internalActiveContext = snapshot(workspace)
        assertEquals("First.kt", internalActiveContext.activeFile?.path)
        assertEquals("First.kt", internalActiveContext.openTabs.first().path)
        assertEquals(2, internalActiveContext.openTabs.size)
    }

    fun testNonTextEditorDoesNotProduceActiveFile() {
        val workspace = createWorkspace()
        val binaryPath = workspace.resolve("image.png")
        Files.write(binaryPath, MINIMAL_PNG)

        // 非文本 editor 不会进入 RawActiveEditor，转换阶段仅保留可安全映射的 tabs。
        val context = IdeContextProjectService.buildContext(
            RawSnapshot(
                activeEditor = null,
                openFiles = listOf(RawFile("image.png", binaryPath)),
            ),
            workspace,
        )

        assertNull(context.activeFile)
        assertEquals(listOf("image.png"), context.openTabs.map(IdeFileDescriptor::path))
    }

    fun testSnapshotCompletesInDumbMode() {
        val workspace = createWorkspace()
        openLocalTextFile(workspace, "DumbMode.kt", "content")

        val context = DumbModeTestUtils.computeInDumbModeSynchronously(project) {
            assertTrue(DumbService.getInstance(project).isDumb)
            snapshot(workspace)
        }

        assertEquals("DumbMode.kt", context.activeFile?.path)
    }

    fun testSelectionTruncationDoesNotSplitSurrogatePair() {
        val exact = "a".repeat(IpcConstants.MAX_SELECTION_CHARS)
        val ordinaryOverflow = exact + "b"
        val crossingPair = "a".repeat(IpcConstants.MAX_SELECTION_CHARS - 1) + "😀"
        val pairInsideBoundary = "a".repeat(IpcConstants.MAX_SELECTION_CHARS - 2) + "😀x"

        assertEquals(exact, IdeContextProjectService.truncateSelection(exact))
        assertEquals(exact, IdeContextProjectService.truncateSelection(ordinaryOverflow))
        assertEquals(
            IpcConstants.MAX_SELECTION_CHARS - 1,
            IdeContextProjectService.truncateSelection(crossingPair)?.length,
        )
        val insideResult = IdeContextProjectService.truncateSelection(pairInsideBoundary)
        assertNotNull(insideResult)
        assertEquals(IpcConstants.MAX_SELECTION_CHARS, insideResult!!.length)
        assertTrue(Character.isLowSurrogate(insideResult.last()))
    }

    fun testTabsAreDeduplicatedAndLimitedAfterFiltering() {
        val workspace = createWorkspace()
        val files = (0..IpcConstants.MAX_OPEN_TABS).map { index ->
            val path = workspace.resolve("tab-$index.kt")
            Files.writeString(path, index.toString())
            RawFile("tab-$index.kt", path)
        }
        val outside = createWorkspace().resolve("outside.kt")
        Files.writeString(outside, "outside")
        val activeRange = IdeRange(IdePosition(0, 0), IdePosition(0, 0))
        val active = RawActiveEditor(
            file = files.last(),
            selection = activeRange,
            selectedText = null,
            selections = listOf(activeRange),
        )

        val context = IdeContextProjectService.buildContext(
            RawSnapshot(
                activeEditor = active,
                openFiles = listOf(files.last(), RawFile("outside.kt", outside)) + files,
            ),
            workspace,
        )

        assertEquals(IpcConstants.MAX_OPEN_TABS, context.openTabs.size)
        assertEquals("tab-${IpcConstants.MAX_OPEN_TABS}.kt", context.openTabs.first().path)
        assertEquals(context.openTabs.size, context.openTabs.map(IdeFileDescriptor::path).distinct().size)
        assertFalse(context.openTabs.any { it.path == "outside.kt" })
    }

    override fun tearDown() {
        try {
            closeAllFiles()
            temporaryDirectories.forEach(::deleteTemporaryDirectory)
        } finally {
            super.tearDown()
        }
    }

    private fun snapshot(workspace: Path): IdeContext = runBlocking {
        project.getService(IdeContextProjectService::class.java).snapshot(workspace)
    }

    private fun createWorkspace(): Path {
        val workspace = Files.createTempDirectory("codex-context-test-")
        temporaryDirectories.add(workspace)
        VfsRootAccess.allowRootAccess(
            testRootDisposable,
            workspace.toString(),
            workspace.toRealPath().toString(),
        )
        return workspace
    }

    private fun openLocalTextFile(
        workspace: Path,
        relativePath: String,
        content: String,
    ): com.intellij.openapi.editor.Editor {
        val path = workspace.resolve(relativePath)
        Files.createDirectories(path.parent)
        if (!Files.exists(path)) {
            Files.writeString(path, content)
        }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        assertNotNull(file)
        val editor = FileEditorManager.getInstance(project).openTextEditor(
            OpenFileDescriptor(project, file!!),
            true,
        )
        assertNotNull(editor)
        return editor!!
    }

    private fun closeAllFiles() {
        val editorManager = FileEditorManager.getInstance(project)
        editorManager.openFiles.forEach(editorManager::closeFile)
    }

    private fun deleteTemporaryDirectory(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object {
        private val MINIMAL_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
