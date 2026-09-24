package com.fde.taskplugin.view

import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS
import android.app.ActivityManager
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.TYPE_SEARCH_BAR
import android.view.accessibility.AccessibilityManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fde.taskplugin.GlobalSystemUIContext
import com.fde.taskplugin.R
import com.fde.taskplugin.TaskInfo
import com.fde.taskplugin.TaskInfo.Companion.DOCK_TYPE_RECENTS
import com.fde.taskplugin.TaskInfo.Companion.DOCK_TYPE_TRASH
import com.fde.taskplugin.adapter.DockAppAdapter
import com.fde.taskplugin.data.AppData
import com.fde.taskplugin.data.DockContext
import com.fde.taskplugin.provider.AllAppsProvider
import com.fde.taskplugin.provider.DockAppsProvider
import com.fde.taskplugin.provider.DockAppsProvider.Companion.ACTION_DOCK_OVERVIEW
import com.fde.taskplugin.provider.DockAppsProvider.Companion.ACTION_OPEN_TRASH
import com.fde.taskplugin.provider.DockAppsProvider.Companion.ACTION_SHOW_RECENTS
import com.fde.taskplugin.receiver.UninstallReceiver
import com.fde.taskplugin.utils.AppUtils
import com.fde.taskplugin.utils.DocumentsUiHelper
import com.fde.taskplugin.utils.HostActivityManager
import com.fde.taskplugin.utils.HostDesktopMode
import com.fde.taskplugin.utils.ScreenSizeUtils
import com.fde.taskplugin.utils.Utils
import com.fde.taskplugin.view.AppOverviewWindow.Companion.TYPE_ALL
import com.fde.taskplugin.view.AppOverviewWindow.Companion.WINDOW_PADDING
import com.fde.taskplugin.view.LoadedDockContextRecycleView.Companion.TYPE_APP
import kotlin.math.abs


