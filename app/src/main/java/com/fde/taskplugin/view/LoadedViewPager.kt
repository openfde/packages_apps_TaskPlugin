package com.fde.taskplugin.view

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


class LoadedViewPager
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewPager(context, attrs) {

    var overviewWindow: AppOverviewWindow ?= null
    private val DEFAULT_TRIGGER_DISTANCE: Int = 20 // 触发翻页的默认距离（像素）
    private val mTriggerDistance = DEFAULT_TRIGGER_DISTANCE
    // 一个最小滚轮刻度经 hwcomposer 合成后约 60px 的竖直位移
    // （证据：一次最小刻度 Y 由 675 降到 615，共 60px）。
    // 每累计约一个刻度的位移翻一页；位移成倍增大时按比例翻更多页，
    // 而不是“每个手势最多只翻一页”（那会把大距离滚动也限制成一页）。
    private val PAGE_TURN_DISTANCE: Float = 60f
    // 调试开关（openfde/openfde#494）：置 true 时输出专用 tag 的手势轨迹日志，
    // 用于定位“改动未生效”的原因：本拷贝是否在运行、APK 是否真的更新、
    // 位移是否达到阈值、两条触摸路径是否重复或漏处理。
    // 现场抓取：adb logcat -s openfde494:V
    private val DEBUG_GESTURE: Boolean = true
    private val DEBUG_TAG = "openfde494"
    private var mStartX = 0f
    private var mStartY = 0f
    private var mMenuVisibleOnDown = false
    private val MENU_DISMISS_GRACE_MS = 300L
    private var mIsHorizontalScroll = false
    // 本手势累计竖直位移、已翻页数（onInterceptTouchEvent 与 OnTouchListener 共享）
    private var mGestureDy = 0f
    private var mPagesTurnedInGesture = 0
    private var mLastGestureY = 0f
    private var mLastGestureEventTime = 0L
    val TAG = "LoadedViewPager"
    var blockScroll : Boolean = false
    var mIsTouching : Boolean = false

    init {
        setOnTouchListener { v, event -> handleTouchEvent(event) }
    }

    private fun dbg(msg: String) {
        if (DEBUG_GESTURE) {
            Log.d(DEBUG_TAG, "impl=TaskPlugin.LoadedViewPager pid=" + android.os.Process.myPid() + " " + msg)
        }
    }

    private fun goToNextPage() {
        val currentItem = currentItem
        val totalItems = adapter?.count ?: 0 // 使用 Elvis 运算符提供默认值

        dbg("flip next from=" + currentItem + " total=" + totalItems)
        if (currentItem < totalItems - 1) {
            setCurrentItem(currentItem + 1, true)
        }
    }
    // 翻到上一页
    private fun goToPreviousPage() {
        val currentItem = currentItem
        dbg("flip prev from=" + currentItem)
        if (currentItem > 0) {
            setCurrentItem(currentItem - 1, true)
        }
    }

    // 依据累计竖直位移计算目标翻页数：
    // 不足 mTriggerDistance 不翻页；约一个滚轮刻度（PAGE_TURN_DISTANCE）翻 1 页；
    // 位移成倍增大时翻页数同比例增大（滚动越远翻得越多，不再固定为一页）。
    private fun targetPageCount(dy: Float): Int {
        if (abs(dy) < mTriggerDistance) {
            return 0
        }
        val pages = max(1, Math.round(abs(dy) / PAGE_TURN_DISTANCE))
        dbg("targetPageCount dy=" + dy + " -> " + pages + " (min=" + mTriggerDistance + " step=" + PAGE_TURN_DISTANCE + ")")
        return pages
    }

    private fun resetGesture() {
        mGestureDy = 0f
        mPagesTurnedInGesture = 0
        mLastGestureY = 0f
        mLastGestureEventTime = 0L
    }

    // onInterceptTouchEvent 与 OnTouchListener 都会收到同一批 MOVE；
    // 用 eventTime 去重，保证同一段位移只累计、只翻页一次，
    // 避免出现修复前两条路径各自翻页的情况。
    private fun trackGestureMove(ev: MotionEvent) {
        if (ev.eventTime == mLastGestureEventTime) {
            dbg("move skip: duplicate eventTime=" + ev.eventTime)
            return
        }
        mLastGestureEventTime = ev.eventTime
        val step = ev.y - mLastGestureY
        mGestureDy += step
        mLastGestureY = ev.y
        dbg("move action=" + ev.action + " src=0x" + Integer.toHexString(ev.source) + " x=" + ev.x + " y=" + ev.y
            + " step=" + step + " totalDy=" + mGestureDy + " turned=" + mPagesTurnedInGesture
            + " slop=" + ViewConfiguration.get(context).scaledTouchSlop)
        // 横向漂移超过触摸抖动容差时按横向滚动处理，不触发翻页
        if (abs(ev.x - mStartX) > ViewConfiguration.get(context).scaledTouchSlop) {
            dbg("move skip: horizontal drift dx=" + (ev.x - mStartX))
            return
        }
        val target = targetPageCount(mGestureDy)
        while (mPagesTurnedInGesture < target) {
            // 竖直位移为负（手指/合成触点向上滑）→ 下一页
            if (mGestureDy < 0) {
                goToNextPage()
            } else {
                goToPreviousPage()
            }
            mPagesTurnedInGesture++
        }
    }

    private var lastInterceptX : Float = -1f
    private var lastInterceptY : Float = -1f

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        Log.d(TAG, "onInterceptTouchEvent() called with: ev = $ev")
        when (ev!!.action) {
            MotionEvent.ACTION_DOWN -> {
                mIsTouching = true;
                mStartX = ev!!.x
                mStartY = ev!!.y
                // 按下时记录应用列表的右键菜单是否显示/刚刚被点外面关掉：
                // 有菜单时本次点击只关菜单，不退出列表
                val ctx = overviewWindow?.contextWindow
                mMenuVisibleOnDown = ctx != null && (ctx.isShowing() ||
                        (ctx.dismissTime > 0 &&
                                SystemClock.uptimeMillis() - ctx.dismissTime < MENU_DISMISS_GRACE_MS))
                lastInterceptX = -1f
                lastInterceptY = -1f
                resetGesture()
                mLastGestureY = ev!!.y
                dbg("down via onInterceptTouchEvent x=" + ev!!.x + " y=" + ev!!.y
                    + " src=0x" + Integer.toHexString(ev!!.source) + " item=" + currentItem
                    + " count=" + (adapter?.count ?: -1) + " blockScroll=" + blockScroll)
            }
            MotionEvent.ACTION_MOVE -> {
                lastInterceptX = -1f
                lastInterceptY = -1f
                trackGestureMove(ev!!)

                val dx = Math.abs(ev!!.x - mStartX)
                val dy = Math.abs(ev!!.y - mStartY)
                Log.d(TAG, "onInterceptTouchEvent: dx:$dx dy:$dy slop:${ViewConfiguration.get(context).scaledTouchSlop}")
                if (dx > dy && dx > ViewConfiguration.get(context).scaledTouchSlop) {
                    Log.d(TAG, "Intercept move TouchEvent")
                    return true // 拦截横向滑动
                }
            }
            MotionEvent.ACTION_UP -> {
                mIsTouching = false
                if(!mMenuVisibleOnDown && mStartX == ev.x && mStartY == ev.y && ev.source == 0x1002){
                    postDelayed({ overviewWindow?.dismiss() }, 50)
                }
                lastInterceptX = -1f
                lastInterceptY = -1f
                dbg("up via onInterceptTouchEvent totalDy=" + mGestureDy + " turned=" + mPagesTurnedInGesture
                    + " item=" + currentItem)
                resetGesture()
            }
        }

        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent?): Boolean {
        if (!mIsHorizontalScroll) {
            return false; // 不处理纵向滑动
        }
        return super.onTouchEvent(ev)
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        Log.d(TAG, "handleTouchEvent() called with: event = $event")
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                mIsTouching = true
                mStartX = event.x
                mStartY = event.y
                mIsHorizontalScroll = false
                resetGesture()
                mLastGestureY = event.y
                dbg("down via OnTouchListener x=" + event.x + " y=" + event.y
                    + " src=0x" + Integer.toHexString(event.source) + " item=" + currentItem
                    + " count=" + (adapter?.count ?: -1))
            }

            MotionEvent.ACTION_MOVE ->  {
                val dx = (event.x - mStartX).toDouble().toFloat()
                val dy = (event.y - mStartY).toDouble().toFloat()
                Log.d(TAG, "handleTouchEvent: dx:$dx dy:$dy $blockScroll")
                trackGestureMove(event)
            }

            MotionEvent.ACTION_UP ->

//                if (mIsHorizontalScroll) {
            {
                mIsTouching = false
                val dx = event.x - mStartX
                Log.d(TAG, "handleTouchEvent() called with: dx = $dx")
                dbg("up via OnTouchListener totalDy=" + mGestureDy + " turned=" + mPagesTurnedInGesture
                    + " item=" + currentItem)
                resetGesture()
            }
        }
        return super.onTouchEvent(event)
    }




}