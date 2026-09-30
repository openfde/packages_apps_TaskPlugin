package com.fde.taskplugin

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.cardview.widget.CardView
import androidx.core.view.postDelayed
import com.android.systemui.plugins.TaskbarPlugin
import com.android.systemui.plugins.annotations.Requires
import com.fde.taskplugin.provider.AllAppsProvider
import com.fde.taskplugin.receiver.UninstallReceiver
import com.fde.taskplugin.utils.HostDesktopMode
import com.fde.taskplugin.utils.SPUtils
import com.fde.taskplugin.utils.ScreenSizeUtils
import com.fde.taskplugin.utils.Utils
import com.fde.taskplugin.utils.ViewTreePrinter
import com.fde.taskplugin.view.DockAppsLayout
import com.fde.taskplugin.view.DockIconView.Companion.RELEASE_DURATION
import kotlinx.coroutines.Runnable

/**
 * A minimal custom taskbar plugin that replaces the default launcher taskbar content
 * with a plugin-provided layout.
 */
@Requires(target = TaskbarPlugin::class, version = TaskbarPlugin.VERSION)
class TaskbarOverlay : TaskbarPlugin , UninstallReceiver.AppUninstallListener{

    companion object {
        /** FDE 设置-显示-字体大小对应 Settings.System 的 key（0.85 / 1.0 / 1.15 / 1.3 / 1.5 / 1.8 / 2.0） */
        private const val KEY_FONT_SCALE = "font_scale"

        /** FDE 设置-显示-显示大小（densityDpi）对应 Settings.Secure 的 key */
        private const val KEY_DISPLAY_DENSITY_FORCED = "display_density_forced"

        /** FDE 任务栏 dock 缩放对应 Settings.System 的 key（0.5 / 0.75 / 1.0 / 1.5 / 2.0） */
        private const val KEY_DOCK_SCALE = "dock_scale"

        private const val DEFAULT_DOCK_SCALE = 1.0f
        private const val MIN_DOCK_SCALE = 0.5f
        private const val MAX_DOCK_SCALE = 2.0f
        private const val CONFIG_CHANGE_DEBOUNCE_MS = 300L
    }

    private val TAG: String? = "TaskbarOverlay"
    private var pluginContext: Context? = null
    private var hostContext: Context? = null

    private var dockAppsGroup: ViewGroup? = null
    private var dockAppsLayout: DockAppsLayout? = null
    private var navi: ViewGroup ?= null
    private var mDockScaleFactor = DEFAULT_DOCK_SCALE
    private val classLoader = TaskbarOverlay::class.java.classLoader
    private var overviewProvider: AllAppsProvider?= null
    var receiver :BroadcastReceiver?= null
    var resolver: ContentResolver? = null

    // 系统 size/density/字体缩放变化的监听与去抖
    private val configHandler = Handler(Looper.getMainLooper())
    private var configObserver: ContentObserver? = null
    private var configChangeReceiver: BroadcastReceiver? = null
    private val configChangeRunnable = Runnable { onDisplayConfigChanged() }
    private var lastDensityDpi = -1
    private var lastFontScale = -1f


    override fun onCreate(hostContext: Context, pluginContext: Context) {
        this.pluginContext = pluginContext
        this.hostContext = hostContext
        SPUtils.pluginContext = hostContext
        GlobalSystemUIContext.setContext(hostContext)
        // 必须用宿主 launcher 的 application context：SystemUiProxy 单例依赖
        // LauncherApplication.appComponent，插件自己的 context 没有 application。
        HostDesktopMode.init(hostContext)
        loadCustomViewsWithInflater(pluginContext!!)
        mDockScaleFactor = readDockScale()
        ScreenSizeUtils.getInstance(pluginContext!!).let {
            it.refresh(pluginContext!!)
            lastDensityDpi = it.densityDpi
            lastFontScale = it.fontScale
        }

        dockAppsGroup = initializeDockAppsGroup(this.pluginContext, dockAppsGroup)
        dockAppsLayout = dockAppsGroup?.findViewById(R.id.apps_rv)
        dockAppsGroup?.defaultFocusHighlightEnabled = false
        dockAppsLayout!!.reloadActivityManager(pluginContext)
        overviewProvider = AllAppsProvider(pluginContext!!, dockAppsLayout)
        dockAppsLayout?.overviewProvider = overviewProvider

        Utils.getLinuxRootFileName(hostContext!!)
        registerDisplayConfigListeners()
        registerAll()

    }

    private fun registerAll() {
        resolver = hostContext!!.contentResolver
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        hostContext!!.registerReceiver(closeSystemDialogsReceiver, filter, RECEIVER_EXPORTED)
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
                if(dockAppsLayout != null){
                    dockAppsLayout?.dimissWindow()
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
        dockAppsLayout?.onUninstall(packageName)
    }

    override fun onInstall(packageName: String?) {

    }

    private fun loadCustomViewsWithInflater(context: Context) {
        if (context == null) {
            throw IllegalArgumentException("Context cannot be null")
        }
        val inflater = LayoutInflater.from(context)
        inflater.factory2 = object : LayoutInflater.Factory2 {
            override fun onCreateView(
                parent: View?,
                name: String,
                context: Context,
                attrs: AttributeSet
            ): View? {
                return createCustomView(name, context, attrs)
            }

            override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
                return createCustomView(name, context, attrs)
            }

            private fun createCustomView(name: String, context: Context, attrs: AttributeSet): View? {
                try {
                    if(name.contains(context.packageName)){
                        val clazz =
                            Class.forName(name, true, classLoader)
                        return clazz.getConstructor(
                            Context::class.java,
                            AttributeSet::class.java
                        )
                            .newInstance(context, attrs) as View
                    }
                } catch (e: Exception) {
                    return null
                }
                return null
            }
        }
    }