class DockAppsLayout
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr),
    DockAppsProvider.DockTaskViewUpdater,
    DockAppItemDecoration.AppClassify,
    DockAppAdapter.DockItemClickListener,
    AllAppsProvider.OverviewAppsUpdater,
    UninstallReceiver.AppUninstallListener
{

    var dockScaleFactor: Float = 1.0f
    private var launcherResumeFlag: Boolean ?= false
    private val activityManager: ActivityManager
    private val launchApps: LauncherApps
    private val userManager: UserManager
    private val windowManager:WindowManager
    private val tasks: MutableList<TaskInfo> = ArrayList()
    private var trashTask: TaskInfo? = null
    private var recentsTask: TaskInfo? = null
    val overviewApps: MutableList<AppData> = java.util.concurrent.CopyOnWriteArrayList()
    private val dockAppAdapter: DockAppAdapter?
    private val dockProvider: DockAppsProvider
    var overviewProvider: AllAppsProvider ?= null
    private var systemUIContext: Context ?= null

    var status: View?= null
    var navi: View?= null
    val SYSTEM_ALL_APP_ACTION = "system_all_app_action"
    var accessibilityManager: AccessibilityManager? = null

    private var itemDecoration: DockAppItemDecoration? = null
    var appOverviewWindow: AppOverviewWindow ?= null

    var globalSearchRecevier:GlobalSearchRecevier ?= null
    var filter: IntentFilter ?= null
    var broadcast :PendingIntent ?= null

    private var itemTouchHelper: ItemTouchHelper? = null
    private var pressX = 0f
    private var pressY = 0f
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        val child = findChildViewUnder(pressX, pressY)
        val holder = child?.let { getChildViewHolder(it) }
        holder?.let { itemTouchHelper?.startDrag(it) }
    }

    private var taskPreviewWindow: TaskPreviewWindow? = null
    private var previewTaskInfo: TaskInfo? = null
    private var pendingPreviewTaskInfo: TaskInfo? = null
    private var pendingPreviewAnchor: View? = null
    private val previewHandler = Handler(Looper.getMainLooper())
    private val hidePreviewRunnable = Runnable {
        val preview = taskPreviewWindow
        val over = preview?.isPointerOverPreview()
        Log.d(
            TAG,
            "hidePreviewRunnable run: preview=$preview showing=${preview?.isShowing()}" +
                    " overPreview=$over previewTask=${previewTaskInfo?.program}"
        )
        if (preview == null || over != true) {
            dismissPreviewWindow("hideRunnable")
        } else {
            Log.d(TAG, "hidePreviewRunnable keep: pointer still over preview")
        }
    }
    private val showPreviewRunnable = Runnable {
        Log.d(TAG, "showPreviewRunnable run pending=${pendingPreviewTaskInfo?.program}")
        val info = pendingPreviewTaskInfo ?: return@Runnable
        val anchor = pendingPreviewAnchor ?: return@Runnable
        showTaskPreview(info, anchor)
    }

    companion object {
        private const val TAG = "DockAppsLayout"
        private const val ACTION_SHORT_CUT = "com.android.launcher3.action.ADD_SHORT_CUT"
        private const val DRAG_LONG_PRESS_TIMEOUT = 100L
        private const val PREVIEW_SHOW_DELAY = 350L
        private const val PREVIEW_HIDE_DELAY = 220L
        private const val PREVIEW_EDGE_MARGIN = 8
        private const val PREVIEW_BOTTOM_GAP = 6
    }

    init {
        activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        launchApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        layoutManager = LinearLayoutManager(context, HORIZONTAL, false)
        // dock 宽度随图标数量变化，wrap_content 的背景需要跟着重新测量，
        // 否则增量刷新时圆角背景宽度会停在旧值。
        setHasFixedSize(false)
        itemAnimator = DockItemAnimator()
        dockAppAdapter = DockAppAdapter(context)
        adapter = dockAppAdapter
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        dockProvider = DockAppsProvider(context, this)
//        overviewProvider = AllAppsProvider(context, this)
        setupDragHelper()
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    dismissTaskPreview()
                }
            }
        })
    }

    private fun setupDragHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0,
        ) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                val pos = viewHolder.adapterPosition
                if (pos == RecyclerView.NO_POSITION || pos <= 0 || pos >= dockProvider.getPersistSize()) {
                    return makeMovementFlags(0, 0)
                }
                return makeMovementFlags(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)
            }

            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                    return false
                }
                val persistSize = dockProvider.getPersistSize()
                if (from <= 0 || to <= 0 || from >= persistSize || to >= persistSize) {
                    return false
                }
                dockProvider.movePersistItem(from, to)
                tasks.add(to, tasks.removeAt(from))
                dockAppAdapter?.moveItem(from, to)
                return true
            }

            override fun onSwiped(
                viewHolder: RecyclerView.ViewHolder,
                direction: Int,
            ) {
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                dockProvider.savePersistOrder()
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper?.attachToRecyclerView(this)
        setupCustomLongPress()
    }

    private fun setupCustomLongPress() {
        addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressX = e.x
                        pressY = e.y
                        longPressHandler.removeCallbacks(longPressRunnable)
                        longPressHandler.postDelayed(longPressRunnable, DRAG_LONG_PRESS_TIMEOUT)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val slop = ViewConfiguration.get(context).scaledTouchSlop
                        if (abs(e.x - pressX) > slop || abs(e.y - pressY) > slop) {
                            longPressHandler.removeCallbacks(longPressRunnable)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                }
                return false
            }
        })
    }

    fun updateNaviWindowFlags() {
        val windowRoot = navi ?: return
        val params = windowRoot.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = params.flags and (
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_SLIPPERY
            ).inv()
        if (windowRoot.isAttachedToWindow) {
            windowManager.updateViewLayout(windowRoot, params)
        }
    }

    override fun onTouchEvent(e: MotionEvent?): Boolean {
        Log.d(TAG, "onTouchEvent() called with: e = $e")
        if(e?.buttonState == MotionEvent.BUTTON_SECONDARY && e.action == MotionEvent.ACTION_DOWN){
            dismissTaskPreview()
            dockAppAdapter?.makeListContextWindowAt(
                e.rawX.toInt(),
                null
            )
            return true
        }
        return super.onTouchEvent(e)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        disableClipForUnboundedDrag()
    }

    private fun disableClipForUnboundedDrag() {
        var p: android.view.ViewParent? = parent
        while (p != null) {
            if (p is ViewGroup) {
                p.clipChildren = false
                p.clipToPadding = false
                p.clipToOutline = false
            }
            p = p.parent
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissTaskPreview()
        dockProvider.unregisterTaskStackListener()
    }
    fun initApps(dockScaleFactor: Float) {
        dismissTaskPreview()
        this.dockScaleFactor = dockScaleFactor
        overviewProvider?.provideAppsWithFilterAsync(TYPE_ALL, null)
//        val provideApps = overviewProvider?.provideAppsWithFilterAsync(TYPE_ALL, null)
//        if (provideApps != null) {
//            overviewApps.clear()
//            overviewApps.addAll(provideApps)
//        }
        dockProvider.providePersistApps();
        dockProvider.mayFillPersistTaskInfo()
        tasks.clear()
        tasks.addAll(dockProvider.persistDockApps)
        appendFixedTasks()
        itemDecoration = DockAppItemDecoration(this)
        addItemDecoration(itemDecoration!!)
        dockAppAdapter?.dockScaleFactor = dockScaleFactor
        dockAppAdapter?.setData(tasks)
        dockAppAdapter?.listener = this
        dockAppAdapter?.notifyDataSetChangedWapper()
        dockAppAdapter?.dockAppLayout = this
        updateNaviWidth(tasks.size)
        dockProvider.registerTaskStackListener()
//        globalSearchRecevier = GlobalSearchRecevier()
//        filter = IntentFilter()
//        filter?.addAction(SYSTEM_ALL_APP_ACTION)
//        context.registerReceiver(globalSearchRecevier, filter, RECEIVER_EXPORTED)
//        broadcast = PendingIntent.getBroadcast(
//            context,
//            0,
//            Intent(SYSTEM_ALL_APP_ACTION),
//            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
//        )
//        accessibilityManager =  GlobalSystemUIContext.getGlobalSystemuiContext()?.getSystemService(AccessibilityManager::class.java)
//        accessibilityManager!!.registerSystemAction(
//            RemoteAction(
//                Icon.createWithResource(context, R.drawable.icon_menu),
//                context.getString(R.string.search),
//                context.getString(R.string.search),
//                broadcast!!
//            ),
//            GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS)
        Log.d(TAG, "$this initApps: $globalSearchRecevier")
    }

    override fun removeTask(taskId: Int) {
        Log.d(TAG, "removeTask() called with: taskId = $taskId")
        var taskInfo :TaskInfo ?= null
        tasks.forEach{info ->
            if(info.id == taskId){
                taskInfo = info
            }
        }
        if(taskInfo != null){
            if( taskInfo!!.isPersist()){
                taskInfo?.finshTask()
            } else {
                tasks.removeIf { taskInfo: TaskInfo -> taskInfo.id == taskId }
            }
            dockAppAdapter!!.setData(tasks)
            dockAppAdapter.notifyDataSetChangedWapper()
        }
        updateTaskPreviewAfterDataChange()
    }

    override fun setTop(taskInfo: TaskInfo?, needAdd: Boolean, isTop: Boolean) {
        if (taskInfo == null){
            dockAppAdapter?.setTopTaskId(null)
        } else {
            if(isTop){
                dockAppAdapter?.setTopTaskId(taskInfo)
            }
            if(needAdd){
                // 回收站/最近任务固定在最后，新运行的任务插到它们前面
                val fixedIndex = tasks.indexOfFirst { isFixedTask(it) }
                if (fixedIndex >= 0) {
                    tasks.add(fixedIndex, taskInfo)
                } else {
                    tasks.add(taskInfo)
                }
            }
            dockAppAdapter!!.setData(tasks)
        }
//        Log.d(TAG, "setTop() called with: taskInfo = $taskInfo, needAdd = $needAdd, isTop = $isTop")
        dockAppAdapter?.notifyDataSetChangedWapper()
        updateNaviWidth(tasks.size)
        updateTaskPreviewAfterDataChange()
    }

    override fun notifyDockAapp(list: MutableList<TaskInfo>) {
        tasks.clear()
        tasks.addAll(list)
        appendFixedTasks()
        Log.d(TAG, "notifyDockAapp: ")
//        tasks.forEach { taskInfo -> Log.d(TAG, "notifyDockAapp each: $taskInfo") }
        dockAppAdapter?.setData(tasks)
        dockAppAdapter?.notifyDataSetChangedWapper()
        updateNaviWidth(tasks.size)
        updateTaskPreviewAfterDataChange()
    }

    private fun isTrashTask(taskInfo: TaskInfo): Boolean {
        return ACTION_OPEN_TRASH == taskInfo.action
    }

    private fun isRecentsTask(taskInfo: TaskInfo): Boolean {
        return ACTION_SHOW_RECENTS == taskInfo.action
    }

    private fun isFixedTask(taskInfo: TaskInfo): Boolean {
        return isTrashTask(taskInfo) || isRecentsTask(taskInfo)
    }

    private fun createTrashTask(): TaskInfo {
        val task = TaskInfo("com.android.documentsui", DocumentsUiHelper.getTrashLabel(context))
        task.action = ACTION_OPEN_TRASH
        task.dockType = DOCK_TYPE_TRASH
        task.icon = context.getDrawable(R.drawable.icon_trash)
        return task
    }

    private fun createRecentsTask(): TaskInfo {
        val task = TaskInfo("com.fde.recents", context.resources.getString(R.string.recents))
        task.action = ACTION_SHOW_RECENTS
        task.dockType = DOCK_TYPE_RECENTS
        task.icon = context.getDrawable(R.drawable.icon_recents)
        return task
    }

    /** 固定的两个图标：最近任务、回收站，始终排在最后。 */
    private fun appendFixedTasks() {
        val recents = recentsTask ?: createRecentsTask().also { recentsTask = it }
        val trash = trashTask ?: createTrashTask().also { trashTask = it }
        tasks.removeAll { isFixedTask(it) }
        tasks.add(recents)
        tasks.add(trash)
    }

    private fun showRecents() {
        // 和最近任务键同一条路径：通知 launcher 走 OverviewCommandHelper 切换 overview
        Utils.notifyShowRecents(context)
        Log.d(TAG, "showRecents broadcast sent")
    }

    private fun openTrash() {
        if (DocumentsUiHelper.openTrash(context)) {
            return
        }
        // 兜底：回收站入口打不开时至少把 DocumentsUI 拉起来
        launchDocumentsUi()
    }

    private fun emptyTrash() {
        if (DocumentsUiHelper.emptyTrash(context)) {
            return
        }
        openTrash()
    }

    private fun launchDocumentsUi() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.android.documentsui")
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) {
                context.startActivity(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "launchDocumentsUi failed", t)
        }
    }

    /**
     * dock 图标 hover 回调。运行中的任务在短暂延迟后弹出缩略图预览，
     * 移出图标后延迟消失，方便鼠标移动到预览窗口上。
     */
    fun onDockItemHover(taskInfo: TaskInfo, anchor: View, hovered: Boolean) {
        Log.d(
            TAG,
            "onDockItemHover hovered=$hovered app=${taskInfo.program} id=${taskInfo.id}" +
                    " state=${taskInfo.getState()} canShow=${canShowTaskPreview(taskInfo)}" +
                    " previewTask=${previewTaskInfo?.program}@${previewTaskInfo?.id}" +
                    " pendingTask=${pendingPreviewTaskInfo?.program}@${pendingPreviewTaskInfo?.id}" +
                    " windowShowing=${taskPreviewWindow?.isShowing()}" +
                    " overPreview=${taskPreviewWindow?.isPointerOverPreview()}"
        )
        if (!hovered) {
            // 注意：ViewGroup 会先派发新目标的 ENTER，再派发旧目标的 EXIT。
            // 这里只能收起/取消"属于这个任务"的预览，否则会把刚排上的新任务弹出取消掉。
            if (pendingPreviewTaskInfo === taskInfo) {
                Log.d(TAG, "onDockItemHover exit: cancel pending show for ${taskInfo.program}")
                previewHandler.removeCallbacks(showPreviewRunnable)
                pendingPreviewTaskInfo = null
            }
            if (previewTaskInfo === taskInfo) {
                previewHandler.removeCallbacks(hidePreviewRunnable)
                // 指针可能已经移进预览窗口，此时不要再排隐藏
                if (taskPreviewWindow?.isPointerOverPreview() != true) {
                    Log.d(TAG, "onDockItemHover exit: post hide for ${taskInfo.program} in ${PREVIEW_HIDE_DELAY}ms")
                    previewHandler.postDelayed(hidePreviewRunnable, PREVIEW_HIDE_DELAY)
                } else {
                    Log.d(TAG, "onDockItemHover exit: pointer over preview, skip hide")
                }
            }
            return
        }
        if (!canShowTaskPreview(taskInfo)) {
            Log.d(TAG, "onDockItemHover enter: cannot show preview for ${taskInfo.program}")
            return
        }
        if (dockAppAdapter?.isContextWindowShowing() == true) {
            Log.d(TAG, "onDockItemHover enter: context menu showing, skip preview for ${taskInfo.program}")
            return
        }
        if (previewTaskInfo === taskInfo) {
            previewHandler.removeCallbacks(hidePreviewRunnable)
            if (taskPreviewWindow?.isShowing() == true) {
                Log.d(TAG, "onDockItemHover enter: preview already showing for ${taskInfo.program}")
                return
            }
        }
        Log.d(TAG, "onDockItemHover enter: post show for ${taskInfo.program} in ${PREVIEW_SHOW_DELAY}ms")
        pendingPreviewTaskInfo = taskInfo
        pendingPreviewAnchor = anchor
        previewHandler.removeCallbacks(showPreviewRunnable)
        previewHandler.postDelayed(showPreviewRunnable, PREVIEW_SHOW_DELAY)
    }

    private fun canShowTaskPreview(taskInfo: TaskInfo): Boolean {
        return taskInfo.id != TaskInfo.ID_UNDEFINED &&
                taskInfo.getState() >= TaskInfo.STATE_RUNNING &&
                !ACTION_DOCK_OVERVIEW.equals(taskInfo.action)
    }

    private fun showTaskPreview(taskInfo: TaskInfo, anchor: View) {
        if (dockAppAdapter?.isContextWindowShowing() == true) {
            Log.d(TAG, "showTaskPreview skip: context menu showing")
            return
        }
        if (!anchor.isAttachedToWindow) {
            Log.w(TAG, "showTaskPreview anchor detached, skip")
            return
        }
        dismissTaskPreviewImmediately()

        // 同一个包的多个窗口并排展示
        val windows = dockProvider.getRunningTaskInfosFor(taskInfo.packageName)
        val previewTasks = buildPreviewTasks(taskInfo, windows)

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val previewWidth = TaskPreviewWindow.previewWidthPx(context, previewTasks.size)
        val screenWidth = ScreenSizeUtils.getInstance(context).screenWidth
        val screenHeight = ScreenSizeUtils.getInstance(context).screenHeight
        var x = location[0] + anchor.width / 2 - previewWidth / 2
        val maxX = screenWidth - previewWidth - PREVIEW_EDGE_MARGIN
        x = x.coerceIn(PREVIEW_EDGE_MARGIN, maxX.coerceAtLeast(PREVIEW_EDGE_MARGIN))
        // Gravity.BOTTOM 的 y 是相对窗口可用区域底边的偏移。taskbar 是导航栏，
        // 其他窗口的可用区域底边在 taskbar 顶部，而不是屏幕底部。
        val rootLocation = IntArray(2)
        anchor.rootView.getLocationOnScreen(rootLocation)
        val rootBottom = rootLocation[1] + anchor.rootView.height
        val frameBottom = if (rootBottom >= screenHeight - 2) rootLocation[1] else screenHeight
        val gap = Utils.dpToPx(context, PREVIEW_BOTTOM_GAP)
        val bottomOffset = (frameBottom - (location[1] - gap)).coerceAtLeast(0)
        Log.d(
            TAG,
            "showTaskPreview app=${taskInfo.program} id=${taskInfo.id} windows=${previewTasks.size}" +
                    " anchor=${location[0]},${location[1]} ${anchor.width}x${anchor.height}" +
                    " screen=${screenWidth}x$screenHeight rootTop=${rootLocation[1]}" +
                    " frameBottom=$frameBottom x=$x bottom=$bottomOffset"
        )

        val window = TaskPreviewWindow(
            context,
            previewWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.LEFT,
            R.layout.layout_task_preview,
            WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG
        )
        window.setTasks(previewTasks)
        window.offsetX = x
        window.offsetY = bottomOffset
        window.elevation = Utils.dpToPx(context, 8)
        window.onPreviewClickListener = { info ->
            dismissTaskPreview()
            onItemClick(resources.getString(R.string.show), info)
        }
        window.onCloseClickListener = { info ->
            dismissTaskPreview()
            closeRunningTask(info)
        }
        window.onHoverChangeListener = { hovered ->
            Log.d(
                TAG,
                "preview hover=$hovered windowShowing=${taskPreviewWindow?.isShowing()}" +
                        " previewTask=${previewTaskInfo?.program}"
            )
            previewHandler.removeCallbacks(hidePreviewRunnable)
            if (!hovered && taskPreviewWindow?.isShowing() == true) {
                previewHandler.postDelayed(hidePreviewRunnable, PREVIEW_HIDE_DELAY)
            }
        }
        window.dismissListener = object : AbsTopPopWindow.WindowDismissListener {
            override fun onWindowDismiss() {
                if (taskPreviewWindow === window) {
                    taskPreviewWindow = null
                    previewTaskInfo = null
                }
            }
        }
        try {
            window.showPopupWindow()
            taskPreviewWindow = window
            previewTaskInfo = taskInfo
            Log.d(
                TAG,
                "showTaskPreview shown=${window.isShowing()}" +
                        " contentAttached=${window.getContentView()?.isAttachedToWindow}"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "showTaskPreview failed", t)
        }
    }

    /**
     * 把 dock 图标对应的运行中窗口转成预览用的 TaskInfo（每个窗口一个，带独立 taskId）。
     * 拿不到运行窗口时退回 dock 图标自身的 TaskInfo。
     */
    private fun buildPreviewTasks(dockTask: TaskInfo, windows: MutableList<ActivityManager.RunningTaskInfo>): MutableList<TaskInfo> {
        if (windows.isEmpty()) {
            return mutableListOf(dockTask)
        }
        return windows.map { running ->
            TaskInfo(dockTask.packageName, dockTask.program).apply {
                id = running.taskId
                label = running.taskDescription?.label
                action = dockTask.action
                platformType = dockTask.platformType
                icon = dockTask.icon
                setState(if (running.isFocused) TaskInfo.STATE_TOP else TaskInfo.STATE_RUNNING)
            }
        }.toMutableList()
    }

    fun dismissTaskPreview() {
        Log.d(TAG, "dismissTaskPreview preview=${previewTaskInfo?.program} pending=${pendingPreviewTaskInfo?.program}")
        previewHandler.removeCallbacks(showPreviewRunnable)
        previewHandler.removeCallbacks(hidePreviewRunnable)
        dismissPreviewWindow("dismissTaskPreview")
        pendingPreviewTaskInfo = null
        pendingPreviewAnchor = null
    }

    /** 只收掉当前预览窗口，不影响已经排队的下一个预览。 */
    private fun dismissPreviewWindow(reason: String) {
        Log.d(TAG, "dismissPreviewWindow reason=$reason preview=${previewTaskInfo?.program}@${previewTaskInfo?.id}")
        taskPreviewWindow?.hide(true)
        taskPreviewWindow = null
        previewTaskInfo = null
    }

    private fun dismissTaskPreviewImmediately() {
        Log.d(TAG, "dismissTaskPreviewImmediately preview=${previewTaskInfo?.program}@${previewTaskInfo?.id}")
        taskPreviewWindow?.dismissImmediately()
        taskPreviewWindow = null
        previewTaskInfo = null
    }

    private fun closeRunningTask(taskInfo: TaskInfo) {
        if (!HostActivityManager.removeTask(taskInfo.id)) {
            activityManager.moveTaskToBack(false, taskInfo.id)
        }
    }

    private fun updateTaskPreviewAfterDataChange() {
        val previewed = previewTaskInfo ?: return
        val dockTask = tasks.firstOrNull { TextUtils.equals(it.packageName, previewed.packageName) }
        if (dockTask == null || !dockTask.isRunning() ||
            dockProvider.getRunningTaskInfosFor(previewed.packageName).isEmpty()) {
            dismissTaskPreview()
        }
    }


    fun updateNaviWidth(count :Int){
        val windowRoot = navi
        windowRoot?.let { view ->
            val windowManager = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = view.layoutParams as? WindowManager.LayoutParams
            if (params != null) {

                val dock_height = context?.resources?.getDimension(R.dimen.dock_app_layout_height)
                var dock_height_scaled = dock_height?.times(dockScaleFactor)?.plus(0.5f)

                params.height = dock_height_scaled!!.toInt()
                val dock_item_width = context?.resources?.getDimension(R.dimen.dock_icon_width)
                val dock_item_width_scaled = dock_item_width?.times(dockScaleFactor)?.plus(0.5f)
                val itemWidth = dock_item_width_scaled!!.toInt()
                val itemMargin =
                    context?.resources?.getDimension(R.dimen.dock_icon_margin)?.toInt()!! * 4
                val groupMargin =
                    context?.resources?.getDimension(R.dimen.dock_group_margin)?.toInt()!! * 2
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                var width = count * (itemWidth + itemMargin ) + groupMargin + context?.resources?.getDimension(R.dimen.dock_width_margin)?.toInt()!!
                val px = Utils.dpToPx(context, width)
                Log.d(TAG, "$this updateNaviWidth: px:$px width:$width")
                if(ScreenSizeUtils.getInstance( context).screenWidth < width){
                    width = ScreenSizeUtils.getInstance( context).screenWidth
                }

                params.width = width
                if(view.isAttachedToWindow){
                    windowManager.updateViewLayout(view, params)
                }

            }
        }
    }


    override fun getDockAapp(): MutableList<TaskInfo> {
        return tasks
    }

    override fun getOverviewAppData(): MutableList<AppData> {
        return overviewApps
    }

    fun reloadActivityManager(systemUIContext: Context?) {
        if (systemUIContext != null) {
            this.systemUIContext = systemUIContext
        }
        dockAppAdapter?.reloadActivityManager(systemUIContext)
    }

    override fun classifyPersit(): Int {
        return dockProvider.getPersistSize()
    }

    override fun classifyActive(): Int {
        return dockProvider.getActiveSize()
    }

    override fun onItemClick(dockContext: DockContext) {
        if(appOverviewWindow != null && appOverviewWindow?.isShowing() == true){
            appOverviewWindow?.dismiss()
//            return
        }
        if(dockContext.type == TYPE_APP){
            dockContext.app ?.let { appData ->
                try {
                    if(appData?.linuxInfo != null){
                        val intent = Intent(Intent.ACTION_VIEW)
                        intent.setDataAndType(Uri.EMPTY, "application/vnd.desktop")
                        val linuxInfo = appData.linuxInfo
                        intent.putExtra("openParams", linuxInfo?.name + "###" + linuxInfo?.path  )
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    } else {
                        val intent = Intent()
                        intent.component = appData?.componentName
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    }
                } catch (e: ActivityNotFoundException) {
                }
            }
        } else if(dockContext.taskInfo == null){
            when(dockContext.name){
                resources.getString(R.string.dock_settings) ->{
                    val intent = Intent()
                    val cn: ComponentName? = ComponentName.unflattenFromString("com.android.settings/.TextReadingForSetupWizardActivity")
                    intent.component = cn;
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
                resources.getString(R.string.close_overview) ->{
                    appOverviewWindow?.dismiss()
                }
            }
        } else {
            onItemClick(dockContext.name, dockContext.taskInfo!!)
        }

    }

    inner class GlobalSearchRecevier : BroadcastReceiver(){
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "onReceive() called with: context = ${context?.packageName}, intent = $intent")

            if(SYSTEM_ALL_APP_ACTION != intent?.action){
                return
            }
            Log.d(TAG, "onReceive    ${this} : ${this@DockAppsLayout}" +
                    "  $appOverviewWindow ${appOverviewWindow?.isShowing()}")
//            if(appOverviewWindow == null){
                makeOverviewWinow()
//            }


            if(appOverviewWindow?.isShowing() == true){
                appOverviewWindow?.dismiss()
            } else {
                appOverviewWindow?.showPopupWindow()
            }
        }
    }

    override fun onItemClick(action: String?, taskInfo: TaskInfo) {
        if(!ACTION_DOCK_OVERVIEW.equals(taskInfo.action)) {
            if(appOverviewWindow != null && appOverviewWindow?.isShowing() == true){
                appOverviewWindow?.dismiss()
                return
            }
        }
        when(action){
            resources.getString(R.string.exit) ->{
                activityManager.moveTaskToBack(false, taskInfo.id)
            }
            resources.getString(R.string.open) ->{
                if(ACTION_DOCK_OVERVIEW.equals(taskInfo.action)) {
//                    context.sendBroadcast(Intent(action))
                    showAppsOverview()
//                    overviewProvider?.provideAppsWithFilterSync(TYPE_ALL, null)
                }else if(ACTION_OPEN_TRASH.equals(taskInfo.action)){
                    openTrash()
                }else if(ACTION_SHOW_RECENTS.equals(taskInfo.action)){
                    showRecents()
                }else if(!TextUtils.isEmpty(taskInfo.packageName) && taskInfo.launchIntent != null){
                    val launchIntent = taskInfo.launchIntent
                    launchIntent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launchIntent)
                }
            }
            resources.getString(R.string.empty_trash) ->{
                emptyTrash()
            }
            resources.getString(R.string.show) ->{
                // Android 17: 恢复桌面任务必须走 Shell 的 showDesktopApp，
                // 否则 MinimizedDesk 里的任务用 moveTaskToFront 无法反最小化。
                if (!HostDesktopMode.showTask(taskInfo.id)) {
                    activityManager.moveTaskToFront(
                        taskInfo.id,
                        ActivityManager.MOVE_TASK_NO_USER_ACTION
                    )
                }
            }
            resources.getString(R.string.minimize) ->{
                Log.d(TAG, "onItemClick() called with: action = $action, taskInfo = $taskInfo")
                // 与标题栏最小化一致：reparent 到 MinimizedDesk + 最小化动画
                if (!HostDesktopMode.minimizeTask(taskInfo.id)) {
                    activityManager.moveTaskToBack(true, taskInfo.id)
                }
            }
            resources.getString(R.string.pin) ->{
                dockProvider.pin(taskInfo)
            }
            resources.getString(R.string.unpin) ->{
                dockProvider.unpin(taskInfo)
            }
            resources.getString(R.string.compatible_set) ->{
                val packageManager: PackageManager = context.packageManager
                try {
                    val label =
                        packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(
                                taskInfo.packageName!!,
                                PackageManager.GET_META_DATA
                            ),
                        )
                    AppUtils.toConpatiblePage(context, taskInfo.packageName, label.toString())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            resources.getString(R.string.dock_settings) ->{
                val intent = Intent()
                val cn: ComponentName? = ComponentName.unflattenFromString("com.android.settings/.TextReadingForSetupWizardActivity")
                intent.component = cn;
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
            resources.getString(R.string.todesk) ->{
                val inte = Intent(ACTION_SHORT_CUT)
                inte.putExtra("packageName", taskInfo.packageName!!)
                inte.putExtra("appName", taskInfo.program!!)
                inte.setPackage("com.android.launcher3")
                context.sendBroadcast(inte)
            }
        }
    }

    private fun showAppsOverview() {
//        overviewProvider.provideAppsWithFilterAsync(TYPE_ALL, null)
        makeOverviewWinow()
        if(appOverviewWindow?.isShowing() != true){
            appOverviewWindow?.showPopupWindow()
            if (dockAppAdapter?.getTopTaskId() != -1){
                launcherResumeFlag = true
//                val runningTasks = activityManager?.getRunningTasks(MAX_RUNNING_TASKS)
//                if (runningTasks != null) {
//                    for (runningTask in runningTasks){
//                        if(dockProvider?.isLauncher(context, runningTask.topActivity) == true){
//                            activityManager?.moveTaskToFront( runningTask.taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
//                        }
//                    }
//                }
            }
            status?.visibility = View.GONE
        } else{
            appOverviewWindow?.dismiss()
        }
    }

    fun makeOverviewWinow() {
        if(appOverviewWindow == null){
            appOverviewWindow = AbsTopPopWindow.Builder(context, MATCH_PARENT, MATCH_PARENT,
                R.layout.layout_all_app_overview)
                .gravity(Gravity.START or Gravity.TOP)
                .locate(WINDOW_PADDING, WINDOW_PADDING)
                .elevation(0)
                .provider(null)
                .paramType(TYPE_SEARCH_BAR)
                .build(AbsTopPopWindow.WindowType.Overview) as AppOverviewWindow
            appOverviewWindow?.dockScaleFactor = dockScaleFactor
            appOverviewWindow?.updateAppList(overviewApps)
            appOverviewWindow?.dismissListener = object : AbsTopPopWindow.WindowDismissListener{
                override fun onWindowDismiss() {
                    status?.visibility = View.VISIBLE
//                    val runningTasks = activityManager?.getRunningTasks(MAX_RUNNING_TASKS)
//                    if (runningTasks != null && launcherResumeFlag == true) {
//                        for (runningTask in runningTasks){
//                            if(dockProvider?.isLauncher(context, runningTask.topActivity) == true){
//                                activityManager.moveTaskToBack(true, runningTask.taskId)
//                            }
//                        }
//                    }
                    launcherResumeFlag = false
                }
            }
            appOverviewWindow?.appProvider = overviewProvider
            appOverviewWindow?.dockProvider = dockProvider
        } else {
            appOverviewWindow?.dockScaleFactor = dockScaleFactor
        }
    }

    override fun onAppListUpdated(list: List<AppData>) {
        overviewApps.clear()
        overviewApps.addAll(list)
        dockProvider.mayFillPersistTaskInfo()
        appOverviewWindow?.updateAppList(overviewApps)
        dockAppAdapter?.notifyDataSetChangedWapper()
        Log.d(TAG, "onAppListUpdated() $dockAppAdapter overviewApps:$overviewApps : list = $list $dockAppAdapter")
    }

    override fun onUninstall(packageName: String) {
        dockProvider.unpin(packageName)
        overviewProvider?.provideAppsWithFilterAsync(TYPE_ALL, null);
//        dockProvider.updateUninstall(packageName)
    }

    override fun onInstall(packageName: String?) {

    }

    fun dimissWindow() {
        AbsTopPopWindow.dissmissWindow(appOverviewWindow)
    }

    fun onDestroy() {
        Log.d(TAG, "$this onDestroy() $globalSearchRecevier")
        dismissTaskPreview()
        appOverviewWindow?.dismiss()
        globalSearchRecevier?.also { receiver ->
            context.unregisterReceiver(receiver)
            globalSearchRecevier = null
        }
        accessibilityManager?.let { acm ->
            acm.unregisterSystemAction(GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS)
        }
    }

}