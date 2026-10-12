package com.myreader.core.document

/** 文档大纲条目（children 嵌套表达层级；无大纲时引擎返回空表） */
data class OutlineEntry(
    val title: String,
    val pageIndex: Int,
    val children: List<OutlineEntry> = emptyList(),
)