    override fun setup(taskbarRoot: ViewGroup) {
        pluginContext ?: return
        // taskbarRoot 现在是整个 taskbar 窗口根（全宽、70dp），插件直接往里面放 dock。
        navi = taskbarRoot
        navi!!.removeAllViews()
        navi!!.visibility = View.VISIBLE
        Log.d(TAG, "setup() called with: taskbarRoot = $taskbarRoot")
        navi!!.postDelayed(100) {
            ViewTreePrinter.printViewTree(navi!!)
        }
        // 宿主在 density/字体缩放等配置变化后可能重建 taskbar 并再次回调 setup，
        // 这里对比快照，配置变化过就先重建 dock，让 dp/sp 按新配置解析。
        val screen = ScreenSizeUtils.getInstance(pluginContext!!)
        screen.refresh(pluginContext!!)
        if (screen.densityDpi != lastDensityDpi || screen.fontScale != lastFontScale) {
            lastDensityDpi = screen.densityDpi
            lastFontScale = screen.fontScale
            reinflateDock()
        }
        updateNaviDock()
    }

    private fun updateNaviDock() {
        navi!!.removeAllViews()
        dockAppsLayout?.onDestroy()
        mDockScaleFactor = readDockScale()
        pluginContext?.let { ScreenSizeUtils.getInstance(it).refresh(it) }

        // 窗口宽度/高度由 DockAppsLayout.updateNaviWidth 按当前 dock 缩放计算，
        // 左右空白区域透传给下层应用；dock 内容以 WRAP_CONTENT 宽度、水平居中放进去。
        val dockParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        )
        navi?.addView(dockAppsGroup, dockParams)
        dockAppsLayout?.navi = navi
        dockAppsLayout?.initApps(mDockScaleFactor)
        dockAppsGroup?.setOnClickListener{
//            traverseAndPrint(navi!!, 0)
            navi?.background = null
            dockAppsLayout?.updateNaviWindowFlags()
        }
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed({ dockAppsLayout?.updateNaviWindowFlags() }, 5000)
        if(Utils.getProperty("fde.systemui.blurlevel", 0) == 0){
//            Log.d(TAG, "updateNaviDock() called blur")
            Utils.setBackgroundBlurRadius(dockAppsGroup?.findViewById(R.id.root_blur), 70, 16f)
        } else {
//            Log.d(TAG, "updateNaviDock() called not blur")
            val bgViewGroup = dockAppsGroup?.findViewById<ViewGroup>(R.id.paren_fl)
            bgViewGroup?.setBackgroundResource(R.drawable.round_rect_16dp_no_blur)
            val cardView = dockAppsGroup?.findViewById<CardView>(R.id.root_blur)
            cardView?.setCardBackgroundColor(Color.parseColor("#aaF2F6FA"))
        }
    }

    @SuppressLint("InflateParams")
    private fun initializeDockAppsGroup(
        context: Context?,
        dockAppsGroup: ViewGroup?,
    ): ViewGroup {
        return dockAppsGroup
            ?: LayoutInflater.from(context).inflate(R.layout.dock_apps_layout, null) as ViewGroup
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

    private fun scheduleDisplayConfigChanged() {
        configHandler.removeCallbacks(configChangeRunnable)
        configHandler.postDelayed(configChangeRunnable, CONFIG_CHANGE_DEBOUNCE_MS)
    }

    /**
     * 运行时适配：重新读取显示配置 -> 重建 dock（让 dp/sp 按新 density/fontScale 重新解析）
     * -> 刷新 dock 窗口宽高。
     */
    private fun onDisplayConfigChanged() {
        val context = pluginContext ?: return
        val screen = ScreenSizeUtils.getInstance(context)
        screen.refresh(context)
        mDockScaleFactor = readDockScale()
        lastDensityDpi = screen.densityDpi
        lastFontScale = screen.fontScale
        Log.d(TAG, "onDisplayConfigChanged dockScale=$mDockScaleFactor" +
                " screen=${screen.screenWidth}x${screen.screenHeight}" +
                " density=${screen.density} densityDpi=${screen.densityDpi}" +
                " fontScale=${screen.fontScale}")
        reinflateDock()
        updateNaviDock()
    }

    /** 重新 inflate dock，使 dp/sp 尺寸按当前 density/fontScale 重新解析，并清掉旧弹窗。 */
    private fun reinflateDock() {
        val context = pluginContext ?: return
        dockAppsLayout?.dismissAllPopups()
        dockAppsLayout?.onDestroy()
        navi?.removeAllViews()
        dockAppsGroup = LayoutInflater.from(context)
            .inflate(R.layout.dock_apps_layout, null) as ViewGroup
        dockAppsGroup?.defaultFocusHighlightEnabled = false
        dockAppsLayout = dockAppsGroup?.findViewById(R.id.apps_rv)
        dockAppsLayout?.reloadActivityManager(context)
        dockAppsLayout?.let { layout ->
            overviewProvider = AllAppsProvider(context, layout)
            layout.overviewProvider = overviewProvider
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
        configChangeReceiver?.let {
            try {
                pluginContext?.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterReceiver failed: ${e.message}")
            }
        }
        configChangeReceiver = null
        pluginContext = null
    }
}
