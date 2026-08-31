package com.fde.taskplugin.data

import com.fde.taskplugin.TaskInfo

data class DockContext(
    val action: String?, val type: Int,
    val name: String?, val app: AppData?, val taskInfo: TaskInfo ?,
    val enable: Boolean = true
)
