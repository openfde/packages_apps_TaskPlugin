package com.fde.taskplugin.utils

import android.content.Context
import android.util.Log
import java.lang.reflect.Method

/**
 * 访问 launcher 进程中自带的 ActivityManagerWrapper。
 *
 * 插件 APK 里打包的 SystemUISharedLib 比较旧（getTaskThumbnail 依赖 Android 17 已删除的
 * IActivityTaskManager.getTaskSnapshot），而 launcher 进程里已经有一份随系统编译的最新实现
 * （最近任务列表就是用它取缩略图的）。这里通过插件 ClassLoader 的父链找到宿主 ClassLoader，
 * 直接使用宿主里的实现，避免版本不一致。
 */
object HostActivityManager {

    private const val TAG = "HostActivityManager"
    private const val HOST_PACKAGE = "com.android.launcher3"
    private const val WRAPPER_CLASS = "com.android.systemui.shared.system.ActivityManagerWrapper"

    private val INT_TYPE: Class<*> = Int::class.javaPrimitiveType!!
    private val BOOLEAN_TYPE: Class<*> = Boolean::class.javaPrimitiveType!!

    private var initialized = false
    private var wrapper: Any? = null
    private var takeThumbnailMethod: Method? = null
    private var getThumbnailMethod: Method? = null
    private var removeTaskMethod: Method? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) {
            return
        }
        initialized = true
        try {
            val pluginLoader = HostActivityManager::class.java.classLoader
            val filter = pluginLoader?.parent
            Log.d(
                TAG,
                "init pluginLoader=$pluginLoader filter=${filter?.javaClass?.name}" +
                        " hostLoader=${filter?.javaClass?.classLoader}"
            )
            val hostClass = loadHostWrapperClass(context) ?: run {
                Log.w(TAG, "host ActivityManagerWrapper class not found")
                return
            }
            wrapper = hostClass.getMethod("getInstance").invoke(null)
            takeThumbnailMethod = safeMethod(hostClass, "takeTaskThumbnail", INT_TYPE)
            getThumbnailMethod = safeMethod(
                hostClass,
                "getTaskThumbnail",
                INT_TYPE,
                BOOLEAN_TYPE
            )
            removeTaskMethod = safeMethod(hostClass, "removeTask", INT_TYPE)
            Log.d(
                TAG,
                "host ActivityManagerWrapper ready class=${hostClass.classLoader}" +
                        " instance=${wrapper?.javaClass?.name} take=${takeThumbnailMethod != null}" +
                        " get=${getThumbnailMethod != null} remove=${removeTaskMethod != null}"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "init failed", t)
        }
    }

    private fun loadHostWrapperClass(context: Context): Class<*>? {
        // 插件 ClassLoader -> ClassLoaderFilter（宿主类） -> launcher ClassLoader
        try {
            val filter = HostActivityManager::class.java.classLoader?.parent
            val hostLoader = filter?.javaClass?.classLoader
            if (hostLoader != null) {
                return hostLoader.loadClass(WRAPPER_CLASS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "load host wrapper via plugin parent failed", t)
        }
        // 兜底：通过 launcher 包上下文拿到宿主 ClassLoader
        return try {
            context.createPackageContext(HOST_PACKAGE, 0).classLoader?.loadClass(WRAPPER_CLASS)
        } catch (t: Throwable) {
            Log.w(TAG, "load host wrapper via package context failed", t)
            null
        }
    }

    private fun safeMethod(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
        return try {
            clazz.getMethod(name, *params)
        } catch (t: Throwable) {
            Log.w(TAG, "method $name not found", t)
            null
        }
    }

    val isAvailable: Boolean
        get() = wrapper != null

    fun cachedThumbnail(taskId: Int): Any? {
        val method = getThumbnailMethod ?: return null
        return try {
            method.invoke(wrapper, taskId, false)
        } catch (t: Throwable) {
            Log.w(TAG, "cachedThumbnail failed", t)
            null
        }
    }

    fun takeThumbnail(taskId: Int): Any? {
        val method = takeThumbnailMethod ?: return null
        return try {
            method.invoke(wrapper, taskId)
        } catch (t: Throwable) {
            Log.w(TAG, "takeThumbnail failed", t)
            null
        }
    }

    fun removeTask(taskId: Int): Boolean {
        val method = removeTaskMethod ?: return false
        return try {
            method.invoke(wrapper, taskId)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "removeTask failed", t)
            false
        }
    }
}
