package com.fde.taskplugin.provider

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.text.TextUtils
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.fde.taskplugin.R
import com.fde.taskplugin.TaskInfo
import com.fde.taskplugin.TaskInfo.Companion.DOCK_TYPE_NORMAL
import com.fde.taskplugin.TaskInfo.Companion.DOCK_TYPE_PERSISIT
import com.fde.taskplugin.TaskInfo.Companion.PLATFORM_TYPE_ANDROID
import com.fde.taskplugin.TaskInfo.Companion.PLATFORM_TYPE_X11
import com.fde.taskplugin.TaskInfo.Companion.STATE_RUNNING
import com.fde.taskplugin.TaskInfo.Companion.STATE_TOP
import com.fde.taskplugin.data.AppData
import com.fde.taskplugin.data.PersistApp
import com.fde.taskplugin.utils.SPUtils
import com.fde.taskplugin.utils.Utils


class DockAppsProvider(private val context: Context, private val updater: DockTaskViewUpdater){

    private val TAG: String = "DockAppsProvider"
    val packageManager: PackageManager
    val activityManager: ActivityManager
    private val appstateListener: AppStateListener
    private val taskFocusListener: TaskStackListener
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val launchApps: LauncherApps
    private val userManager: UserManager
    val persistDockApps: MutableList<TaskInfo> = ArrayList()
    private val activeDockApps: MutableList<TaskInfo> = ArrayList()
    private val persisApps: MutableList<PersistApp> = ArrayList()
    private var taskStackListenerRegistered = false

    companion object {
        const val PACKAGE_X11 = "com.fde.x11"
        const val PACKAGE_VNC = "com.iiordanov.bVNC"
        const val ACTION_DOCK_OVERVIEW = "com.fde.systemui.SHOW_APP_OVERVIEW"
        const val ACTION_OPEN_TRASH = "com.fde.systemui.OPEN_TRASH"
        const val ACTION_SHOW_RECENTS = "com.fde.systemui.SHOW_RECENTS"
        /** Sent when the persisted dock/pin state changes so every display taskbar refreshes. */
        const val ACTION_DOCK_STATE_CHANGED = "com.fde.taskplugin.DOCK_STATE_CHANGED"

        private val AM_WRAPPER = ActivityManagerWrapper.getInstance()
        private val TC_WRAPPER = TaskStackChangeListeners.getInstance()
        const val MAX_RUNNING_TASKS = 50
    }

