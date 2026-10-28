package com.fde.taskplugin.utils

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.provider.Settings
import android.util.Log
import android.app.ActivityOptions;

/**
 * 打开 / 清空 DocumentsUI 的回收站页面。
 *
 * 首选 DocumentsUI 新增的 `com.android.documentsui.OPEN_TRASH`（以及
 * `com.android.documentsui.EMPTY_TRASH`）extra，见
 * packages_apps_documentsui files/ActionHandler#launchToTrash。
 *
 * 兼容未打补丁的 DocumentsUI：额外尝试按 DocumentsUI 自身的做法构造
 * EXTRA_STACK（DocumentStack + trash RootInfo），等价于点击侧边栏的"回收站"。
 */
object DocumentsUiHelper {

    private const val TAG = "DocumentsUiHelper"
    private const val DOCSUI_PACKAGE = "com.android.documentsui"
    private const val FILES_ACTIVITY = "com.android.documentsui.files.FilesActivity"
    private const val EXTRA_OPEN_TRASH = "com.android.documentsui.OPEN_TRASH"
    private const val EXTRA_EMPTY_TRASH = "com.android.documentsui.EMPTY_TRASH"
    private const val EXTRA_STACK = "com.android.documentsui.STACK"
    private const val TRASH_ROOT_ID = "trash_root"
    private const val TRASH_STRING = "root_trash"

    // 与 packages_apps_documentsui base/Shared#SETTINGS_TRASH_PAGE_OPEN 对应：
    // DocumentsUI 在进入/离开回收站页面时上报该值，用来区分"回收站已打开"还是"没打开"。
    private const val KEY_TRASH_PAGE_OPEN = "documentsui_trash_page_open"
    private const val MAX_RUNNING_TASKS = 20

    // android.provider.DocumentsContract.Root
    private const val FLAG_LOCAL_ONLY = 1
    private const val FLAG_SUPPORTS_IS_CHILD = 1 shl 4

    fun getTrashLabel(context: Context): String {
        return try {
            val docsCtx = context.createPackageContext(DOCSUI_PACKAGE, 0)
            val id = docsCtx.resources.getIdentifier(TRASH_STRING, "string", DOCSUI_PACKAGE)
            if (id != 0) docsCtx.getString(id) else "Trash"
        } catch (t: Throwable) {
            "Trash"
        }
    }

    /**
     * 打开回收站页面。
     *
     * - 回收站已经打开（DocumentsUI 侧会上报 [KEY_TRASH_PAGE_OPEN]）→ 只把现有窗口
     *   放到顶层显示，不再新开一个 DocumentsUI 窗口；
     * - 否则按原来的方式新开回收站页面。
     */
    fun openTrash(context: Context): Boolean {
        if (bringOpenTrashToFront(context)) {
            Log.d(TAG, "trash page already open, brought it to the front")
            return true
        }
        return launchTrash(context, empty = false)
    }

    /**
     * 回收站页面已经打开时把它带到前台。返回 true 表示已处理（包括它已经在顶层）；
     * 返回 false 表示回收站并未打开（或对应窗口已不存在），需要调用方新开。
     */
    private fun bringOpenTrashToFront(context: Context): Boolean {
        val trashOpen =
            Settings.System.getInt(context.contentResolver, KEY_TRASH_PAGE_OPEN, 0) == 1
        if (!trashOpen) {
            return false
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val target = am.getRunningTasks(MAX_RUNNING_TASKS)
            ?.firstOrNull { isTrashFilesTask(it) }
            ?: return false

        Log.d(TAG, "trash task found, taskId=${target.taskId} focused=${target.isFocused}")
        if (target.isFocused) {
            // 已经在顶层，无需重复动作。
            return true
        }
        // 桌面模式下优先走 Shell 恢复（含从 MinimizedDesk 反最小化），否则普通置顶。
        val moved = try {
            am.moveTaskToFront(target.taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "moveTaskToFront failed", t)
            false
        }
        val broughtFront = HostDesktopMode.showTask(target.taskId) || moved
        return broughtFront
    }

    private fun isTrashFilesTask(info: ActivityManager.RunningTaskInfo): Boolean {
        val top = info.topActivity ?: return false
        val base = info.baseActivity ?: return false
        return DOCSUI_PACKAGE == top.packageName
            && DOCSUI_PACKAGE == base.packageName
            && FILES_ACTIVITY == top.className
    }

    /** 打开回收站并触发清空流程（DocumentsUI 会弹确认框）。 */
    fun emptyTrash(context: Context): Boolean {
        val intent = Intent().apply {
                component = ComponentName(
                    "com.android.documentsui",
                    "com.android.documentsui.files.EmptyTrashActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

        val options = ActivityOptions.makeCustomAnimation(
            context,android.R.anim.fade_in, android.R.anim.fade_out
        )
        context.startActivity(intent, options.toBundle())
        return true;
    }

    private fun launchTrash(context: Context, empty: Boolean): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.component = ComponentName(DOCSUI_PACKAGE, FILES_ACTIVITY)
            intent.putExtra(EXTRA_OPEN_TRASH, true)
            if (empty) {
                intent.putExtra(EXTRA_EMPTY_TRASH, true)
            }
            // 兼容未打补丁的 DocumentsUI
            buildTrashStack(context)?.let { intent.putExtra(EXTRA_STACK, it) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            
            Log.d(TAG, "launchTrash success empty=$empty")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "launchTrash failed empty=$empty", t)
            false
        }
    }

    private fun buildTrashStack(context: Context): Parcelable? {
        return try {
            val docsCtx = context.createPackageContext(DOCSUI_PACKAGE, 0)
            val classLoader = docsCtx.classLoader
            val rootInfoClass = classLoader.loadClass("com.android.documentsui.base.RootInfo")
            val userIdClass = classLoader.loadClass("com.android.documentsui.base.UserId")
            val documentInfoClass = classLoader.loadClass("com.android.documentsui.base.DocumentInfo")
            val stackClass = classLoader.loadClass("com.android.documentsui.base.DocumentStack")

            val root = rootInfoClass.getConstructor().newInstance()
            rootInfoClass.getField("authority").set(root, null)
            rootInfoClass.getField("rootId").set(root, TRASH_ROOT_ID)
            rootInfoClass.getField("title").set(root, getTrashLabel(context))
            rootInfoClass.getField("flags").set(root, FLAG_LOCAL_ONLY or FLAG_SUPPORTS_IS_CHILD)
            rootInfoClass.getField("userId").set(root, userIdClass.getField("CURRENT_USER").get(null))

            val emptyDocs = java.lang.reflect.Array.newInstance(documentInfoClass, 0)
            stackClass.getConstructor(rootInfoClass, emptyDocs.javaClass)
                .newInstance(root, emptyDocs) as Parcelable
        } catch (t: Throwable) {
            Log.w(TAG, "buildTrashStack failed", t)
            null
        }
    }
}
