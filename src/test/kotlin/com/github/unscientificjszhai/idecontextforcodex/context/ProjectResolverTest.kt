package com.github.unscientificjszhai.idecontextforcodex.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class ProjectResolverTest {
    private val root = Path.of("").toAbsolutePath().root.resolve("workspace")

    @Test
    fun `精确 content root 优先于 ancestor`() {
        val result = ProjectResolver.selectProject(
            root.resolve("module"),
            listOf(
                ProjectPathCandidate("parent", root),
                ProjectPathCandidate("exact", root.resolve("module")),
            ),
        )

        assertEquals("exact", result)
    }

    @Test
    fun `ancestor 选择最长 root`() {
        val result = ProjectResolver.selectProject(
            root.resolve("module/src"),
            listOf(
                ProjectPathCandidate("parent", root),
                ProjectPathCandidate("nested", root.resolve("module")),
            ),
        )

        assertEquals("nested", result)
    }

    @Test
    fun `同项目多个同级 root 不构成歧义`() {
        val result = ProjectResolver.selectProject(
            root,
            listOf(
                ProjectPathCandidate("same-project", root),
                ProjectPathCandidate("same-project", root),
            ),
        )

        assertEquals("same-project", result)
    }

    @Test
    fun `不同项目同优先级返回 null`() {
        val result = ProjectResolver.selectProject(
            root,
            listOf(
                ProjectPathCandidate("first", root),
                ProjectPathCandidate("second", root),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `disposed 项目不参与选择`() {
        val result = ProjectResolver.selectProject(
            root,
            listOf(
                ProjectPathCandidate("disposed", root, isDisposed = true),
                ProjectPathCandidate("open", root.parent!!, isDisposed = false),
            ),
        )

        assertEquals("open", result)
    }

    @Test
    fun `无匹配时返回 null`() {
        assertNull(
            ProjectResolver.selectProject(
                root,
                listOf(ProjectPathCandidate("other", Path.of("/other"))),
            ),
        )
    }
}
