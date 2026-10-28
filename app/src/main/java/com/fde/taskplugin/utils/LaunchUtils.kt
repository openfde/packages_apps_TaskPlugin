package com.fde.taskplugin.utils

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Display
import com.fde.taskplugin.TaskInfo

/**
 * FDE multi-display launch policy.
 *
 * <p>All taskbars share one global window list. When the user clicks an app entry:
 * <ul>
 *     <li>if the app already has a task, activate that task in place; never move it to another
 *         display silently;</li>
 *     <li>if the app has no task, launch it on the display that owns the taskbar that was
 *         clicked.</li>
 * </ul>
 */
object LaunchUtils {
    private const val TAG = "LaunchUtils"
    private const val MAX_RUNNING_TASKS = 50

    @JvmStatic
    @JvmOverloads
    fun launch(context: Context, intent: Intent, taskInfo: TaskInfo? = null) {
        // X11 windows are placed by the X server and may not have an Android task. Keep the
        // existing launch path for them; only Android tasks get the display-aware policy.
        if (taskInfo?.platformType == TaskInfo.PLATFORM_TYPE_X11) {
            startOnTaskbarDisplay(context, intent)
            return
        }

        val packageName = taskInfo?.packageName
            ?: intent.component?.packageName
            ?: intent.`package`

        // Do not trust TaskInfo.runningTaskInfo here: the dock model can keep a stale snapshot
        // after the app's task has finished. Query the current task list instead, otherwise
        // moveTaskToFront() targets a dead task and the app never comes back.
        val existingTaskId = findRunningTaskId(context, packageName)
        if (existingTaskId != null && existingTaskId > 0) {
            if (moveTaskToFront(context, existingTaskId)) {
                return
            }
            Log.w(TAG, "moveTaskToFront failed taskId=$existingTaskId; re-checking")
            // If the task disappeared between the query and moveTaskToFront(), fall through and
            // start a fresh task on this taskbar's display. If it still exists, preserve the old
            // behavior instead of launching with a display option.
            val stillRunningTaskId = findRunningTaskId(context, packageName)
            if (stillRunningTaskId != null && stillRunningTaskId > 0) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (t: Throwable) {
                    Log.w(TAG, "startActivity fallback failed", t)
                }
                return
            }
        }

        startOnTaskbarDisplay(context, intent)
    }

    private fun moveTaskToFront(context: Context, taskId: Int): Boolean {
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.moveTaskToFront(
                taskId,
                ActivityManager.MOVE_TASK_NO_USER_ACTION,
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "moveTaskToFront failed taskId=$taskId", t)
            false
        }
    }

    private fun startOnTaskbarDisplay(context: Context, intent: Intent) {
        val displayId = try {
            context.display?.displayId ?: Display.DEFAULT_DISPLAY
        } catch (t: Throwable) {
            Display.DEFAULT_DISPLAY
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        try {
            context.startActivity(intent, options.toBundle())
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity on display $displayId failed", t)
            context.startActivity(intent)
        }
    }

    private fun findRunningTaskId(context: Context, packageName: String?): Int? {
        if (packageName.isNullOrEmpty()) {
            return null
        }
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            activityManager.getRunningTasks(MAX_RUNNING_TASKS)
                ?.firstOrNull { info ->
                    info.baseActivity?.packageName == packageName ||
                        info.topActivity?.packageName == packageName
                }
                ?.taskId
        } catch (t: Throwable) {
            Log.w(TAG, "getRunningTasks failed for $packageName", t)
            null
        }
    }
}
