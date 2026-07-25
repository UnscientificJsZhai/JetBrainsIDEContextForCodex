package com.github.unscientificjszhai.codexjetbrainsideplugin.context

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DocumentPositionMapperTest : BasePlatformTestCase() {
    fun testOffsetClampAndEmptyDocument() {
        val empty = EditorFactory.getInstance().createDocument("")
        assertEquals(IdePosition(0, 0), DocumentPositionMapper.toPosition(empty, -1))
        assertEquals(IdePosition(0, 0), DocumentPositionMapper.toPosition(empty, 1))

        val document = EditorFactory.getInstance().createDocument("abc")
        assertEquals(IdePosition(0, 0), DocumentPositionMapper.toPosition(document, -10))
        assertEquals(IdePosition(0, 3), DocumentPositionMapper.toPosition(document, 100))
    }

    fun testTabUsesUtf16OffsetInsteadOfVisualColumn() {
        val document = EditorFactory.getInstance().createDocument("\tX")
        assertEquals(IdePosition(0, 1), DocumentPositionMapper.toPosition(document, 1))
    }

    fun testCrLfAndEof() {
        // IDE 文档会把磁盘上的 CRLF 规范化成 LF。
        val document = EditorFactory.getInstance().createDocument(
            StringUtil.convertLineSeparators("ab\r\ncd"),
        )
        assertEquals(IdePosition(0, 2), DocumentPositionMapper.toPosition(document, 2))
        assertEquals(IdePosition(1, 0), DocumentPositionMapper.toPosition(document, 3))
        assertEquals(IdePosition(1, 2), DocumentPositionMapper.toPosition(document, 5))
    }

    fun testEmojiUsesTwoUtf16CodeUnits() {
        val document = EditorFactory.getInstance().createDocument("A😀B")
        assertEquals(IdePosition(0, 1), DocumentPositionMapper.toPosition(document, 1))
        assertEquals(IdePosition(0, 3), DocumentPositionMapper.toPosition(document, 3))
    }

    fun testCombiningCharactersAndRtlUseRawUtf16Offsets() {
        val document = EditorFactory.getInstance().createDocument("e\u0301\nאב")
        assertEquals(IdePosition(0, 2), DocumentPositionMapper.toPosition(document, 2))
        assertEquals(IdePosition(1, 2), DocumentPositionMapper.toPosition(document, 5))
    }
}
