package com.fde.taskplugin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
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
import com.fde.taskplugin.utils.HostDesktopMode
import com.fde.taskplugin.utils.SPUtils
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
class TaskbarOverlay : TaskbarPlugin {

    private val TAG: String? = "TaskbarOverlay"
    private var pluginContext: Context? = null
    private var hostContext: Context? = null

    private var dockAppsGroup: ViewGroup? = null
    private var dockAppsLayout: DockAppsLayout? = null
    private var navi: ViewGroup ?= null
    private var mDockScaleFactor = 1.0f
    private val classLoader = TaskbarOverlay::class.java.classLoader
    private var overviewProvider: AllAppsProvider?= null


    override fun onCreate(hostContext: Context, pluginContext: Context) {
        this.pluginContext = pluginContext
        this.hostContext = hostContext
        SPUtils.pluginContext = hostContext
        GlobalSystemUIContext.setContext(hostContext)
        // 必须用宿主 launcher 的 application context：SystemUiProxy 单例依赖
        // LauncherApplication.appComponent，插件自己的 context 没有 application。
        HostDesktopMode.init(hostContext)
        loadCustomViewsWithInflater(pluginContext!!)

        dockAppsGroup = initializeDockAppsGroup(this.pluginContext, dockAppsGroup)
        dockAppsLayout = dockAppsGroup?.findViewById(R.id.apps_rv)
        dockAppsGroup?.defaultFocusHighlightEnabled = false
        dockAppsLayout!!.reloadActivityManager(pluginContext)
        overviewProvider = AllAppsProvider(pluginContext!!, dockAppsLayout)
        dockAppsLayout?.overviewProvider = overviewProvider

        Utils.getLinuxRootFileName(hostContext!!)

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
        updateNaviDock()
    }

    private fun updateNaviDock() {
        navi!!.removeAllViews()
        dockAppsLayout?.onDestroy()
        val height = pluginContext!!.resources.getDimension(R.dimen.dock_height).toInt()

        // 不再改窗口根(navi)自身的 layoutParams，窗口由 launcher 管理为全宽 70dp；
        // dock 以 WRAP_CONTENT 宽度、水平居中放进去即可。
        val dockParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            height,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        )
        navi?.addView(dockAppsGroup, dockParams)
        dockAppsLayout?.initApps(mDockScaleFactor)
        dockAppsLayout?.navi = navi
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


    override fun onDestroy() {
        pluginContext = null
    }
}