    init {
        packageManager = context.packageManager
        activityManager = context.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
        userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        launchApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        appstateListener = AppStateListener(updater)
        // FDE-X11 在 setTaskDescription（标题/图标变化）后发广播，广播里带 taskId + icon。
        // 不能只 updateDockAppLocked()：getRunningTasks() 后续返回的 taskDescription.icon
        // 是 null，靠重新拉取永远拿不到新图标。这里直接用广播里的 Bitmap 更新缓存条目。
        try {
            context.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    val taskId = i?.getIntExtra("taskId", -1) ?: -1
                    val icon: android.graphics.Bitmap? =
                        i?.getParcelableExtra("icon")
                    val title = i?.getStringExtra("title")
                    Log.d(TAG, "TASK_DESCRIPTION_UPDATED taskId=$taskId icon=${icon != null}"
                            + " title=$title")
                    mainHandler.post {
                        val targets = ArrayList<TaskInfo>()
                        activeDockApps.filterTo(targets) { it.id == taskId }
                        persistDockApps.filterTo(targets) { it.id == taskId }
                        targets.forEach { info ->
                            if (icon != null) {
                                info.icon = BitmapDrawable(icon)
                                info.iconPath = null
                            }
                            // 不动 program：它是 val，且 dock 显示用的是 X 窗口的 label
                            // （"FDE::TITLE::" + label），由 topTask 从 taskDescription 解析。
                        }
                        Log.d(TAG, "TASK_DESCRIPTION_UPDATED applied to ${targets.size} entries")
                        appstateListener.updateDockAppLocked()
                    }
                }
            }, android.content.IntentFilter("com.fde.x11.TASK_DESCRIPTION_UPDATED"),
                android.content.Context.RECEIVER_EXPORTED)
        } catch (t: Throwable) {
            Log.e(TAG, "register task description receiver failed: $t")
        }
        // Android 17: desktop mode keeps multiple tasks RESUMED, so onTaskStackChanged may not
        // fire when focus switches between visible windows. onTaskFocusChanged is the reliable
        // signal for updating the dock selection.
        taskFocusListener = object : TaskStackListener() {
            override fun onTaskFocusChanged(taskId: Int, focused: Boolean) {
                Log.d(TAG, "onTaskFocusChanged taskId=$taskId focused=$focused")
                mainHandler.post { appstateListener.updateDockAppLocked() }
            }

            override fun onTaskMovedToBack(taskInfo: RunningTaskInfo?) {
                Log.d(TAG, "onTaskMovedToBack taskId=${taskInfo?.taskId}")
                mainHandler.post { appstateListener.updateDockAppLocked() }
            }
        }
    }

    fun providePersistApps() : MutableList<TaskInfo>{
        val apps: MutableList<TaskInfo> = ArrayList()
        val persistApps = SPUtils.getPersistDockApp()
        val allTask = TaskInfo(persistApps[0], "Apps")
        allTask.icon = context.resources.getDrawable(R.drawable.icon_menu)
        allTask.action = ACTION_DOCK_OVERVIEW
        allTask.dockType = DOCK_TYPE_PERSISIT
        apps.add(allTask)
        for (app in persistApps){
            val info = generateTaskInfo(app, DOCK_TYPE_PERSISIT)
            if(info != null){
                apps.add(info)
            }
        }
        val list = apps.toMutableSet().toList()
        persistDockApps.clear()
        persistDockApps.addAll(list)
        Log.d(TAG, "providePersistApps() called $persistDockApps")
        return persistDockApps
    }

    private fun generateX11TaskInfo(packageName : String, type: Int): TaskInfo? {
        val names = packageName.split("#")
        // key/program 保持原样：dock 现有条目、SP 持久化、isLinux() 判定都依赖
        // "FDE::TITLE::" 前缀，改 key 会导致条目全部对不上（图标全丢）。
        // 只把前缀去掉后用于匹配应用列表（终端/浏览器的标题不是应用名）。
        val matchLabel = names[1].removePrefix("FDE::TITLE::")
        val task = TaskInfo(packageName, names[1])
        val overviewAppData = updater.getOverviewAppData()
        val appData = overviewAppData.firstOrNull{
            it.fileName?.contains(matchLabel) ?: false
        }
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.EMPTY, "application/vnd.desktop")
        intent.putExtra("openParams", "${appData?.linuxInfo?.name}###${appData?.linuxInfo?.path}" )
        task.launchIntent = intent
        task.componentName = intent.component
        task.dockType = type
        task.icon = appData?.icon
        task.iconPath = appData?.iconPath
        task.platformType = PLATFORM_TYPE_X11
        Log.d(TAG, "generateX11TaskInfo() returned: $task")
        return task
    }

    private fun generateTaskInfo(packageName: String, type: Int): TaskInfo? {
        if(!packageName.contains("#")){
            return generatAndroidTaskInfo(packageName, type)
        }else{
            return generateX11TaskInfo(packageName, type)
        }
    }

    private fun generatAndroidTaskInfo(packageName: String, type: Int): TaskInfo? {
        try {
            val packageInfo =
                packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            val appIcon = packageManager.getApplicationIcon(packageName)
            val appName = packageInfo.applicationInfo?.let {
                packageManager.getApplicationLabel(it).toString()
            } ?: "Unknown App"
            val task = TaskInfo(packageName, appName)
            task.icon = appIcon
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                val action = launchIntent.action
                task.action = action
                val componentName = launchIntent.component
                task.componentName = componentName
                task.dockType = type
                task.launchIntent = launchIntent
                task.platformType = PLATFORM_TYPE_ANDROID
            }
            Log.d(TAG, "generatAndroidTaskInfo: ${task}")
            return task
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "provideSysApps() e:$e")
        }
        return null
    }

    fun getRunningTaskInfoPackageName(runningTaskInfo: RunningTaskInfo): String? {
        return if (runningTaskInfo.baseActivity == null) {
            null
        } else {
            runningTaskInfo.baseActivity!!.packageName
        }
    }

    /** 返回属于同一个 dock 图标（同一个包）的所有运行中窗口，焦点窗口排在前面。 */
    fun getRunningTaskInfosFor(packageName: String): MutableList<RunningTaskInfo> {
        val result: MutableList<RunningTaskInfo> = ArrayList()
        activityManager.getRunningTasks(MAX_RUNNING_TASKS)?.forEach { info ->
            var pkg = getRunningTaskInfoPackageName(info) ?: return@forEach
            if (Utils.isX11App(pkg, info.topActivity)) {
                val label = info.taskDescription?.label ?: return@forEach
                pkg = "$pkg#$label"
            }
            if (TextUtils.equals(pkg, packageName)) {
                result.add(info)
            }
        }
        result.sortByDescending { it.isFocused }
        return result
    }

    fun shouldIgnoreTopTask(componentName: ComponentName?): Boolean {
        if (componentName == null) {
            return true
        }
        val packageName = componentName.packageName
        if ("android" == packageName) {
            return true
        }
        if (isSpecialLauncher(packageName)) {
            return true
        }
        if (context != null && packageName.startsWith(context.packageName)) {
            return true
        }
        if (isLauncher(context, componentName)) {
            return true
        }
        if (packageName.startsWith("com.android.systemui")) {
            return true
        }
        return false
    }

    @VisibleForTesting
    fun isLauncher(context: Context,componentName: ComponentName?,): Boolean {
        if (componentName == null) {
            return false
        }
        val packageName = componentName.packageName
        if(TextUtils.equals(packageName, "com.android.launcher3")){
            return true
        }
        val className = componentName.className
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        val resolveInfos = context.packageManager.queryIntentActivities(intent, 0)
        for (resolveInfo in resolveInfos) {
            if (resolveInfo?.activityInfo == null) {
                continue
            }
            val activityInfo = resolveInfo.activityInfo
            if (packageName == activityInfo.packageName && className == activityInfo.name) {
                return true
            }
        }
        return false
    }

    private fun isSpecialLauncher(packageName: String?): Boolean {
        if ("com.farmerbb.taskbar" == packageName) {
            return true
        }
        if ("com.teslacoilsw.launcher" == packageName) {
            return true
        }
        return "ch.deletescape.lawnchair.plah" == packageName
    }



    private fun topTask(runningTaskInfo: RunningTaskInfo, isTop: Boolean = true) {
        Log.d(TAG, "topTask() called with: runningTaskInfo = ${runningTaskInfo.baseActivity?.packageName}, isTop = $isTop")
        if (((runningTaskInfo.baseIntent.flags and 0x00800000) == 0x00800000)
            && !("com.iflytek.inputmethod".equals(runningTaskInfo.baseActivity?.packageName))) {
            Log.d(TAG, "FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS")
            return
        }
        var packageName = getRunningTaskInfoPackageName(runningTaskInfo)
        if(packageName == null){
            Log.d(TAG, "Can't get package name")
            return
        }
        if(isLauncher(context, runningTaskInfo.topActivity)){
            Log.d(TAG, "isLauncher")
//            updater.setTop(null, false, isTop)
            return
        }

        if (shouldIgnoreTopTask(runningTaskInfo.topActivity)) {
            Log.d(TAG, "topTask shouldIgnoreTopTask ")
//            updater.setTop(null, false)
            return
        }
        var taskInfo: TaskInfo ?= null
        var needAdd  = false
        if(Utils.isX11App(packageName, runningTaskInfo.topActivity)){
            val label = runningTaskInfo.taskDescription?.label ?: return
            packageName = "$packageName#$label"
            Log.d(TAG, "isX11Platform packageName$packageName")
        }

        if (isPersistApp(packageName)){
            taskInfo = getTaskInfoFromPersist(packageName)
            Log.d(TAG, "getTaskInfoFromPersist $taskInfo")
        } else if (isActiveApp(packageName)){
            taskInfo = getTaskInfoFromActive(packageName)
            Log.d(TAG, "getTaskInfoFromActive $taskInfo")
        } else {
            taskInfo = generateTaskInfo(packageName, DOCK_TYPE_NORMAL)
            if (taskInfo != null) {
                activeDockApps.add(taskInfo)
                needAdd = true
            }
            Log.d(TAG, "generateTaskInfoFromTopTask $taskInfo")
        }
        taskInfo?.runningTaskInfo = runningTaskInfo
//        val taskInfo = TaskInfo(packageName, packageName)
        taskInfo?.id = runningTaskInfo.taskId
        if(isTop){
            taskInfo?.setState(STATE_TOP)
        }else {
            taskInfo?.setState(STATE_RUNNING)
        }
        Log.d(TAG, "topTask: ${taskInfo}")
        taskInfo?.setBaseActivityComponentName(runningTaskInfo.baseActivity)
        taskInfo?.setRealActivityComponentName(runningTaskInfo.topActivity)
        val userHandles = userManager.userProfiles
        for (userHandle in userHandles) {
            val infoList = launchApps.getActivityList(packageName, userHandle)
            if(runningTaskInfo.taskDescription != null
                && runningTaskInfo.taskDescription!!.label != null
                && (runningTaskInfo.taskDescription!!.label.contains("Fusion")
                        || runningTaskInfo.taskDescription!!.label.contains("FDE")||taskInfo!!.isLinux())
            ){
                // 没有设置 _NET_WM_ICON 时 taskDescription.icon 为 null。
                // 直接 BitmapDrawable(null) 会得到一个"非空但画不出内容"的 Drawable，
                // dock 上就是一张空图；而且下面 icon == null 的兜底也不会生效，
                // 因为此时 icon 已经不是 null 了。
                // 这里对 Linux 窗口退回到应用自带的默认图标。
                val descIcon = runningTaskInfo.taskDescription!!.icon
                Log.d("DockIconDbg", "topTask pkg=$packageName taskDescIcon=" + descIcon
                        + " oldIcon=" + taskInfo?.icon + " iconPath=" + taskInfo?.iconPath)
                taskInfo?.icon = if (descIcon != null) {
                    // 有真实窗口图标时，清掉 generateX11TaskInfo() 里"按窗口标题匹配
                    // 应用列表"得到的 iconPath —— DockAppAdapter 会优先用 iconPath，
                    // 不清掉的话 dock 显示的永远是旧图标/错图标。
                    taskInfo?.iconPath = null
                    BitmapDrawable(descIcon)
                } else {
                    // taskDescription.icon 可能会在后续轮询里变回 null（X 侧图标只在
                    // 启动时带过来一次）。这时不能把它覆盖成默认图，否则热启动的窗口
                    // 图标会被抹掉 —— 保留上一次已知的好图标。
                    taskInfo?.icon ?: context?.getDrawable(R.drawable.linux_x11)
                }
            } else if (taskInfo?.icon == null && infoList.size > 0 && infoList[0] != null) {
                taskInfo?.icon = infoList[0]!!.getIcon(0)
                break
            }
        }
        var icon = taskInfo?.icon
        icon = if (icon == null && context != null)  context.getDrawable(R.mipmap.default_icon_round) else icon
        taskInfo?.icon = icon
        Log.d(TAG, "icon: ${taskInfo?.icon}")
        // app.icon 只在创建/持久化时写入一次；topTask 更新的是临时对象，dock 列表里
        // 缓存的 TaskInfo 还留着旧图标，adapter 刷新时拿到的是旧值。
        // 这里把同一图标回写到缓存条目，保证下次 onBindViewHolder 用的是最新图标。
        val pkg = taskInfo?.packageName
        if (pkg != null) {
            activeDockApps.firstOrNull { TextUtils.equals(it.packageName, pkg) }?.icon = icon
            persistDockApps.firstOrNull { TextUtils.equals(it.packageName, pkg) }?.icon = icon
        }
        updater.setTop(taskInfo, needAdd, isTop)
        Log.d("DockIconDbg", "setTop pkg=${taskInfo?.packageName} icon=${taskInfo?.icon}"
                + " cachedActive=" + activeDockApps.firstOrNull {
                    TextUtils.equals(it.packageName, taskInfo?.packageName)
                }?.icon)
    }

    private fun isActiveApp(packageName: String): Boolean {
        activeDockApps.forEach{ app->
            if(TextUtils.equals(app.packageName, packageName)){
                return true
            }
        }
        return false
    }

    private fun getTaskInfoFromPersist(packageName: String):TaskInfo? {
        Log.d(TAG, "getTaskInfoFromPersist() called with: packageName = $packageName")
        persistDockApps.forEach{ app->
            if(TextUtils.equals(app.packageName, packageName)){
                mayFillTaskInfo(app)
                return app
            }
        }
        return null
    }

    private fun mayFillTaskInfo(app: TaskInfo) {
        Log.d(TAG, "mayFillTaskInfo app = $app")
        val packageName = app.packageName
        if(packageName.contains("#")){
            val names = packageName.split("#")
            val overviewAppData = updater.getOverviewAppData()
            val appData = overviewAppData.firstOrNull{
                it.packageName?.equals(packageName) ?: false
            }
            if(appData == null){
                return
            }
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.EMPTY, "application/vnd.desktop")
            intent.putExtra("openParams", "${appData?.linuxInfo?.name}###${appData?.linuxInfo?.path}" )
            app.launchIntent = intent
            app.componentName = intent.component
            val linuxInfo = appData.linuxInfo
            app.linuxInfo = linuxInfo
            app.icon = appData.icon
            app.platformType = PLATFORM_TYPE_X11
            Log.d(TAG, "mayFillTaskInfo get x11app :$app")
        } else if(!packageName.equals("com.android.allapp")){
            try {
                val packageInfo =
                    packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                val appIcon = packageManager.getApplicationIcon(packageName)
                val appName = packageInfo.applicationInfo?.let {
                    packageManager.getApplicationLabel(it).toString()
                } ?: "Unknown App"
                app.icon = appIcon
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    val action = launchIntent.action
                    app.action = action
                    val componentName = launchIntent.component
                    app.componentName = componentName
                    app.launchIntent = launchIntent
                    app.platformType = PLATFORM_TYPE_ANDROID
                }
            } catch (e: PackageManager.NameNotFoundException){
            }
        }
        Log.d(TAG, "mayFillTaskInfo finish: $app")
    }

    private fun getTaskInfoFromActive(packageName: String):TaskInfo? {
        Log.d(TAG, "getTaskInfoFromActive: ")
        activeDockApps.forEach{ app->
            if(TextUtils.equals(app.packageName, packageName)){
                mayFillTaskInfo(app)
                return app
            }
        }
        return null
    }

    private fun isPersistApp(packageName: String): Boolean {
        Log.d(TAG, "isPersistApp: $packageName $persistDockApps ")
        if(persistDockApps.isEmpty()){
            providePersistApps()
        }
        persistDockApps.forEach{ app->
            if(TextUtils.equals(app.packageName, packageName)){
                return true
            }
        }
        return false
    }


    fun registerTaskStackListener(){
        if (taskStackListenerRegistered) {
            appstateListener.updateDockAppLocked()
            return
        }
        TC_WRAPPER.registerTaskStackListener(appstateListener)
        ActivityTaskManager.getInstance().registerTaskStackListener(taskFocusListener)
        taskStackListenerRegistered = true
        appstateListener.updateDockAppLocked()
    }

    fun unregisterTaskStackListener(){
        if (!taskStackListenerRegistered) {
            return
        }
        TC_WRAPPER.unregisterTaskStackListener(appstateListener)
        ActivityTaskManager.getInstance().unregisterTaskStackListener(taskFocusListener)
        taskStackListenerRegistered = false
    }

    fun getPersistSize(): Int {
        return persistDockApps.size
    }

    fun movePersistItem(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= persistDockApps.size || to >= persistDockApps.size || from == to) {
            return
        }
        val item = persistDockApps.removeAt(from)
        persistDockApps.add(to, item)
    }

    fun savePersistOrder() {
        SPUtils.updatePersistDockApp(persistDockApps)
        notifyDockStateChanged()
    }

    fun getActiveSize(): Int {
        return activeDockApps.size
    }

    fun pin(packageName: String) {
        val taskInfo = TaskInfo(packageName, packageName)
        Log.d(TAG, "pin() called with: taskInfo = $taskInfo")
        pin(taskInfo)
        val apps: MutableList<TaskInfo> = ArrayList()
        apps.addAll(persistDockApps)
        apps.addAll(activeDockApps)
        updater.notifyDockAapp(apps)
    }

    fun unpin(packageName: String) {
        val taskInfo = TaskInfo(packageName, packageName)
        unpin(taskInfo)
    }

    fun pin(taskInfo: TaskInfo) {
        Log.d(TAG, "pin() called with: taskInfo = $taskInfo")
        taskInfo.dockType = DOCK_TYPE_PERSISIT
        activeDockApps.removeIf { info->
            if(TextUtils.equals(info.packageName, taskInfo.packageName)){
                taskInfo.setState(info.getState())
                true
            } else {
                false
            }
        }
        mayFillTaskInfo(taskInfo)
        persistDockApps.add(taskInfo)
        SPUtils.updatePersistDockApp(persistDockApps)
        val apps: MutableList<TaskInfo> = ArrayList()
        apps.addAll(persistDockApps)
        apps.addAll(activeDockApps)
        updater.notifyDockAapp(apps)
        notifyDockStateChanged()
    }

    fun unpin(taskInfo: TaskInfo) {
        taskInfo.dockType = DOCK_TYPE_NORMAL
        if(taskInfo.isRunning()){
            activeDockApps.add(taskInfo)
        }
        persistDockApps.removeIf {
                info-> TextUtils.equals(info.packageName, taskInfo.packageName)
        }
        SPUtils.updatePersistDockApp(persistDockApps)
        val apps: MutableList<TaskInfo> = ArrayList()
        apps.addAll(persistDockApps)
        apps.addAll(activeDockApps)
        updater.notifyDockAapp(apps)
        notifyDockStateChanged()
    }

    fun isPersistDockApp(packageName: String): Boolean {
        persistDockApps.forEach {info ->
            if(TextUtils.equals(info.packageName, packageName)){return true}}
        return false
    }

    fun mayFillPersistTaskInfo() {
        persistDockApps.forEach {
            mayFillTaskInfo(it)
        }
        val apps: MutableList<TaskInfo> = ArrayList()
        apps.addAll(persistDockApps)
        apps.addAll(activeDockApps)
        Log.d(TAG, "mayFillPersistTaskInfo() called $persistDockApps")
        updater.notifyDockAapp(apps)
    }

    fun updateUninstall(packageName: String) {
        persistDockApps.removeIf { info-> TextUtils.equals(info.packageName, packageName) }
        val apps: MutableList<TaskInfo> = ArrayList()
        apps.addAll(persistDockApps)
        apps.addAll(activeDockApps)
        updater.notifyDockAapp(apps)
        notifyDockStateChanged()
    }

    /**
     * Each display owns a DockAppsProvider but pin state is global. Notify all taskbar
     * instances so a pin/unpin/reorder made on one display is reflected on the others.
     */
    private fun notifyDockStateChanged() {
        try {
            val intent = Intent(ACTION_DOCK_STATE_CHANGED).setPackage(context.packageName)
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "notifyDockStateChanged failed", t)
        }
    }


    /**
     * 把 dock 上已经不在运行的图标状态复位（包级对账，不依赖 TaskInfo.id）。
     *
     * dock 的 id 只在 topTask() 中写入，而 providePersistApps()/initApps() 会重建一批新实例，
     * id 可能长期为 0；此时 removeTask(taskId) 的 id 匹配不成立，状态会停在 RUNNING/TOP，
     * 图标残留“打开中”下划线（issue #507）。同包多窗口只要还有一个窗口在运行就保留下划线。
     */
    private fun resetDockAppsNotIn(runningTasks: List<RunningTaskInfo>): Boolean {
        val runningPackages = HashSet<String>()
        runningTasks.forEach { info ->
            val pkg = getRunningTaskInfoPackageName(info) ?: return@forEach
            runningPackages.add(pkg)
            if (Utils.isX11App(pkg, info.topActivity)) {
                // Linux/X11 应用在 dock 里的包名是 "pkg#label"，同时保留基础包名以兼容旧数据。
                info.taskDescription?.label?.let { runningPackages.add("$pkg#$it") }
            }
        }
        var changed = false
        persistDockApps.forEach { app ->
            if (app.isRunning() && app.packageName !in runningPackages) {
                app.runningTaskInfo = null
                app.finshTask()
                changed = true
            }
        }
        val activeSize = activeDockApps.size
        activeDockApps.removeIf { app -> app.packageName !in runningPackages }
        if (activeDockApps.size != activeSize) {
            changed = true
        }
        return changed
    }

    inner class AppStateListener(private val updater: DockTaskViewUpdater) : TaskStackChangeListener {

        private val TAG: String = "AppStateListener"

        init {
            updateDockAppLocked()
        }

        override fun onTaskCreated(
            taskId: Int,
            componentName: ComponentName?,
        ) {
            super.onTaskCreated(taskId, componentName)
            logByTaskid("onTaskCreated", taskId)
            updateDockAppLocked()
        }

        override fun onTaskMovedToFront(taskId: Int) {
            super.onTaskMovedToFront(taskId)
            logByTaskid("onTaskMovedToFront", taskId)
            updateDockAppLocked()
        }

        override fun onTaskMovedToFront(taskInfo: RunningTaskInfo) {
            super.onTaskMovedToFront(taskInfo)
            logByTaskid("onTaskMovedToFront", taskInfo.taskId)
            updateDockAppLocked()
        }

        override fun onTaskStackChanged() {
            super.onTaskStackChanged()
//            val info = AM_WRAPPER.getRunningTask(false)
//            info?.let { topTask(it) }
            updateDockAppLocked()
        }

        fun updateDockAppLocked() {
            val runningTasks = activityManager.getRunningTasks(MAX_RUNNING_TASKS) ?: return
            // 同一包可能有多个 task 共用同一个 dock 图标，先处理非焦点窗口、最后处理焦点窗口，
            // 否则后处理的非焦点窗口会把共享 TaskInfo 的 TOP 状态覆盖成 RUNNING。
            runningTasks.filter { !it.isFocused }.forEach { topTask(it, false) }
            runningTasks.filter { it.isFocused }.forEach { topTask(it, true) }
            // 反向对账：已经没有任何运行窗口的 dock 图标必须复位，不依赖 TaskInfo.id（issue #507）。
            if (resetDockAppsNotIn(runningTasks)) {
                val apps: MutableList<TaskInfo> = ArrayList()
                apps.addAll(persistDockApps)
                apps.addAll(activeDockApps)
                updater.refreshDockRunningStates(apps)
            }
        }

        override fun onTaskRemoved(taskId: Int) {
            super.onTaskRemoved(taskId)
            logByTaskid("onTaskRemoved", taskId)
            activeDockApps.removeIf {taskInfo: TaskInfo -> taskInfo.id == taskId}
            // Persisted (pinned) icons are not in activeDockApps, so clear their stale
            // RunningTaskInfo snapshot here. Otherwise a later click can try to bring a dead
            // task to front and the app never launches.
            persistDockApps.forEach { taskInfo ->
                if (taskInfo.id == taskId) {
                    taskInfo.runningTaskInfo = null
                    taskInfo.finshTask()
                }
            }
            updater.removeTask(taskId)
            onTaskStackChanged()
        }

        fun logByTaskid(event: String, taskid: Int) {
            Log.d(TAG, "event = $event, taskid = $taskid")
            activityManager?.getRunningTasks(MAX_RUNNING_TASKS)?.forEach {
                Log.d(
                    TAG, "foreach: runningtask taskId:${it.taskId} " +
                            "topActivity:${it.topActivity}"
                )
            }
        }
    }

    interface DockTaskViewUpdater{
        fun removeTask(taskId: Int)
        fun setTop(info: TaskInfo?, needAdd: Boolean, isTop: Boolean)
        fun notifyDockAapp(taskInfo: MutableList<TaskInfo>)
        /** 状态被反向对账复位后强制刷新 dock，避免图标残留打开态指示（issue #507）。 */
        fun refreshDockRunningStates(taskInfo: MutableList<TaskInfo>)
        fun getDockAapp():MutableList<TaskInfo>
        fun getOverviewAppData():MutableList<AppData>
    }
}


