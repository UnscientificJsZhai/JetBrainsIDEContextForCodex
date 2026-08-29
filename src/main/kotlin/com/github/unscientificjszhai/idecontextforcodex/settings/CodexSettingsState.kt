package com.github.unscientificjszhai.idecontextforcodex.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * IDEContextForCodex 的应用级设置。
 *
 * 这里只保存连接开关和 Codex 主目录覆盖值，不保存任何编辑器内容或工作区路径。
 */
@Service(Service.Level.APP)
@State(
    name = "CodexIdeContextSettings",
    storages = [Storage("codex-ide-context.xml")],
)
class CodexSettingsState : PersistentStateComponent<CodexSettingsState.Settings> {
    private var settings = Settings()

    override fun getState(): Settings = settings

    override fun loadState(state: Settings) {
        settings = state
    }

    var enabled: Boolean
        get() = settings.enabled
        set(value) {
            settings.enabled = value
        }

    var codexHomeOverride: String?
        get() = settings.codexHomeOverride.takeIf(String::isNotBlank)
        set(value) {
            settings.codexHomeOverride = value?.trim().orEmpty()
        }

    class Settings {
        var enabled: Boolean = true
        var codexHomeOverride: String = ""
    }
}
