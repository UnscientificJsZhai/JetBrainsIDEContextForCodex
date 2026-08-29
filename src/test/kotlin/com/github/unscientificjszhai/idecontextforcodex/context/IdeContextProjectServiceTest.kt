package com.github.unscientificjszhai.idecontextforcodex.context

import com.github.unscientificjszhai.idecontextforcodex.ipc.protocol.IpcConstants
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class IdeContextProjectServiceTest : BasePlatformTestCase() {
    private val temporaryDirectories = mutableListOf<Path>()
    private val additionalProjects = mutableListOf<Project>()

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
                IdeRange(IdePosition(1, 0), IdePosition(1, 2)),
                IdeRange(IdePosition(0, 0), IdePosition(0, 2)),
            ),
            activeFile.selections,
        )
        assertEquals(activeFile.selection, activeFile.selections.first())
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
        assertEquals(2, externalActiveContext.openTabs.size)
        assertEquals(
            setOf("First.kt", "Second.kt"),
            externalActiveContext.openTabs.map(IdeFileDescriptor::path).toSet(),
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

    fun testFocusedTextEditorWinsAndSelectedTextEditorIsFallback() {
        val workspace = createWorkspace()
        val firstEditor = openLocalTextFile(workspace, "First.kt", "first")
        val firstFile = FileDocumentManager.getInstance().getFile(firstEditor.document)
        assertNotNull(firstFile)
        /*
         * Headless TestEditorManager 不建立真实 UI focus，focusedEditor 始终为 null。这里从同一
         * manager 取得真实 TextEditor，并把它放到 focusedEditor API 参数位验证优先级。
         */
        val firstTextEditor = FileEditorManager.getInstance(project).getSelectedEditor(firstFile!!)
        assertTrue(firstTextEditor is TextEditor)

        val selectedEditor = openLocalTextFile(workspace, "Second.kt", "second")

        assertSame(
            firstEditor,
            IdeContextProjectService.selectEditor(firstTextEditor, selectedEditor),
        )
        assertSame(
            selectedEditor,
            IdeContextProjectService.selectEditor(null, selectedEditor),
        )
    }

    fun testNonTextFocusedEditorFallsBackToRealSelectedTextEditor() {
        val workspace = createWorkspace()
        val selectedEditor = openLocalTextFile(workspace, "Selected.kt", "selected")
        /*
         * Headless TestEditorManager 会把二进制文件交给文本 provider，无法稳定创建图片等非文本
         * editor。这里在 FileEditor/TextEditor 的公开 API 边界提供一个非 TextEditor 实例，
         * 并用真实 platform Editor 验证与生产代码完全相同的 fallback 分支。
         */
        val nonTextEditor = Proxy.newProxyInstance(
            FileEditor::class.java.classLoader,
            arrayOf(FileEditor::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as FileEditor

        assertSame(
            selectedEditor,
            IdeContextProjectService.selectEditor(nonTextEditor, selectedEditor),
        )
        assertNull(IdeContextProjectService.selectEditor(nonTextEditor, null))
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

    fun testCancelledServiceScopeRejectsSnapshot() {
        val workspace = createWorkspace()
        val serviceScope = CoroutineScope(SupervisorJob())
        val service = IdeContextProjectService(project, serviceScope)
        serviceScope.cancel()

        var cancellation: CancellationException? = null
        try {
            runBlocking {
                service.snapshot(workspace)
            }
        } catch (exception: CancellationException) {
            cancellation = exception
        }

        assertNotNull(cancellation)
    }

    fun testDisposedProjectReturnsEmptyContextWhenCallerScopeIsStillActive() {
        val workspace = createWorkspace()
        val disposedProject = createAdditionalProject(createWorkspace())
        val serviceScope = CoroutineScope(SupervisorJob())
        val service = IdeContextProjectService(disposedProject, serviceScope)
        closeAdditionalProject(disposedProject)
        assertTrue(disposedProject.isDisposed)

        val context = runBlocking {
            service.snapshot(workspace)
        }

        assertNull(context.activeFile)
        assertEmpty(context.openTabs)
        serviceScope.cancel()
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

    fun testRealLargeSelectionIsLimitedBeforeBuildingContext() {
        val workspace = createWorkspace()
        val editor = openLocalTextFile(workspace, "Large.kt", "placeholder")
        val content = "a".repeat(IpcConstants.MAX_SELECTION_CHARS - 1) + "😀tail"
        runWriteAction {
            editor.document.setText(content)
            editor.caretModel.primaryCaret.setSelection(0, content.length)
        }

        val activeFile = snapshot(workspace).activeFile

        assertNotNull(activeFile)
        assertEquals(IpcConstants.MAX_SELECTION_CHARS - 1, activeFile!!.activeSelectionContent?.length)
        assertEquals(
            IdePosition(0, content.length),
            activeFile.selection.end,
        )
    }

    fun testPublicResolverMatchesRealOpenProjectContentRoot() {
        val contentRoot = createWorkspace()
        val virtualContentRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(contentRoot)
        assertNotNull(virtualContentRoot)
        ModuleRootModificationUtil.addContentRoot(module, virtualContentRoot!!)
        assertTrue(
            ProjectRootManager.getInstance(project).contentRoots.any { it == virtualContentRoot },
        )

        val resolved = ProjectResolver().resolve(contentRoot)

        assertNotNull(resolved)
        assertSame(project, resolved!!.project)
        assertEquals(contentRoot.toRealPath(), resolved.workspaceRoot)
    }

    fun testPublicResolverRejectsAmbiguousRealOpenProjects() {
        val sharedWorkspace = createWorkspace()
        val virtualSharedWorkspace = LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(sharedWorkspace)
        assertNotNull(virtualSharedWorkspace)
        ModuleRootModificationUtil.addContentRoot(module, virtualSharedWorkspace!!)
        val secondProject = createAdditionalProject(sharedWorkspace)
        assertTrue(ProjectManager.getInstance().openProjects.any { it === project })
        assertTrue(ProjectManager.getInstance().openProjects.any { it === secondProject })

        assertNull(ProjectResolver().resolve(sharedWorkspace))
    }

    fun testPublicResolverCanonicalizesSymlinkWorkspaceRoot() {
        val realWorkspace = createWorkspace()
        val virtualWorkspace = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(realWorkspace)
        assertNotNull(virtualWorkspace)
        ModuleRootModificationUtil.addContentRoot(module, virtualWorkspace!!)
        val linksDirectory = createWorkspace()
        val workspaceLink = linksDirectory.resolve("linked-workspace")
        try {
            Files.createSymbolicLink(workspaceLink, realWorkspace)
        } catch (exception: UnsupportedOperationException) {
            org.junit.Assume.assumeNoException(exception)
        } catch (exception: java.io.IOException) {
            org.junit.Assume.assumeNoException(exception)
        } catch (exception: SecurityException) {
            org.junit.Assume.assumeNoException(exception)
        }

        val resolved = ProjectResolver().resolve(workspaceLink)

        assertNotNull(resolved)
        assertSame(project, resolved!!.project)
        assertEquals(realWorkspace.toRealPath(), resolved.workspaceRoot)
    }

    fun testSnapshotMatchesStaticSingleSelectionFixture() {
        val workspace = createWorkspace()
        val editor = openLocalTextFile(workspace, "src/Sample.kt", "示例")
        editor.caretModel.primaryCaret.setSelection(0, 2)

        val context = snapshot(workspace)
        val fixture = javaClass.classLoader
            .getResourceAsStream("protocol/ide-context/success-single-selection.json")
            ?.bufferedReader()
            ?.use { JsonParser.parseReader(it).asJsonObject }
        assertNotNull(fixture)
        val expected = fixture!!
            .getAsJsonObject("result")
            .getAsJsonObject("ideContext")

        assertEquals(expected, Gson().toJsonTree(context))
    }

    fun testSnapshotMatchesStaticMultiSelectionFixture() {
        val workspace = createWorkspace()
        openLocalTextFile(workspace, "src/Sample.kt", "示例")
        val editor = openLocalTextFile(workspace, "src/Unicode.kt", "x\nA😀\nend")
        runWriteAction {
            editor.caretModel.primaryCaret.moveToOffset(6)
            val primary = editor.caretModel.addCaret(editor.offsetToVisualPosition(3))
            assertNotNull(primary)
            primary!!.setSelection(3, 5)
        }

        val context = snapshot(workspace)
        val fixture = javaClass.classLoader
            .getResourceAsStream("protocol/ide-context/success-multi-selection.json")
            ?.bufferedReader()
            ?.use { JsonParser.parseReader(it).asJsonObject }
        assertNotNull(fixture)
        val expected = fixture!!
            .getAsJsonObject("result")
            .getAsJsonObject("ideContext")

        assertEquals(expected, Gson().toJsonTree(context))
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
            additionalProjects.toList().forEach(::closeAdditionalProject)
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

    private fun createAdditionalProject(projectPath: Path): Project {
        val projectManager = ProjectManagerEx.getInstanceEx()
        val openTask = OpenProjectTask.build()
            .asNewProject()
            .withForceOpenInNewFrame(true)
        val additionalProject = projectManager.newProject(
            projectPath,
            openTask,
        )
        assertNotNull(additionalProject)
        val openedProject = projectManager.openProject(
            projectPath,
            openTask.withProject(additionalProject!!),
        )
        assertNotNull(openedProject)
        assertSame(additionalProject, openedProject)
        additionalProjects.add(openedProject!!)
        return openedProject
    }

    private fun closeAdditionalProject(additionalProject: Project) {
        additionalProjects.remove(additionalProject)
        if (!additionalProject.isDisposed) {
            PlatformTestUtil.forceCloseProjectWithoutSaving(additionalProject)
        }
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
