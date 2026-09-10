package com.fde.taskplugin.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.android.systemui.shared.system.ActivityManagerWrapper
import java.util.concurrent.Executors

/**
 * 任务缩略图加载器。优先使用 launcher 宿主进程里的 ActivityManagerWrapper（与最近任务同一套
 * 取图逻辑），失败时回退到插件自带 SystemUISharedLib 和 TaskSnapshotManager。
 *
 * 另外提供 startLive/stopLive：预览显示期间低频轮询 takeTaskThumbnail，
 * 对可见任务拿到接近实时的画面（系统侧在 WM 全局锁外截图）。
 */
object TaskThumbnailLoader {

    private const val TAG = "TaskThumbnailLoader"
    private const val MAX_WIDTH = 640
    private const val CACHE_TTL_MS = 2000L
    private const val LIVE_INTERVAL_MS = 200L
    private const val LIVE_MAX_FAILURES = 3

    private val INT_TYPE: Class<*> = Int::class.javaPrimitiveType!!
    private val BOOLEAN_TYPE: Class<*> = Boolean::class.javaPrimitiveType!!

    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = LruCache<Int, CacheEntry>(8)

    private val liveExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TaskThumbnailLive")
    }

    @Volatile
    private var liveToken = 0

    private class CacheEntry(val bitmap: Bitmap, val time: Long)

    /** 实时预览的一帧。bitmap 通常是 HARDWARE 位图，交给 GPU 缩放，不做 CPU 拷贝。 */
    class LiveFrame(val bitmap: Bitmap, val snapshotId: Long)


    fun load(context: Context, taskId: Int, callback: (Bitmap?) -> Unit) {
        if (taskId <= 0) {
            Log.w(TAG, "load invalid taskId=$taskId")
            mainHandler.post { callback(null) }
            return
        }
        val cached = cache.get(taskId)
        if (cached != null && System.currentTimeMillis() - cached.time < CACHE_TTL_MS) {
            Log.d(TAG, "load taskId=$taskId hit cache ${cached.bitmap.width}x${cached.bitmap.height}")
            mainHandler.post { callback(cached.bitmap) }
            return
        }
        Log.d(TAG, "load taskId=$taskId from scratch, hostAvailable=${HostActivityManager.isAvailable}")
        executor.execute {
            HostActivityManager.init(context)
            val bitmap = loadInternal(taskId)
            Log.d(
                TAG,
                "load taskId=$taskId result=${bitmap?.width}x${bitmap?.height}" +
                        " hostAvailable=${HostActivityManager.isAvailable}"
            )
            if (bitmap != null) {
                mainHandler.post { cache.put(taskId, CacheEntry(bitmap, System.currentTimeMillis())) }
            }
            mainHandler.post { callback(bitmap) }
        }
    }

    fun invalidate(taskId: Int) {
        cache.remove(taskId)
    }

    /**
     * 开始对 [taskId] 低频轮询快照，直到 [stopLive] 或任务连续多次不可截取。
     * 仅对可见任务有实时效果；后台任务会取不到帧并自动停止（由调用方保留静态首帧）。
     */
    fun startLive(context: Context, taskId: Int, callback: (LiveFrame) -> Unit) {
        if (taskId <= 0) {
            return
        }
        stopLive()
        val token = ++liveToken
        Log.d(TAG, "startLive taskId=$taskId interval=${LIVE_INTERVAL_MS}ms")
        liveExecutor.execute {
            HostActivityManager.init(context)
            if (!HostActivityManager.isAvailable) {
                Log.w(TAG, "startLive abort: host ActivityManagerWrapper unavailable")
                return@execute
            }
            var failures = 0
            var frames = 0
            while (token == liveToken) {
                val start = SystemClock.uptimeMillis()
                val data = HostActivityManager.takeThumbnail(taskId)
                val bitmap = extractBitmap(data)
                if (bitmap != null) {
                    failures = 0
                    frames++
                    val frame = LiveFrame(bitmap, extractSnapshotId(data))
                    mainHandler.post {
                        if (token == liveToken) {
                            callback(frame)
                        }
                    }
                } else {
                    failures++
                    if (failures >= LIVE_MAX_FAILURES) {
                        Log.w(TAG, "stopLive taskId=$taskId: not capturable (frames=$frames)")
                        break
                    }
                }
                val elapsed = SystemClock.uptimeMillis() - start
                val sleep = LIVE_INTERVAL_MS - elapsed
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            Log.d(TAG, "startLive loop end taskId=$taskId frames=$frames")
        }
    }

    /** 停止实时轮询（下次循环检查时退出，进行中的截图调用无法取消）。 */
    fun stopLive() {
        liveToken++
    }

    private fun extractSnapshotId(data: Any?): Long {
        if (data == null) {
            return 0L
        }
        try {
            (data.javaClass.getMethod("getSnapshotId").invoke(data) as? Long)?.let { return it }
        } catch (_: Throwable) {
        }
        try {
            (data.javaClass.getField("snapshotId").get(data) as? Long)?.let { return it }
        } catch (_: Throwable) {
        }
        return 0L
    }

    private fun loadInternal(taskId: Int): Bitmap? {
        // 1. 宿主 launcher 的 ActivityManagerWrapper（最近任务同款）
        extractBitmap(HostActivityManager.takeThumbnail(taskId))?.let {
            Log.d(TAG, "use host fresh snapshot for taskId=$taskId")
            return scale(it)
        }
        extractBitmap(HostActivityManager.cachedThumbnail(taskId))?.let {
            Log.d(TAG, "use host cached snapshot for taskId=$taskId")
            return scale(it)
        }

        // 2. 插件打包的 SystemUISharedLib（旧版本，getTaskThumbnail 在 Android 17 上可能不可用）
        try {
            val wrapper = ActivityManagerWrapper.getInstance()
            extractBitmap(wrapper.getTaskThumbnail(taskId, false))?.let {
                Log.d(TAG, "use bundled cached snapshot for taskId=$taskId")
                return scale(it)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bundled getTaskThumbnail failed: $t")
        }
        try {
            val method = ActivityManagerWrapper::class.java
                .getMethod("takeTaskThumbnail", INT_TYPE)
            extractBitmap(method.invoke(ActivityManagerWrapper.getInstance(), taskId))?.let {
                Log.d(TAG, "use bundled fresh snapshot for taskId=$taskId")
                return scale(it)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bundled takeTaskThumbnail failed: $t")
        }

        // 3. 直接使用 framework 的 TaskSnapshotManager
        extractBitmap(takeFromSnapshotManager(taskId))?.let {
            Log.d(TAG, "use TaskSnapshotManager snapshot for taskId=$taskId")
            return scale(it)
        }
        Log.w(TAG, "no thumbnail for taskId=$taskId")
        return null
    }

    private fun takeFromSnapshotManager(taskId: Int): Any? {
        return try {
            val clazz = Class.forName("android.window.TaskSnapshotManager")
            val instance = clazz.getMethod("getInstance").invoke(null)
            clazz.getMethod(
                "takeTaskSnapshot",
                INT_TYPE,
                BOOLEAN_TYPE
            ).invoke(instance, taskId, true)
        } catch (t: Throwable) {
            Log.w(TAG, "TaskSnapshotManager failed: $t")
            null
        }
    }

    private fun extractBitmap(data: Any?): Bitmap? {
        if (data == null) {
            return null
        }
        try {
            (data.javaClass.getMethod("getThumbnail").invoke(data) as? Bitmap)?.let { return it }
        } catch (_: Throwable) {
        }
        try {
            (data.javaClass.getField("thumbnail").get(data) as? Bitmap)?.let { return it }
        } catch (_: Throwable) {
        }
        // TaskSnapshot 兜底
        try {
            (data.javaClass.getMethod("wrapToBitmap").invoke(data) as? Bitmap)?.let { return it }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun scale(src: Bitmap): Bitmap? {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) {
            return null
        }
        val targetWidth = minOf(src.width, MAX_WIDTH)
        val targetHeight = (src.height * targetWidth.toFloat() / src.width).toInt().coerceAtLeast(1)
        return try {
            val software = if (src.config == Bitmap.Config.HARDWARE) {
                src.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                src
            }
            val dst = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            Canvas(dst).drawBitmap(
                software,
                null,
                Rect(0, 0, targetWidth, targetHeight),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            if (software !== src) {
                software.recycle()
            }
            dst
        } catch (t: Throwable) {
            Log.w(TAG, "scale thumbnail failed", t)
            null
        }
    }
}
