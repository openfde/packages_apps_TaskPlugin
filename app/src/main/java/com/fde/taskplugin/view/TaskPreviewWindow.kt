package com.fde.taskplugin.view

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.fde.taskplugin.R
import com.fde.taskplugin.TaskInfo
import com.fde.taskplugin.utils.TaskThumbnailLoader

/**
 * Windows 风格的任务预览窗口：hover dock 图标时在图标上方弹出任务缩略图。
 * 同一个包的多个窗口会并排展示，点击缩略图切换到对应窗口。
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

        /** 预览窗口的预期总宽度（每张缩略图卡片等宽并排）。 */
        fun previewWidthPx(context: Context, count: Int): Int {
            val padding = context.resources.getDimensionPixelSize(R.dimen.task_preview_padding)
            val gap = gapPx(context)
            val cardWidth = cardWidthPx(context, count)
            return padding * 2 + count * cardWidth + (count - 1).coerceAtLeast(0) * gap
        }

        private fun cardWidthPx(context: Context, count: Int): Int {
            val padding = context.resources.getDimensionPixelSize(R.dimen.task_preview_padding)
            return if (count <= 1) {
                context.resources.getDimensionPixelSize(R.dimen.task_preview_width) - padding * 2
            } else {
                context.resources.getDimensionPixelSize(R.dimen.task_preview_item_width)
            }
        }

        private fun gapPx(context: Context): Int =
            context.resources.getDimensionPixelSize(R.dimen.task_preview_item_gap)
    }

    var onPreviewClickListener: ((TaskInfo) -> Unit)? = null
    var onCloseClickListener: ((TaskInfo) -> Unit)? = null
    var onHoverChangeListener: ((Boolean) -> Unit)? = null

    private val tasks: MutableList<TaskInfo> = ArrayList()
    private val holders: MutableList<ThumbHolder> = ArrayList()
    private var iconIv: ImageView? = null
    private var nameTv: TextView? = null
    private var closeIv: ImageView? = null
    private var thumbRow: LinearLayout? = null
    private var loadToken = 0
    private var liveActive = false
    private var lastHoverInside = false

    // 不抢焦点，避免 hover 预览把当前应用焦点顶掉
    override fun extraWindowFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    fun setTasks(list: List<TaskInfo>) {
        tasks.clear()
        tasks.addAll(list)
    }

    override fun showPopupWindow() {
        Log.d(
            TAG,
            "@${Integer.toHexString(System.identityHashCode(this))} showPopupWindow tasks=${tasks.size}" +
                    " tasks=${tasks.joinToString { "${it.program}@${it.id}" }}"
        )
        super.showPopupWindow()
        initViews()
        loadThumbnails()
        startLivePreview()
    }

    private fun initViews() {
        val content = mContentView ?: return
        iconIv = content.findViewById(R.id.task_preview_icon)
        nameTv = content.findViewById(R.id.task_preview_name)
        closeIv = content.findViewById(R.id.task_preview_close)
        thumbRow = content.findViewById(R.id.task_preview_thumb_row)

        val first = tasks.firstOrNull()
        iconIv?.setImageDrawable(first?.icon)
        nameTv?.text = first?.program
        val multiple = tasks.size > 1
        closeIv?.visibility = if (multiple) View.GONE else View.VISIBLE
        closeIv?.setOnClickListener(this)

        content.setOnHoverListener(hoverListener)

        holders.clear()
        thumbRow?.removeAllViews()
        val cardWidth = cardWidthPx(getContext(), tasks.size)
        val gap = gapPx(getContext())
        val maxThumbHeight = (getContext().resources.displayMetrics.heightPixels * 0.5f).toInt()
        tasks.forEachIndexed { index, task ->
            val item = LayoutInflater.from(getContext())
                .inflate(R.layout.layout_task_preview_item, thumbRow, false)
            val lp = item.layoutParams as LinearLayout.LayoutParams
            lp.width = cardWidth
            if (index > 0) {
                lp.marginStart = gap
            }
            item.layoutParams = lp

            val title = item.findViewById<TextView>(R.id.task_preview_item_title)
            val close = item.findViewById<ImageView>(R.id.task_preview_item_close)
            val container = item.findViewById<FrameLayout>(R.id.task_preview_item_thumb_container)
            val thumb = item.findViewById<ImageView>(R.id.task_preview_item_thumb)
            val placeholder = item.findViewById<ImageView>(R.id.task_preview_item_placeholder)
            val loading = item.findViewById<ProgressBar>(R.id.task_preview_item_loading)

            title.text = task.label ?: task.program
            title.visibility = if (multiple) View.VISIBLE else View.GONE
            close.visibility = if (multiple) View.VISIBLE else View.GONE
            placeholder.setImageDrawable(task.icon)
            thumb.maxHeight = maxThumbHeight

            holders.add(ThumbHolder(task, title, close, container, thumb, placeholder, loading))

            container.setOnClickListener { onPreviewClickListener?.invoke(task) }
            thumb.setOnClickListener { onPreviewClickListener?.invoke(task) }
            close.setOnClickListener { onCloseClickListener?.invoke(task) }

            item.setOnHoverListener(hoverListener)
            title.setOnHoverListener(hoverListener)
            close.setOnHoverListener(hoverListener)
            container.setOnHoverListener(hoverListener)
            thumb.setOnHoverListener(hoverListener)

            thumbRow?.addView(item)
        }

        content.alpha = 0f
        content.animate().alpha(1f).setDuration(FADE_IN_DURATION).start()
        content.post {
            val location = IntArray(2)
            content.getLocationOnScreen(location)
            Log.d(
                TAG,
                "@${Integer.toHexString(System.identityHashCode(this))} layout done" +
                        " size=${content.width}x${content.height}" +
                        " screenPos=(${location[0]},${location[1]}) cards=${holders.size}"
            )
        }
    }

    /**
     * hover 判定不看 ENTER/EXIT 动作，也不看事件坐标：
     * - ViewGroup 在子 View 间切换时会先发新目标 ENTER 再发旧目标 EXIT，只看动作会把停在预览里误判成移出；
     * - 窗口级 EXIT 携带的坐标是离开时的边界坐标，按坐标判会误判成仍在窗口内。
     * 框架在派发过程中会同步更新各 View 的 isHovered，这里 post 到派发结束后再取框架状态判定。
     */
    private val hoverListener = View.OnHoverListener { view, event ->
        val action = event.action
        if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_EXIT) {
            Log.d(
                TAG,
                "@${Integer.toHexString(System.identityHashCode(this))}" +
                        " hoverEvent action=${MotionEvent.actionToString(action)}" +
                        " raw=(${event.rawX},${event.rawY})"
            )
            view.post { notifyHover(isPointerOverPreview()) }
        }
        false
    }

    private fun notifyHover(inside: Boolean) {
        if (inside == lastHoverInside) {
            return
        }
        lastHoverInside = inside
        Log.d(TAG, "preview hover inside=$inside")
        onHoverChangeListener?.invoke(inside)
    }

    /**
     * 指针当前是否停在预览窗口上（窗口内容或任意子 View 处于 hovered）。
     * 使用框架维护的 isHovered：窗口收到 HOVER_EXIT 时会同步清除，不依赖 ENTER/EXIT 的派发顺序。
     */
    fun isPointerOverPreview(): Boolean {
        val content = mContentView
        val over = content != null && isHoveredInTree(content)
        Log.d(
            TAG,
            "@${Integer.toHexString(System.identityHashCode(this))}" +
                    " isPointerOverPreview=$over content=${content?.width}x${content?.height}" +
                    " attached=${content?.isAttachedToWindow}"
        )
        return over
    }

    private fun isHoveredInTree(view: View): Boolean {
        if (view.isHovered) {
            return true
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (isHoveredInTree(view.getChildAt(i))) {
                    return true
                }
            }
        }
        return false
    }

    private fun loadThumbnails() {
        val token = ++loadToken
        val context = getContext()
        holders.forEach { holder ->
            val taskId = holder.task.id
            if (taskId <= 0) {
                holder.loading.visibility = View.GONE
                return@forEach
            }
            TaskThumbnailLoader.load(context, taskId) { bitmap ->
                if (token != loadToken || !isShowing()) {
                    return@load
                }
                if (bitmap != null) {
                    showThumbnail(holder, bitmap)
                } else {
                    holder.loading.visibility = View.GONE
                    holder.placeholder.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * 对可见任务（TOP）启动低频快照轮询，拿到接近实时的画面。
     * 后台任务系统不允许截取，轮询会自动结束，保留静态首帧。
     */
    private fun startLivePreview() {
        val target = tasks.firstOrNull { it.isTop() } ?: tasks.firstOrNull() ?: return
        if (target.id <= 0) {
            return
        }
        val holder = holders.firstOrNull { it.task === target } ?: return
        liveActive = true
        Log.d(TAG, "startLivePreview id=${target.id} app=${target.program}")
        TaskThumbnailLoader.startLive(getContext(), target.id) { frame ->
            if (!liveActive || !isShowing()) {
                return@startLive
            }
            showThumbnail(holder, frame.bitmap)
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

    private fun showThumbnail(holder: ThumbHolder, bitmap: Bitmap) {
        holder.thumb.setImageBitmap(bitmap)
        holder.thumb.visibility = View.VISIBLE
        holder.placeholder.visibility = View.GONE
        holder.loading.visibility = View.GONE
    }

    override fun onClick(v: View?) {
        if (v?.id == R.id.task_preview_close) {
            tasks.firstOrNull()?.let { onCloseClickListener?.invoke(it) }
        }
    }

    fun hide(animated: Boolean) {
        Log.d(TAG, "@${Integer.toHexString(System.identityHashCode(this))} hide animated=$animated")
        stopLivePreview()
        loadToken++
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
        // 动画回调异常时兜底移除，避免窗口残留
        handler.postDelayed({ dismissImmediately() }, FADE_OUT_DURATION + 80)
    }

    override fun dismiss() {
        Log.d(TAG, "@${Integer.toHexString(System.identityHashCode(this))} dismiss (self/outside)")
        stopLivePreview()
        loadToken++
        super.dismiss()
    }

    override fun dismissImmediately() {
        Log.d(TAG, "@${Integer.toHexString(System.identityHashCode(this))} dismissImmediately")
        stopLivePreview()
        loadToken++
        super.dismissImmediately()
    }

    private class ThumbHolder(
        val task: TaskInfo,
        val title: TextView,
        val close: ImageView,
        val container: FrameLayout,
        val thumb: ImageView,
        val placeholder: ImageView,
        val loading: ProgressBar
    )
}
