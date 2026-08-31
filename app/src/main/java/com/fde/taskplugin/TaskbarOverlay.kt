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
    private var dockAppsGroup: ViewGroup? = null
    private var dockAppsLayout: DockAppsLayout? = null
    private var navi: ViewGroup ?= null
    private var mDockScaleFactor = 1.0f
    private val classLoader = TaskbarOverlay::class.java.classLoader


    override fun onCreate(hostContext: Context, pluginContext: Context) {
        this.pluginContext = pluginContext
        SPUtils.pluginContext = pluginContext
        loadCustomViewsWithInflater(pluginContext!!)

        dockAppsGroup = initializeDockAppsGroup(this.pluginContext, dockAppsGroup)
        dockAppsLayout = dockAppsGroup?.findViewById(R.id.apps_rv)
        dockAppsGroup?.defaultFocusHighlightEnabled = false
        dockAppsLayout!!.reloadActivityManager(pluginContext)


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
        val ctx = pluginContext ?: return
        val taskbarLayout =
            LayoutInflater.from(ctx).inflate(R.layout.taskbar_plugin_layout, taskbarRoot, true)
        taskbarRoot.visibility = View.VISIBLE
        Log.d(TAG, "setup() called with: taskbarRoot = $taskbarRoot")
        Log.d(TAG, "setup() called with: taskbarRoot = ${taskbarRoot?.parent}")
        Log.d(TAG, "setup() called with: taskbarRoot = ${taskbarRoot?.parent?.parent}")
        navi = taskbarRoot
        navi!!.postDelayed( 100, {
            ViewTreePrinter.printViewTree(navi!!.parent as View)
        })
        updateNaviDock()

    }

    private fun updateNaviDock() {
        val childCount:Int = navi!!.childCount
        navi!!.removeAllViews()
//        for (i in 0 until childCount) {
//            val child = navi?.getChildAt(i)
//            child?.visibility = View.GONE
//        }
        dockAppsLayout?.onDestroy()
        val layoutParams = navi?.layoutParams as FrameLayout.LayoutParams
        layoutParams.width = FrameLayout.LayoutParams.WRAP_CONTENT
        layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
        layoutParams.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        navi?.layoutParams = layoutParams
        val dockParams :FrameLayout.LayoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT)
//        navi?.removeAllViews()
        navi?.addView(dockAppsGroup, dockParams)
        dockAppsLayout?.initApps(mDockScaleFactor)
        dockAppsLayout?.navi = navi
        dockAppsGroup?.setOnClickListener{
//            traverseAndPrint(navi!!, 0)
            navi?.background = null
            dockAppsLayout?.updateNaviWindowFlags()
        }
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                dockAppsLayout?.updateNaviWindowFlags()
            }
        }, 1000)
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
