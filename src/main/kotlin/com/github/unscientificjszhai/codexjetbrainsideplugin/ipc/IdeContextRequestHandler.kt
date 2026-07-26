package com.github.unscientificjszhai.codexjetbrainsideplugin.ipc

import com.github.unscientificjszhai.codexjetbrainsideplugin.context.IdeContextProjectService
import com.github.unscientificjszhai.codexjetbrainsideplugin.context.ProjectResolver
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.IpcConstants
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.IpcMessages
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.int
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.obj
import com.github.unscientificjszhai.codexjetbrainsideplugin.ipc.protocol.string
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.nio.file.InvalidPathException
import java.nio.file.Path

interface IdeContextProvider {
    suspend fun canHandle(request: JsonObject): Boolean

    suspend fun handle(request: JsonObject, clientId: String): JsonObject
}

/**
 * 把 IPC 请求路由到匹配工作区的 project service。
 */
class IdeContextRequestHandler(
    private val projectResolver: ProjectResolver = ProjectResolver(),
    private val gson: Gson = Gson(),
) : IdeContextProvider {
    override suspend fun canHandle(request: JsonObject): Boolean {
        if (request.int("version") != IpcConstants.PROTOCOL_VERSION) return false
        if (request.string("method") != IpcConstants.IDE_CONTEXT_METHOD) return false
        val workspaceRoot = parseWorkspaceRoot(request) ?: return false
        return projectResolver.resolve(workspaceRoot) != null
    }

    override suspend fun handle(request: JsonObject, clientId: String): JsonObject {
        val requestId = request.string("requestId") ?: return JsonObject()
        if (request.int("version") != IpcConstants.PROTOCOL_VERSION) {
            return IpcMessages.errorResponse(requestId, IpcConstants.ERROR_REQUEST_VERSION_MISMATCH)
        }
        if (request.string("method") != IpcConstants.IDE_CONTEXT_METHOD) {
            return IpcMessages.errorResponse(requestId, IpcConstants.ERROR_NO_HANDLER_FOR_REQUEST)
        }

        val workspaceRoot = parseWorkspaceRoot(request)
            ?: return IpcMessages.errorResponse(requestId, IpcConstants.ERROR_NO_CLIENT_FOUND)
        val resolved = projectResolver.resolve(workspaceRoot)
            ?: return IpcMessages.errorResponse(requestId, IpcConstants.ERROR_NO_CLIENT_FOUND)

        return try {
            val context = withTimeout(IpcConstants.REQUEST_TIMEOUT_MS) {
                resolved.project
                    .getService(IdeContextProjectService::class.java)
                    .snapshot(resolved.workspaceRoot)
            }
            IpcMessages.successResponse(
                requestId = requestId,
                method = IpcConstants.IDE_CONTEXT_METHOD,
                handledByClientId = clientId,
                result = JsonObject().apply {
                    add("ideContext", gson.toJsonTree(context))
                },
            )
        } catch (_: TimeoutCancellationException) {
            IpcMessages.errorResponse(requestId, IpcConstants.ERROR_REQUEST_TIMEOUT)
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            IpcMessages.errorResponse(requestId, IpcConstants.ERROR_NO_CLIENT_FOUND)
        } catch (_: Throwable) {
            IpcMessages.errorResponse(requestId, IpcConstants.ERROR_NO_CLIENT_FOUND)
        }
    }

    private fun parseWorkspaceRoot(request: JsonObject): Path? {
        val value = request.obj("params")?.string("workspaceRoot") ?: return null
        return try {
            Path.of(value).takeIf(Path::isAbsolute)?.normalize()
        } catch (_: InvalidPathException) {
            null
        }
    }
}
