package com.fde.taskplugin.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.util.Log

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

    /** 打开回收站页面。 */
    fun openTrash(context: Context): Boolean {
        return launchTrash(context, empty = false)
    }

    /** 打开回收站并触发清空流程（DocumentsUI 会弹确认框）。 */
    fun emptyTrash(context: Context): Boolean {
        return launchTrash(context, empty = true)
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
