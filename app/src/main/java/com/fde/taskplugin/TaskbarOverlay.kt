package com.fde.taskplugin

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.util.SparseArray
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.cardview.widget.CardView
import com.android.systemui.plugins.TaskbarPlugin
import com.android.systemui.plugins.annotations.Requires
import com.fde.taskplugin.provider.AllAppsProvider
import com.fde.taskplugin.provider.DockAppsProvider.Companion.ACTION_DOCK_STATE_CHANGED
import com.fde.taskplugin.receiver.UninstallReceiver
import com.fde.taskplugin.utils.HostDesktopMode
import com.fde.taskplugin.utils.SPUtils
import com.fde.taskplugin.utils.ScreenSizeUtils
import com.fde.taskplugin.utils.Utils
import com.fde.taskplugin.view.DockAppsLayout
import kotlinx.coroutines.Runnable

/**
 * A minimal custom taskbar plugin that replaces the default launcher taskbar content
 * with a plugin-provided layout.
 *
 * <p>FDE multi-display: the Launcher host calls [setup] once per display and on every taskbar
 * recreation. This plugin keeps one [TaskbarInstance] per display, but all instances read the
 * same global task list. The taskbar on each display therefore shows all windows; launching a
 * new app targets the display that owns the clicked taskbar.
 */
@Requires(target = TaskbarPlugin::class, version = TaskbarPlugin.VERSION)
class TaskbarOverlay : TaskbarPlugin, UninstallReceiver.AppUninstallListener {

    companion object {
        /** FDE 设置-显示-字体大小对应 Settings.System 的 key（0.85 / 1.0 / 1.15 / 1.3 / 1.5 / 1.8 / 2.0） */
        private const val KEY_FONT_SCALE = "font_scale"

        /** FDE 设置-显示-显示大小（densityDpi）对应 Settings.Secure 的 key */
        private const val KEY_DISPLAY_DENSITY_FORCED = "display_density_forced"

        /** FDE 任务栏 dock 缩放对应 Settings.System 的 key（0.5 / 0.75 / 1.0 / 1.5 / 2.0） */
        private const val KEY_DOCK_SCALE = "dock_scale"

        private const val KEY_TRASH_STORAGE = "trash_storage"

        private const val DEFAULT_DOCK_SCALE = 1.0f
        private const val MIN_DOCK_SCALE = 0.5f
        private const val MAX_DOCK_SCALE = 2.0f
        private const val CONFIG_CHANGE_DEBOUNCE_MS = 300L
    }

    private val TAG: String? = "TaskbarOverlay"
    private var pluginContext: Context? = null
    private var hostContext: Context? = null

    /** displayId -> taskbar instance. One taskbar window exists per display. */
    private val taskbarInstances = SparseArray<TaskbarInstance>()
    private val classLoader = TaskbarOverlay::class.java.classLoader
    private var receiver: BroadcastReceiver? = null
    var resolver: ContentResolver? = null
    private var dockStateReceiver: BroadcastReceiver? = null

    // 系统 size/density/字体缩放变化的监听与去抖（全局共享，通知所有 display 实例）
    private val configHandler = Handler(Looper.getMainLooper())
    private var configObserver: ContentObserver? = null
    private var trashObserver: ContentObserver? = null
    private var configChangeReceiver: BroadcastReceiver? = null
    private val configChangeRunnable = Runnable { onDisplayConfigChanged() }

    override fun onCreate(hostContext: Context, pluginContext: Context) {
        this.pluginContext = pluginContext
        this.hostContext = hostContext
        SPUtils.pluginContext = hostContext
        GlobalSystemUIContext.setContext(hostContext)
        // 必须用宿主 launcher 的 application context：SystemUiProxy 单例依赖
        // LauncherApplication.appComponent，插件自己的 context 没有 application。
        HostDesktopMode.init(hostContext)
        Utils.getLinuxRootFileName(hostContext)
        registerDisplayConfigListeners()
        registerAll()
    }

