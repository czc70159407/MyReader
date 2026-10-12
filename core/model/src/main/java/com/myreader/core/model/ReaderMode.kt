package com.myreader.core.model

/**
 * 阅读模式：水平分页（v1 默认）/ 垂直连续滚动（v1.1）。
 * M7 起由 SettingsPrefs 以 ordinal 持久化为「默认阅读模式」偏好——枚举不可重排/中间插入（仅尾部追加）。
 * 定义在 core:model（而非 core:document）：偏好层（core:data）需暴露 Flow&lt;ReaderMode&gt;，
 * 但 core:data 不依赖 core:document（其 api 了 compose-ui）；与 DocSort 同处惯例一致。
 */
enum class ReaderMode { HORIZONTAL, VERTICAL }
