package com.github.unscientificjszhai.idecontextforcodex.context

data class IdePosition(
    val line: Int,
    val character: Int,
)

data class IdeRange(
    val start: IdePosition,
    val end: IdePosition,
)

data class IdeFileDescriptor(
    val label: String,
    val path: String,
)

data class IdeActiveFile(
    val label: String,
    val path: String,
    val selection: IdeRange,
    val activeSelectionContent: String?,
    val selections: List<IdeRange>,
)

data class IdeContext(
    val activeFile: IdeActiveFile?,
    val openTabs: List<IdeFileDescriptor>,
)
