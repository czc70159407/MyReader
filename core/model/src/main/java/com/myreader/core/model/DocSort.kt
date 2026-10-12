package com.myreader.core.model

/**
 * 书架排序枚举。Room 无法参数化 ORDER BY，Repository 按此分发到具名查询。
 * 当前值由 DataStore 持久化（M6 接入），默认 TITLE。
 */
enum class DocSort {
    TITLE,
    LAST_READ,
    ADDED,
    SIZE,
}
