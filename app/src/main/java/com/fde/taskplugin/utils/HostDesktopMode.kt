package com.fde.taskplugin.utils

import android.content.Context
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

    private var initialized = false
    private var proxyInstance: Any? = null
    private var desktopModeField: Field? = null
    private var desktopModeInstance: Any? = null
    private var showDesktopAppMethod: Method? = null
    private var minimizeDesktopAppMethod: Method? = null
    private var toFrontReason: Any? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) {
            return
        }
        try {
            val hostLoader = loadHostClassLoader(context) ?: run {
                Log.w(TAG, "host class loader not found")
                return
            }
            val proxyClass = hostLoader.loadClass(PROXY_CLASS)
            val singleton = proxyClass.getField("INSTANCE").get(null)
            val proxy = singleton.javaClass.getMethod("get", Context::class.java)
                .invoke(singleton, resolveAppContext(context))
            proxyInstance = proxy
            desktopModeField = proxyClass.getDeclaredField("desktopMode").apply {
                isAccessible = true
            }
            desktopModeInstance = desktopModeField?.get(proxy)

            val reasonClass = hostLoader.loadClass(REASON_CLASS)
            toFrontReason = reasonClass.getMethod("valueOf", String::class.java)
                .invoke(null, "TASKBAR_TAP")
            showDesktopAppMethod = proxyClass.getMethod(
                "showDesktopApp",
                Int::class.javaPrimitiveType!!,
                RemoteTransition::class.java,
                reasonClass
            )
            minimizeDesktopAppMethod = hostLoader.loadClass(DESKTOP_MODE_CLASS).getMethod(
                "minimizeDesktopApp",
                Int::class.javaPrimitiveType!!
            )
            initialized = true
            Log.d(
                TAG,
                "ready show=${showDesktopAppMethod != null}" +
                        " minimize=${minimizeDesktopAppMethod != null}" +
                        " desktopMode=${desktopModeInstance != null}"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "init failed", t)
        }
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
}
