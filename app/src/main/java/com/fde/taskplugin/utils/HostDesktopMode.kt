package com.fde.taskplugin.utils

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.window.RemoteTransition
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 访问 launcher 宿主进程里的桌面模式接口（Shell IDesktopMode）。
 *
 * Android 17 桌面模式下，任务最小化/恢复必须走 Shell 的 IDesktopMode：
 * - 最小化: minimizeDesktopApp(taskId) -> 任务被 reparent 到 MinimizedDesk_N 并带最小化动画
 * - 恢复:   showDesktopApp(taskId)     -> 从 MinimizedDesk_N 反最小化并置顶
 *
 * 框架没有公开 API 能触发这两个操作；插件通过插件 ClassLoader 的父链找到宿主
 * Launcher 的 SystemUiProxy（与 HostActivityManager 同一套路）。
 */
object HostDesktopMode {

    private const val TAG = "HostDesktopMode"
    private const val HOST_PACKAGE = "com.android.launcher3"
    private const val PROXY_CLASS = "com.android.quickstep.SystemUiProxy"
    private const val REASON_CLASS =
        "com.android.wm.shell.shared.desktopmode.DesktopTaskToFrontReason"
    private const val DESKTOP_MODE_CLASS = "com.android.wm.shell.desktopmode.api.IDesktopMode"

    // 兼容旧 Launcher APK：它自带一份 IDesktopMode 接口，可能没有新增的动画目标方法。
    // 这种情况下改用广播把数据发给 Shell，不需要重新编译 Launcher3。
    private const val ACTION_UPDATE_DOCK_ICON_BOUNDS =
        "com.android.wm.shell.action.UPDATE_DOCK_ICON_BOUNDS"
    private const val ACTION_SET_MINIMIZE_TARGET =
        "com.android.wm.shell.action.SET_MINIMIZE_TARGET"
    private const val EXTRA_DISPLAY_ID = "display_id"
    private const val EXTRA_PACKAGE_NAMES = "package_names"
    private const val EXTRA_PACKAGE_BOUNDS = "package_bounds"
    private const val EXTRA_TASK_IDS = "task_ids"
    private const val EXTRA_TASK_BOUNDS = "task_bounds"
    private const val EXTRA_TASK_ID = "task_id"
    private const val EXTRA_TARGET_BOUNDS = "target_bounds"