    /**
     * Host entry point. Launcher calls this once for every display and again whenever it
     * recreates a taskbar (density/font-scale/display changes). The same display therefore
     * reuses its [TaskbarInstance] and only rebinds the new root view.
     */
    override fun setup(taskbarRoot: ViewGroup) {
        if (pluginContext == null) {
            return
        }
        val displayId = resolveDisplayId(taskbarRoot)
        val instance = taskbarInstances.get(displayId)
            ?: TaskbarInstance(displayId).also { taskbarInstances.put(displayId, it) }
        instance.bind(taskbarRoot)
    }

    /** Host is tearing down all taskbars: release every display instance and its popups. */
    override fun teardown() {
        destroyAllInstances()
    }

    private fun destroyAllInstances() {
        for (i in taskbarInstances.size() - 1 downTo 0) {
            taskbarInstances.valueAt(i).destroy()
        }
        taskbarInstances.clear()
    }

    private fun registerAll() {
        resolver = hostContext!!.contentResolver
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        hostContext!!.registerReceiver(closeSystemDialogsReceiver, filter, RECEIVER_EXPORTED)

        // Every display has its own DockAppsProvider. Refresh all of them when one taskbar
        // pins/unpins/reorders an app so all taskbars stay in sync.
        dockStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_DOCK_STATE_CHANGED) {
                    return
                }
                for (i in 0 until taskbarInstances.size()) {
                    taskbarInstances.valueAt(i).refreshDockState()
                }
            }
        }
        hostContext!!.registerReceiver(
            dockStateReceiver,
            IntentFilter(ACTION_DOCK_STATE_CHANGED),
            RECEIVER_NOT_EXPORTED,
        )

        registPackageUpdate()
    }

    private val closeSystemDialogsReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                Log.d(TAG, "onReceive() called with: context = $context, intent = $intent")
                if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS != intent.action) {
                    return
                }
                for (i in 0 until taskbarInstances.size()) {
                    taskbarInstances.valueAt(i).dismissPopups()
                }
            }
        }

    private fun registPackageUpdate() {
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED)
        filter.addDataScheme("package")
        receiver = UninstallReceiver(this)
        pluginContext?.registerReceiver(receiver, filter)
    }

    override fun onUninstall(packageName: String) {
        for (i in 0 until taskbarInstances.size()) {
            taskbarInstances.valueAt(i).onUninstall(packageName)
        }
    }

    override fun onInstall(packageName: String?) {
    }

    /** setup() 传入的是某个 display 的 taskbar 根视图，这里取它所属的 displayId。 */
    private fun resolveDisplayId(root: View): Int {
        root.display?.let { return it.displayId }
        return try {
            root.context.display?.displayId ?: Display.DEFAULT_DISPLAY
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "resolveDisplayId failed, assume default display", e)
            Display.DEFAULT_DISPLAY
        }
    }

    private fun readDockScale(): Float {
        val context = pluginContext ?: hostContext ?: return DEFAULT_DOCK_SCALE
        return try {
            Settings.System.getFloat(context.contentResolver, KEY_DOCK_SCALE, DEFAULT_DOCK_SCALE)
                .coerceIn(MIN_DOCK_SCALE, MAX_DOCK_SCALE)
        } catch (e: Exception) {
            Log.w(TAG, "readDockScale failed: ${e.message}")
            DEFAULT_DOCK_SCALE
        }
    }

    /**
     * 监听系统“显示大小”（density）、“字体大小”（font_scale）以及 FDE dock 缩放（dock_scale）。
     * 设置 App 中调整后，配置会通过 ContentObserver / ACTION_CONFIGURATION_CHANGED 通知到这里。
     */
    private fun registerDisplayConfigListeners() {
        val context = pluginContext ?: return
        if (configObserver == null) {
            val observer = object : ContentObserver(configHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    Log.d(TAG, "display config changed: $uri")
                    scheduleDisplayConfigChanged()
                }
            }
            val resolver = context.contentResolver
            resolver.registerContentObserver(
                Settings.System.getUriFor(KEY_FONT_SCALE), false, observer)
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(KEY_DISPLAY_DENSITY_FORCED), false, observer)
            resolver.registerContentObserver(
                Settings.System.getUriFor(KEY_DOCK_SCALE), false, observer)
            configObserver = observer
        }
        // 回收站占用变化单独监听：只刷新回收站图标，不走整条 dock 的重建路径。
        registerTrashStorageListener()
        if (configChangeReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (Intent.ACTION_CONFIGURATION_CHANGED == intent?.action) {
                        Log.d(TAG, "ACTION_CONFIGURATION_CHANGED")
                        scheduleDisplayConfigChanged()
                    }
                }
            }
            context.registerReceiver(
                receiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED))
            configChangeReceiver = receiver
        }
    }

    /**
     * 监听回收站占用（trash_storage）变化：清空回收站后只刷新回收站这一个
     * ITEM（[DockAppsLayout.refreshTrashItem]），而不是整个列表都重建。
     */
    private fun registerTrashStorageListener() {
        val context = pluginContext ?: return
        if (trashObserver == null) {
            val observer = object : ContentObserver(configHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    Log.d(TAG, "trash storage changed: $uri")
                    // Refresh the trash item on every dock; each display has its own
                    // DockAppsLayout instance.
                    for (i in 0 until taskbarInstances.size()) {
                        taskbarInstances.valueAt(i).refreshTrashItem()
                    }
                }
            }
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(KEY_TRASH_STORAGE), false, observer)
            trashObserver = observer
        }
    }

    private fun scheduleDisplayConfigChanged() {
        configHandler.removeCallbacks(configChangeRunnable)
        configHandler.postDelayed(configChangeRunnable, CONFIG_CHANGE_DEBOUNCE_MS)
    }

    private fun onDisplayConfigChanged() {
        for (i in 0 until taskbarInstances.size()) {
            taskbarInstances.valueAt(i).onDisplayConfigChanged()
        }
    }

    override fun onDestroy() {
        configHandler.removeCallbacksAndMessages(null)
        configObserver?.let {
            try {
                pluginContext?.contentResolver?.unregisterContentObserver(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterContentObserver failed: ${e.message}")
            }
        }
        configObserver = null
        trashObserver?.let {
            try {
                pluginContext?.contentResolver?.unregisterContentObserver(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterContentObserver failed: ${e.message}")
            }
        }
        trashObserver = null
        configChangeReceiver?.let {
            try {
                pluginContext?.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterReceiver failed: ${e.message}")
            }
        }
        configChangeReceiver = null
        try {
            hostContext?.unregisterReceiver(closeSystemDialogsReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "unregister closeSystemDialogsReceiver failed: ${e.message}")
        }
        dockStateReceiver?.let {
            try {
                hostContext?.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregister dockStateReceiver failed: ${e.message}")
            }
        }
        dockStateReceiver = null
        receiver?.let {
            try {
                pluginContext?.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregister package receiver failed: ${e.message}")
            }
        }
        receiver = null
        destroyAllInstances()
        pluginContext = null
    }

    /** One taskbar window/instance for one display. */
    private inner class TaskbarInstance(private val displayId: Int) {
        private var root: ViewGroup? = null
        private var displayContext: Context? = null
        private var inflater: LayoutInflater? = null
        private var dockAppsGroup: ViewGroup? = null
        private var dockAppsLayout: DockAppsLayout? = null
        private var overviewProvider: AllAppsProvider? = null
        private var dockScaleFactor = DEFAULT_DOCK_SCALE
        private var lastDensityDpi = -1
        private var lastFontScale = -1f

        /**
         * Rebind this display's dock to the host's new taskbar root. The root can change on
         * every taskbar recreation while the display instance stays alive, so always detach
         * from the previous parent before adding the dock to the new one.
         */
        fun bind(taskbarRoot: ViewGroup) {
            val context = createDisplayContext(taskbarRoot)
            if (context == null) {
                Log.w(TAG, "bind: no context for display $displayId")
                return
            }
            detachDock()
            root = taskbarRoot
            displayContext = context
            inflater = createInflater(context)
            taskbarRoot.removeAllViews()
            ensureDock()
            updateNaviDock()
        }

        /**
         * Build a display-specific context for this taskbar. The host may call setup() before
         * the root is attached, in which case fall back to the plugin context and let a later
         * rebind use the real display context.
         */
        private fun createDisplayContext(taskbarRoot: ViewGroup): Context? {
            val plugin = pluginContext ?: return null
            return try {
                val display = taskbarRoot.display ?: taskbarRoot.context.display
                if (display != null) {
                    // createDisplayContext() delegates to the base application context and loses
                    // PluginContextWrapper's class loader. Keep the display-adjusted resources
                    // but restore the plugin class loader, otherwise nested layouts inflated by
                    // child views (e.g. DockAppAdapter#onCreateViewHolder) cannot resolve
                    // com.fde.taskplugin.view.* classes and crash with ClassNotFoundException.
                    val displayContext = plugin.createDisplayContext(display)
                    DisplayContextWrapper(displayContext, plugin.classLoader)
                } else {
                    // Root not attached yet. Fall back to the plugin context; a later setup()
                    // recreation will rebind with the real display context.
                    plugin
                }
            } catch (t: Throwable) {
                Log.w(TAG, "createDisplayContext failed for display $displayId", t)
                plugin
            }
        }

        /**
         * Display-adjusted context that keeps the plugin class loader and a plugin-aware
         * LayoutInflater. ContextWrapper would otherwise expose the base application context's
         * empty class loader for nested inflater calls.
         */
        private inner class DisplayContextWrapper(
            base: Context,
            private val pluginClassLoader: ClassLoader,
        ) : ContextWrapper(base) {
            private val pluginInflater: LayoutInflater by lazy {
                LayoutInflater.from(base).cloneInContext(this).also { inflater ->
                    inflater.factory2 = object : LayoutInflater.Factory2 {
                        override fun onCreateView(
                            parent: View?,
                            name: String,
                            context: Context,
                            attrs: AttributeSet,
                        ): View? = createCustomView(name, context, attrs)

                        override fun onCreateView(
                            name: String,
                            context: Context,
                            attrs: AttributeSet,
                        ): View? = createCustomView(name, context, attrs)
                    }
                }
            }

            override fun getClassLoader(): ClassLoader = pluginClassLoader

            override fun getSystemService(name: String): Any? {
                if (Context.LAYOUT_INFLATER_SERVICE == name) {
                    return pluginInflater
                }
                return super.getSystemService(name)
            }
        }

        private fun createInflater(context: Context): LayoutInflater {
            val inflater = LayoutInflater.from(context).cloneInContext(context)
            inflater.factory2 = object : LayoutInflater.Factory2 {
                override fun onCreateView(
                    parent: View?,
                    name: String,
                    context: Context,
                    attrs: AttributeSet,
                ): View? = createCustomView(name, context, attrs)

                override fun onCreateView(
                    name: String,
                    context: Context,
                    attrs: AttributeSet,
                ): View? = createCustomView(name, context, attrs)
            }
            return inflater
        }

        private fun createCustomView(
            name: String,
            context: Context,
            attrs: AttributeSet,
        ): View? {
            return try {
                if (name.contains(context.packageName)) {
                    val clazz = Class.forName(name, true, classLoader)
                    clazz.getConstructor(Context::class.java, AttributeSet::class.java)
                        .newInstance(context, attrs) as View
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "createCustomView failed: $name", e)
                null
            }
        }

        /**
         * Inflate this display's dock once and rebuild it when density/font scale changes.
         * Every display gets its own DockAppsProvider so pin/unpin state stays global while
         * launch contexts remain display-local.
         */
        private fun ensureDock() {
            val context = displayContext ?: return
            ScreenSizeUtils.getInstance(context).refresh(context)
            val metrics = context.resources.displayMetrics
            val fontScale = context.resources.configuration.fontScale
            val needNew = dockAppsGroup == null ||
                metrics.densityDpi != lastDensityDpi ||
                fontScale != lastFontScale
            if (!needNew) {
                dockScaleFactor = readDockScale()
                return
            }

            dockAppsLayout?.dismissAllPopups()
            dockAppsLayout?.onDestroy()
            detachDock()

            val layoutInflater = inflater ?: return
            val group = layoutInflater.inflate(R.layout.dock_apps_layout, null) as ViewGroup
            group.defaultFocusHighlightEnabled = false
            val layout = group.findViewById<DockAppsLayout>(R.id.apps_rv) ?: return
            layout.reloadActivityManager(context)
            val provider = AllAppsProvider(context, layout)
            layout.overviewProvider = provider

            dockAppsGroup = group
            dockAppsLayout = layout
            overviewProvider = provider
            lastDensityDpi = metrics.densityDpi
            lastFontScale = fontScale
            dockScaleFactor = readDockScale()
        }

        /** Attach the prepared dock to this display's taskbar root and start its data flow. */
        private fun updateNaviDock() {
            val taskbarRoot = root ?: return
            detachDock()
            taskbarRoot.removeAllViews()
            val dock = dockAppsGroup ?: return

            val dockParams = if (taskbarRoot is FrameLayout) {
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM,
                )
            } else {
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            taskbarRoot.addView(dock, dockParams)
            taskbarRoot.visibility = View.VISIBLE
            dockAppsLayout?.navi = taskbarRoot
            dockAppsLayout?.initApps(dockScaleFactor)

            dockAppsGroup?.setOnClickListener {
                taskbarRoot.background = null
                dockAppsLayout?.updateNaviWindowFlags()
            }
            taskbarRoot.postDelayed({
                dockAppsLayout?.updateNaviWindowFlags()
            }, 1000)
            if (Utils.getProperty("fde.systemui.blurlevel", 0) == 0) {
                Utils.setBackgroundBlurRadius(
                    dockAppsGroup?.findViewById(R.id.root_blur), 70, 16f)
            } else {
                val bgViewGroup = dockAppsGroup?.findViewById<ViewGroup>(R.id.paren_fl)
                bgViewGroup?.setBackgroundResource(R.drawable.round_rect_16dp_no_blur)
                val cardView = dockAppsGroup?.findViewById<CardView>(R.id.root_blur)
                cardView?.setCardBackgroundColor(Color.parseColor("#aaF2F6FA"))
            }
        }

        /** Refresh only the trash item after its storage state changes. */
        fun refreshTrashItem() {
            dockAppsLayout?.refreshTrashItem()
        }

        fun refreshDockState() {
            dockScaleFactor = readDockScale()
            dockAppsLayout?.initApps(dockScaleFactor)
        }

        fun onUninstall(packageName: String) {
            dockAppsLayout?.onUninstall(packageName)
        }

        fun dismissPopups() {
            dockAppsLayout?.dismissAllPopups()
        }

        fun onDisplayConfigChanged() {
            val context = displayContext ?: return
            ScreenSizeUtils.getInstance(context).refresh(context)
            dockScaleFactor = readDockScale()
            ensureDock()
            updateNaviDock()
        }

        private fun detachDock() {
            val dock = dockAppsGroup ?: return
            (dock.parent as? ViewGroup)?.removeView(dock)
        }

        fun destroy() {
            Log.d(TAG, "destroy taskbar display=$displayId")
            dockAppsLayout?.dismissAllPopups()
            dockAppsLayout?.onDestroy()
            detachDock()
            root?.removeAllViews()
            dockAppsGroup = null
            dockAppsLayout = null
            overviewProvider = null
            root = null
            displayContext = null
            inflater = null
        }
    }
}
