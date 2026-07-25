package com.github.unscientificjszhai.codexjetbrainsideplugin.context

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.file.Path

data class ResolvedProject(
    val project: Project,
    val workspaceRoot: Path,
)

internal data class ProjectPathCandidate<T>(
    val project: T,
    val root: Path,
    val isDisposed: Boolean = false,
)

class ProjectResolver {
    fun resolve(workspaceRoot: Path): ResolvedProject? {
        val canonicalWorkspaceRoot = canonicalizeAbsolutePath(workspaceRoot) ?: return null
        val candidates = ProjectManager.getInstance().openProjects
            .asSequence()
            .filterNot(Project::isDisposed)
            .flatMap { project ->
                projectRoots(project)
                    .mapNotNull(::canonicalizeAbsolutePath)
                    .distinct()
                    .map { root -> ProjectPathCandidate(project, root) }
            }
            .toList()

        val project = selectProject(canonicalWorkspaceRoot, candidates) ?: return null
        return ResolvedProject(project, canonicalWorkspaceRoot)
    }

    private fun projectRoots(project: Project): Sequence<Path> = sequence {
        project.basePath?.let { basePath ->
            runCatching { Path.of(basePath) }.getOrNull()?.let { yield(it) }
        }
        ProjectRootManager.getInstance(project).contentRoots.forEach { contentRoot ->
            runCatching { contentRoot.toNioPath() }.getOrNull()?.let { yield(it) }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ProjectResolver::class.java)

        internal fun <T> selectProject(
            workspaceRoot: Path,
            candidates: List<ProjectPathCandidate<T>>,
        ): T? {
            val eligibleCandidates = candidates.filterNot(ProjectPathCandidate<T>::isDisposed)
            val exact = eligibleCandidates.filter { it.root == workspaceRoot }
            val bestCandidates = if (exact.isNotEmpty()) {
                exact
            } else {
                val ancestors = eligibleCandidates.filter { workspaceRoot.startsWith(it.root) }
                val longest = ancestors.maxOfOrNull { it.root.nameCount } ?: return null
                ancestors.filter { it.root.nameCount == longest }
            }

            val projects = bestCandidates.map(ProjectPathCandidate<T>::project).distinct()
            if (projects.size != 1) {
                LOG.warn(
                    "工作区项目解析存在歧义：候选项目数=${projects.size}，匹配类型=" +
                        if (exact.isNotEmpty()) "exact" else "ancestor",
                )
                return null
            }
            return projects.single()
        }
    }
}

internal fun canonicalizeAbsolutePath(path: Path): Path? {
    if (!path.isAbsolute) return null
    return runCatching { path.toRealPath() }.getOrNull()
}
