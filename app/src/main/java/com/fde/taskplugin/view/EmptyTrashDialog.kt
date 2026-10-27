package com.fde.taskplugin.view

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.fde.taskplugin.R
import com.fde.taskplugin.utils.DocumentsUiHelper

/**
 * 本应用内"清空回收站"确认弹窗。
 *
 * 清空回收站不再打开 DocumentsUI（避免它自己的 EmptyTrashActivity 确认框），
 * 而是由 TaskPlugin 以系统弹窗形式直接确认：
 *   - 取消：什么都不做直接关闭；
 *   - 永久删除：直接调 [DocumentsUiHelper.performEmptyTrash] 清空回收站。
 */
class EmptyTrashDialog(
    context: Context,
) : AbsTopPopWindow(
    context,
    ViewGroup.LayoutParams.WRAP_CONTENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
    Gravity.CENTER,
    R.layout.layout_empty_trash_dialog,
    WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG
) {

    companion object {
        private const val TAG = "EmptyTrashDialog"
    }

    /** 弹出确认弹窗（只在内部清理逻辑，不阻塞调用方）。 */
    fun showEmptyTrashDialog() {
        if (isShowing()) {
            return
        }
        showPopupWindow()
        bindViews()
        Log.d(TAG, "showEmptyTrashDialog shown")
    }

    private fun bindViews() {
        mContentView?.findViewById<TextView>(R.id.empty_trash_dialog_cancel)?.setOnClickListener {
            Log.d(TAG, "cancel clicked")
            dismiss()
        }
        mContentView?.findViewById<TextView>(R.id.empty_trash_dialog_delete)?.setOnClickListener {
            Log.d(TAG, "delete forever clicked")
            dismiss()
            DocumentsUiHelper.performEmptyTrash(getContext())
        }
        mContentView?.post {
            // 居中弹窗用 overview（中心锚点）做缩放淡入，与其它弹窗动画风格一致。
            runWindowAnim(WindowGravity.overview, true)
        }
    }
}
