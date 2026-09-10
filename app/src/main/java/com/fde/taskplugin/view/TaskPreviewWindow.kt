package com.fde.taskplugin.view

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.fde.taskplugin.R
import com.fde.taskplugin.TaskInfo
import com.fde.taskplugin.utils.TaskThumbnailLoader

/**
 * Windows 风格的任务预览窗口：hover dock 图标时在图标上方弹出任务缩略图。
 * 点击缩略图切换到任务，点击关闭按钮结束任务。
 */
class TaskPreviewWindow(
    context: Context,
    width: Int,
    height: Int,
    gravity: Int,
    layoutResId: Int,
    typeParam: Int
) : AbsTopPopWindow(context, width, height, gravity, layoutResId, typeParam), View.OnClickListener {

    companion object {
        private const val TAG = "TaskPreviewWindow"
        private const val FADE_IN_DURATION = 120L
        private const val FADE_OUT_DURATION = 90L
    }

    var taskInfo: TaskInfo? = null
    var onPreviewClickListener: ((TaskInfo) -> Unit)? = null
    var onCloseClickListener: ((TaskInfo) -> Unit)? = null
    var onHoverChangeListener: ((Boolean) -> Unit)? = null

    private var iconIv: ImageView? = null
    private var nameTv: TextView? = null
    private var closeIv: ImageView? = null
    private var thumbIv: ImageView? = null
    private var loadingPb: ProgressBar? = null
    private var placeholderIv: ImageView? = null
    private var loadToken = 0
    private var liveActive = false

    // 不抢焦点，避免 hover 预览把当前应用焦点顶掉
    override fun extraWindowFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    override fun showPopupWindow() {
        Log.d(TAG, "showPopupWindow task=${taskInfo?.program} id=${taskInfo?.id}")
        super.showPopupWindow()
        Log.d(TAG, "showPopupWindow content=${mContentView} attached=${mContentView?.isAttachedToWindow}")
        initViews()
        loadThumbnail()
        startLivePreview()
    }

    private fun initViews() {
        val content = mContentView ?: return
        iconIv = content.findViewById(R.id.task_preview_icon)
        nameTv = content.findViewById(R.id.task_preview_name)
        closeIv = content.findViewById(R.id.task_preview_close)
        thumbIv = content.findViewById(R.id.task_preview_thumb)
        loadingPb = content.findViewById(R.id.task_preview_loading)
        placeholderIv = content.findViewById(R.id.task_preview_placeholder)

        val info = taskInfo ?: return
        iconIv?.setImageDrawable(info.icon)
        placeholderIv?.setImageDrawable(info.icon)
        nameTv?.text = info.program

        closeIv?.setOnClickListener(this)
        thumbIv?.setOnClickListener(this)

        val hoverListener = View.OnHoverListener { _, event ->
            Log.d(TAG, "preview hover action=${event.action}")
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER -> onHoverChangeListener?.invoke(true)
                MotionEvent.ACTION_HOVER_EXIT -> onHoverChangeListener?.invoke(false)
            }
            false
        }
        // Clickable children consume hover events, so attach the listener to them as well,
        // otherwise moving the mouse from the dock into the preview would dismiss it.
        content.setOnHoverListener(hoverListener)
        closeIv?.setOnHoverListener(hoverListener)
        thumbIv?.setOnHoverListener(hoverListener)
        content.findViewById<View>(R.id.task_preview_thumb_container)?.setOnHoverListener(hoverListener)

        content.alpha = 0f
        content.animate().alpha(1f).setDuration(FADE_IN_DURATION).start()
    }

    private fun loadThumbnail() {
        val info = taskInfo ?: return
        val token = ++loadToken
        Log.d(TAG, "loadThumbnail task=${info.program} id=${info.id}")
        TaskThumbnailLoader.load(getContext(), info.id) { bitmap ->
            Log.d(TAG, "loadThumbnail callback id=${info.id} bitmap=${bitmap?.width}x${bitmap?.height}")
            if (token != loadToken || !isShowing()) {
                return@load
            }
            if (bitmap != null) {
                showThumbnail(bitmap)
            } else {
                loadingPb?.visibility = View.GONE
                placeholderIv?.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 对可见任务（TOP）启动低频快照轮询，拿到接近实时的画面。
     * 后台任务系统不允许截取，轮询会自动结束，保留静态首帧。
     */
    private fun startLivePreview() {
        val info = taskInfo ?: return
        if (!info.isTop()) {
            Log.d(TAG, "startLivePreview skip, state=${info.getState()} not TOP")
            return
        }
        liveActive = true
        Log.d(TAG, "startLivePreview id=${info.id} app=${info.program}")
        TaskThumbnailLoader.startLive(getContext(), info.id) { frame ->
            if (!liveActive || !isShowing()) {
                return@startLive
            }
            showThumbnail(frame.bitmap)
        }
    }

    private fun stopLivePreview() {
        if (!liveActive) {
            return
        }
        liveActive = false
        TaskThumbnailLoader.stopLive()
        Log.d(TAG, "stopLivePreview")
    }

    private fun showThumbnail(bitmap: Bitmap) {
        val thumb = thumbIv ?: return
        val content = mContentView ?: return
        val resources = getContext().resources
        val maxWidth = resources.getDimensionPixelSize(R.dimen.task_preview_width) -
                resources.getDimensionPixelSize(R.dimen.task_preview_padding) * 2
        val maxHeight = (resources.displayMetrics.heightPixels * 0.5f).toInt()

        var w = bitmap.width
        var h = bitmap.height
        if (w > maxWidth) {
            h = (h * maxWidth.toFloat() / w).toInt().coerceAtLeast(1)
            w = maxWidth
        }
        if (h > maxHeight) {
            w = (w * maxHeight.toFloat() / h).toInt().coerceAtLeast(1)
            h = maxHeight
        }

        thumb.setImageBitmap(bitmap)
        val params = thumb.layoutParams
        params.width = w
        params.height = h
        thumb.layoutParams = params
        thumb.visibility = View.VISIBLE
        loadingPb?.visibility = View.GONE
        placeholderIv?.visibility = View.GONE
        Log.d(TAG, "thumbnail loaded ${w}x$h for ${taskInfo?.program}")
    }

    override fun onClick(v: View?) {
        val info = taskInfo ?: return
        when (v?.id) {
            R.id.task_preview_close -> onCloseClickListener?.invoke(info)
            R.id.task_preview_thumb -> onPreviewClickListener?.invoke(info)
        }
    }

    fun hide(animated: Boolean) {
        stopLivePreview()
        if (!animated) {
            dismissImmediately()
            return
        }
        val content = mContentView ?: run {
            dismissImmediately()
            return
        }
        content.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_DURATION)
            .withEndAction { dismissImmediately() }
            .start()
    }

    override fun dismissImmediately() {
        stopLivePreview()
        super.dismissImmediately()
    }
}
