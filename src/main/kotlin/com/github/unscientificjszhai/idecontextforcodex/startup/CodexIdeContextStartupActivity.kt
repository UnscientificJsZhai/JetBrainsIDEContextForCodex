package com.github.unscientificjszhai.idecontextforcodex.startup

import com.github.unscientificjszhai.idecontextforcodex.ipc.CodexIdeContextService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class CodexIdeContextStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode || project.isDisposed) return
        service<CodexIdeContextService>().start()
    }
}
