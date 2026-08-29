package com.github.unscientificjszhai.idecontextforcodex.ipc.protocol

object IpcConstants {
    const val PROTOCOL_VERSION = 0
    const val IDE_CONTEXT_METHOD = "ide-context"
    const val MAX_FRAME_BYTES = 268_435_456
    const val REQUEST_TIMEOUT_MS = 4_000L
    const val MAX_SELECTION_CHARS = 40_000
    const val MAX_OPEN_TABS = 100

    const val ERROR_NO_CLIENT_FOUND = "no-client-found"
    const val ERROR_CLIENT_DISCONNECTED = "client-disconnected"
    const val ERROR_REQUEST_TIMEOUT = "request-timeout"
    const val ERROR_REQUEST_VERSION_MISMATCH = "request-version-mismatch"
    const val ERROR_NO_HANDLER_FOR_REQUEST = "no-handler-for-request"
}
