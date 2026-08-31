package com.fde.taskplugin

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import com.android.systemui.plugins.TaskbarPlugin
import com.android.systemui.plugins.annotations.Requires

/**
 * A minimal custom taskbar plugin that replaces the default launcher taskbar content
 * with a plugin-provided layout.
 */
@Requires(target = TaskbarPlugin::class, version = TaskbarPlugin.VERSION)
class TaskbarOverlay : TaskbarPlugin {

    private val TAG: String? = "TaskbarOverlay"
    private var pluginContext: Context? = null

    override fun onCreate(hostContext: Context, pluginContext: Context) {
        this.pluginContext = pluginContext
    }

    override fun setup(taskbarRoot: ViewGroup) {
        val ctx = pluginContext ?: return
        LayoutInflater.from(ctx).inflate(R.layout.taskbar_plugin_layout, taskbarRoot, true)
        Log.d(TAG, "setup() called with: taskbarRoot = $taskbarRoot")
        Log.d(TAG, "setup() called with: taskbarRoot = ${taskbarRoot?.parent}")
        Log.d(TAG, "setup() called with: taskbarRoot = ${taskbarRoot?.parent?.parent}")

    }

    override fun onDestroy() {
        pluginContext = null
    }
}
