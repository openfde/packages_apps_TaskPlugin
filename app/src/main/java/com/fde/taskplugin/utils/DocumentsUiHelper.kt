package com.fde.taskplugin.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.provider.Settings
import android.util.Log
import java.io.File
import android.app.ActivityOptions;
/**
 * 打开 DocumentsUI 的回收站页面，以及直接永久清空回收站。
 *
 * 打开回收站页首选 DocumentsUI 新增的 `com.android.documentsui.OPEN_TRASH` extra，
 * 见 packages_apps_documentsui files/ActionHandler#launchToTrash。
 *
 * 清空回收站不再走 DocumentsUI（不弹它自己的确认框），而是由本应用内的确认弹窗
 * 确认后直接调 [performEmptyTrash] 清空，逻辑与 DocumentsUI 的 EmptyTrashActivity 一致。
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

    /** 与 com.android.documentsui.provider.FileUtils.PATH_TRASH 保持一致。 */
    private const val PATH_TRASH = "/mnt/sdcard/.trash-storage/"

    /** 与 com.android.documentsui.provider.FileUtils 中 provider 刷新广播保持一致。 */
    private const val ACTION_FDE_PROVIDER = "android.intent.action.FDE_PROVIDER"

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
        return launchTrash(context)
    }

    fun emptyTrash(context: Context): Boolean {
        return launchTrash(context, empty = true)
    }



    /**
     * 直接永久清空回收站（本应用内"清空回收站"确认弹窗确认后调用），
     * 不再打开 DocumentsUI 走它的 EmptyTrashActivity。逻辑与 DocumentsUI 的
     * EmptyTrashActivity.emptyTrashAndFinish 保持一致：
     *
     *  1. 物理删除 /mnt/sdcard/.trash-storage/ 下所有文件/文件夹；
     *  2. 重置 trash_storage，dock 回收站图标通过 ContentObserver 自动刷新为空态；
     *  3. 广播 FDE_PROVIDER，通知相关 provider 刷新文件列表。
     */
    fun performEmptyTrash(context: Context) {
        // 清空可能耗时较长（大回收站），放到子线程执行，避免阻塞 UI 线程。
        Thread {
            try {
                val trashDir = File(PATH_TRASH)
                if (trashDir.exists() && trashDir.isDirectory) {
                    trashDir.listFiles()?.forEach { deleteRecursive(it) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "clear trash folder failed", t)
            }
            try {
                Settings.System.putInt(context.contentResolver, "trash_storage", 0)
            } catch (t: Throwable) {
                Log.w(TAG, "reset trash_storage failed", t)
            }
            try {
                context.sendBroadcast(Intent(ACTION_FDE_PROVIDER))
            } catch (t: Throwable) {
                Log.w(TAG, "notify FDE_PROVIDER failed", t)
            }
            Log.d(TAG, "performEmptyTrash done")
        }.start()
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        file.delete()
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
            val options = ActivityOptions.makeCustomAnimation( context,android.R.anim.fade_in, android.R.anim.fade_out)
            context.startActivity(intent,options.toBundle())
            Log.d(TAG, "launchTrash success")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "launchTrash failed", t)
            false
        }
    }

    private fun launchTrash(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.component = ComponentName(DOCSUI_PACKAGE, FILES_ACTIVITY)
            intent.putExtra(EXTRA_OPEN_TRASH, true)
            // 兼容未打补丁的 DocumentsUI
            buildTrashStack(context)?.let { intent.putExtra(EXTRA_STACK, it) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeCustomAnimation( context,android.R.anim.fade_in, android.R.anim.fade_out)
            context.startActivity(intent,options.toBundle())
            Log.d(TAG, "launchTrash success")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "launchTrash failed", t)
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
