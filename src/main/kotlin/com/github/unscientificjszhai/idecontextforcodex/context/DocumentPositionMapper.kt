package com.github.unscientificjszhai.idecontextforcodex.context

import com.intellij.openapi.editor.Document

object DocumentPositionMapper {
    fun toPosition(document: Document, offset: Int): IdePosition {
        val safeOffset = offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(safeOffset)
        return IdePosition(
            line = line,
            character = safeOffset - document.getLineStartOffset(line),
        )
    }
}
