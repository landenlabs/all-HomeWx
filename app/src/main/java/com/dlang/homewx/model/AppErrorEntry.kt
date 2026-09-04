package com.dlang.homewx.model

/** One entry in the rolling diagnostics log shown in Settings - [source] names which poller failed. */
data class AppErrorEntry(
    val timestampMillis: Long,
    val source: String,
    val message: String
)
