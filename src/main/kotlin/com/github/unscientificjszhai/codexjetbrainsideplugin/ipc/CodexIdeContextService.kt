package com.github.unscientificjszhai.codexjetbrainsideplugin.ipc

import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.router.IpcRouterCoordinator
import com.github.unscientificjszhai.codexjetbrainsideplugin.settings.CodexSettingsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * IDE Context IPC 的 application 级生命周期入口。
 */
@Service(Service.Level.APP)
class CodexIdeContextService(
    private val coroutineScope: CoroutineScope,
) {
    private val lifecycleLock = Any()

    @Volatile
    private var serviceJob: Job? = null

    @Volatile
    private var coordinator: IpcRouterCoordinator? = null

    fun start() {
        val settings = service<CodexSettingsState>()
        if (!settings.enabled) return
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            LOG.warn("当前版本尚未实现 Windows named pipe，IDE Context IPC 不会启动")
            return
        }

        synchronized(lifecycleLock) {
            if (serviceJob?.isActive == true) return

            val endpoints = IpcEndpointResolver().resolve(settings.codexHomeOverride)
            val newCoordinator = IpcRouterCoordinator(
                endpoints = endpoints,
                provider = IdeContextRequestHandler(),
                coroutineScope = coroutineScope,
            )
            coordinator = newCoordinator
            val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
                try {
                    newCoordinator.run()
                } catch (_: CancellationException) {
                    // application scope 关闭或 stop() 时正常退出。
                } catch (exception: Throwable) {
                    LOG.warn("Codex IDE Context IPC 已停止：${exception.javaClass.simpleName}")
                } finally {
                    newCoordinator.close()
                    synchronized(lifecycleLock) {
                        if (coordinator === newCoordinator) {
                            coordinator = null
                            serviceJob = null
                        }
                    }
                }
            }
            serviceJob = job
            job.start()
        }
    }

    suspend fun stop() {
        val job = synchronized(lifecycleLock) {
            coordinator?.close()
            coordinator = null
            serviceJob.also { serviceJob = null }
        }
        job?.cancelAndJoin()
    }

    companion object {
        private val LOG = Logger.getInstance(CodexIdeContextService::class.java)
    }
}