    private var initialized = false
    private var broadcastContext: Context? = null
    private var proxyInstance: Any? = null
    private var desktopModeField: Field? = null
    private var desktopModeInstance: Any? = null
    private var showDesktopAppMethod: Method? = null
    private var minimizeDesktopAppMethod: Method? = null
    private var updateDockIconBoundsMethod: Method? = null
    private var setMinimizeAnimationTargetMethod: Method? = null
    private var toFrontReason: Any? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) {
            return
        }
        // 广播回退不依赖 Launcher 的 IDesktopMode 接口，先保存 context。
        broadcastContext = resolveAppContext(context)

        val hostLoader = loadHostClassLoader(context) ?: run {
            Log.w(TAG, "host class loader not found")
            return
        }

        // 1) SystemUiProxy.INSTANCE.get(context)：拿到宿主代理实例
        val proxyClass = try {
            hostLoader.loadClass(PROXY_CLASS).also { clazz ->
                val singleton = clazz.getField("INSTANCE").get(null)
                val proxy = singleton.javaClass.getMethod("get", Context::class.java)
                    .invoke(singleton, resolveAppContext(context))
                proxyInstance = proxy
            }
        } catch (t: Throwable) {
            Log.w(TAG, "resolve SystemUiProxy failed", t)
            return
        }

        // 2) desktopMode 字段（SystemUI 连接后才非空，方法查找不依赖它的值）
        try {
            desktopModeField = proxyClass.getDeclaredField("desktopMode").apply {
                isAccessible = true
            }
            desktopModeInstance = desktopModeField?.get(proxyInstance)
            Log.d(TAG, "desktopMode interface = ${desktopModeField?.type}")
        } catch (t: Throwable) {
            Log.w(TAG, "resolve desktopMode field failed", t)
        }

        // 3) showDesktopApp(taskId, transition, reason)
        try {
            val reasonClass = hostLoader.loadClass(REASON_CLASS)
            toFrontReason = reasonClass.getMethod("valueOf", String::class.java)
                .invoke(null, "TASKBAR_TAP")
            showDesktopAppMethod = proxyClass.getMethod(
                "showDesktopApp",
                Int::class.javaPrimitiveType!!,
                RemoteTransition::class.java,
                reasonClass
            )
        } catch (t: Throwable) {
            Log.w(TAG, "resolve showDesktopApp failed", t)
        }

        // 4) IDesktopMode.minimizeDesktopApp(taskId) 及最小化动画目标接口
        try {
            // 优先使用 desktopMode 字段的运行时类型：与宿主实际使用的接口完全一致，
            // 避免 hostLoader.loadClass(类名) 因类未被打包/类名不一致抛 ClassNotFoundException。
            val desktopModeClass = desktopModeField?.type
                ?: hostLoader.loadClass(DESKTOP_MODE_CLASS)
            minimizeDesktopAppMethod = desktopModeClass.getMethod(
                "minimizeDesktopApp",
                Int::class.javaPrimitiveType!!
            )
            // 5) 把 dock 图标位置同步给 Shell，供标题栏最小化动画使用。
            try {
                updateDockIconBoundsMethod = desktopModeClass.getMethod(
                    "updateDockIconBounds",
                    Int::class.javaPrimitiveType!!,
                    Bundle::class.java,
                    Bundle::class.java
                )
            } catch (t: Throwable) {
                Log.i(TAG, "updateDockIconBounds unavailable, use broadcast fallback")
            }
            // 6) dock 图标点击最小化时，补偿一个精确的一次性目标（避免布局缓存有偏差）。
            try {
                setMinimizeAnimationTargetMethod = desktopModeClass.getMethod(
                    "setMinimizeAnimationTarget",
                    Int::class.javaPrimitiveType!!,
                    Rect::class.java
                )
            } catch (t: Throwable) {
                Log.i(TAG, "setMinimizeAnimationTarget unavailable, use broadcast fallback")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "resolve minimizeDesktopApp failed, interface=${desktopModeField?.type}", t)
        }

        initialized = showDesktopAppMethod != null || minimizeDesktopAppMethod != null
        Log.d(
            TAG,
            "ready show=${showDesktopAppMethod != null}" +
                    " minimize=${minimizeDesktopAppMethod != null}" +
                    " dockBounds=${updateDockIconBoundsMethod != null}" +
                    " minimizeTarget=${setMinimizeAnimationTargetMethod != null}" +
                    " broadcast=${broadcastContext != null}" +
                    " desktopMode=${desktopModeInstance != null}" +
                    " initialized=$initialized"
        )
    }

    /**
     * SystemUiProxy 单例要求 context 的 applicationContext 是 LauncherApplication，
     * 插件自己的 context 没有 application，这里做一次归一化。
     */
    private fun resolveAppContext(context: Context): Context {
        context.applicationContext?.let { return it }
        return try {
            context.createPackageContext(HOST_PACKAGE, 0).applicationContext ?: context
        } catch (t: Throwable) {
            Log.w(TAG, "resolveAppContext failed", t)
            context
        }
    }

    private fun loadHostClassLoader(context: Context): ClassLoader? {
        // 插件 ClassLoader -> ClassLoaderFilter（宿主类）-> launcher ClassLoader
        try {
            val filter = HostDesktopMode::class.java.classLoader?.parent
            val hostLoader = filter?.javaClass?.classLoader
            if (hostLoader != null) {
                return hostLoader
            }
        } catch (t: Throwable) {
            Log.w(TAG, "load host loader via plugin parent failed", t)
        }
        // 兜底：通过 launcher 包上下文拿到宿主 ClassLoader
        return try {
            context.createPackageContext(HOST_PACKAGE, 0).classLoader
        } catch (t: Throwable) {
            Log.w(TAG, "load host loader via package context failed", t)
            null
        }
    }

    /** SystemUI 可能晚于插件连接，desktopMode 为空时重读一次。 */
    private fun currentDesktopMode(): Any? {
        if (desktopModeInstance != null) {
            return desktopModeInstance
        }
        val proxy = proxyInstance ?: return null
        return try {
            desktopModeField?.get(proxy)?.also { desktopModeInstance = it }
        } catch (t: Throwable) {
            Log.w(TAG, "read desktopMode failed", t)
            null
        }
    }

    val isAvailable: Boolean
        get() = showDesktopAppMethod != null && minimizeDesktopAppMethod != null

    /** 把桌面任务（含 MinimizedDesk 里的）恢复到前台。 */
    fun showTask(taskId: Int): Boolean {
        val method = showDesktopAppMethod ?: return false
        val reason = toFrontReason ?: return false
        val proxy = proxyInstance ?: return false
        if (currentDesktopMode() == null) {
            return false
        }
        return try {
            method.invoke(proxy, taskId, null, reason)
            Log.d(TAG, "showTask taskId=$taskId")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "showTask failed", t)
            false
        }
    }

    /** 与标题栏最小化一致：reparent 到 MinimizedDesk 并播放最小化动画。 */
    fun minimizeTask(taskId: Int): Boolean {
        val method = minimizeDesktopAppMethod ?: return false
        val target = currentDesktopMode() ?: return false
        return try {
            method.invoke(target, taskId)
            Log.d(TAG, "minimizeTask taskId=$taskId")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "minimizeTask failed", t)
            false
        }
    }

    /**
     * 把当前 dock 上各图标的屏幕位置同步给 Shell。
     * [boundsByPackage] 的 key 是包名，[boundsByTaskId] 的 key 是 taskId，
     * value 都是图标在屏幕上的 Rect。Shell 在播放标题栏最小化动画时用它作为
     * “被吸进 dock”的终点。按 taskId 查表可以正确处理同一包名的多个窗口（如 X11）。
     */
    fun updateDockIconBounds(
        displayId: Int,
        boundsByPackage: Bundle,
        boundsByTaskId: Bundle,
    ): Boolean {
        val method = updateDockIconBoundsMethod
        val target = currentDesktopMode()
        if (method != null && target != null) {
            try {
                method.invoke(target, displayId, boundsByPackage, boundsByTaskId)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "updateDockIconBounds AIDL call failed, fallback to broadcast", t)
            }
        }
        return sendDockIconBoundsBroadcast(displayId, boundsByPackage, boundsByTaskId)
    }

    /**
     * 点击 dock 图标最小化时，给 Shell 一个精确的一次性动画目标。
     * 目标按 taskId 生效，并在动画开始后自动失效。
     */
    fun setMinimizeAnimationTarget(taskId: Int, bounds: Rect): Boolean {
        val method = setMinimizeAnimationTargetMethod
        val target = currentDesktopMode()
        if (method != null && target != null) {
            try {
                method.invoke(target, taskId, bounds)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "setMinimizeAnimationTarget AIDL call failed, fallback", t)
            }
        }
        val context = broadcastContext ?: return false
        val intent =
            Intent(ACTION_SET_MINIMIZE_TARGET).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TARGET_BOUNDS, Rect(bounds))
            }
        Log.d(TAG, "broadcast target task=$taskId bounds=$bounds")
        return sendBroadcast(context, intent)
    }

    private fun sendDockIconBoundsBroadcast(
        displayId: Int,
        boundsByPackage: Bundle,
        boundsByTaskId: Bundle,
    ): Boolean {
        val context = broadcastContext ?: return false

        val names = ArrayList<String>()
        val packageBounds = ArrayList<Rect>()
        for (key in boundsByPackage.keySet()) {
            @Suppress("DEPRECATION")
            val rect = boundsByPackage.getParcelable(key) as? Rect ?: continue
            names.add(key)
            packageBounds.add(Rect(rect))
        }
        val taskIds = ArrayList<Int>()
        val taskBounds = ArrayList<Rect>()
        for (key in boundsByTaskId.keySet()) {
            val taskId = key.toIntOrNull() ?: continue
            @Suppress("DEPRECATION")
            val rect = boundsByTaskId.getParcelable(key) as? Rect ?: continue
            taskIds.add(taskId)
            taskBounds.add(Rect(rect))
        }

        val intent =
            Intent(ACTION_UPDATE_DOCK_ICON_BOUNDS).apply {
                putExtra(EXTRA_DISPLAY_ID, displayId)
                putStringArrayListExtra(EXTRA_PACKAGE_NAMES, names)
                putParcelableArrayListExtra(EXTRA_PACKAGE_BOUNDS, packageBounds)
                putIntegerArrayListExtra(EXTRA_TASK_IDS, taskIds)
                putParcelableArrayListExtra(EXTRA_TASK_BOUNDS, taskBounds)
            }
        Log.d(TAG, "broadcast bounds display=$displayId packages=${names.size} tasks=${taskIds.size}")
        return sendBroadcast(context, intent)
    }

    private fun sendBroadcast(context: Context, intent: Intent): Boolean =
        try {
            context.sendBroadcast(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendBroadcast failed: ${intent.action}", t)
            false
        }
}
