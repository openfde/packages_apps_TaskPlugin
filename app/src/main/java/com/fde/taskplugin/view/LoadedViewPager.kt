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

    private fun goToNextPage() {
        val currentItem = currentItem
        val totalItems = adapter?.count ?: 0 // 使用 Elvis 运算符提供默认值

        if (currentItem < totalItems - 1) {
            setCurrentItem(currentItem + 1, true)
        }
    }
    // 翻到上一页
    private fun goToPreviousPage() {
        val currentItem = currentItem
        if (currentItem > 0) {
            setCurrentItem(currentItem - 1, true)
        }
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
    //
    // 一次滚动（一个按下→抬起的手势）最多只翻一页：鼠标滚轮在桌面会话中会被
    // hwcomposer 合成成一段纯竖直的触摸滑动（实测一个最小刻度约 28 帧 MOVE、
    // 合计约 60px 位移），所以不能用位移大小换算翻页数，否则一次滚动就会翻多页。
    private fun trackGestureMove(ev: MotionEvent) {
        if (ev.eventTime == mLastGestureEventTime) {
            return
        }
        mLastGestureEventTime = ev.eventTime
        mGestureDy += ev.y - mLastGestureY
        mLastGestureY = ev.y
        // 横向漂移超过触摸抖动容差时按横向滚动处理，不触发翻页
        if (abs(ev.x - mStartX) > ViewConfiguration.get(context).scaledTouchSlop) {
            return
        }
        // 本次手势已经翻过一页，或累计竖直位移还没到最小触发距离：不再翻页
        if (mPagesTurnedInGesture > 0 || abs(mGestureDy) < mTriggerDistance) {
            return
        }
        // 竖直位移为负（手指/合成触点向上滑）→ 下一页
        if (mGestureDy < 0) {
            goToNextPage()
        } else {
            goToPreviousPage()
        }
        mPagesTurnedInGesture = 1
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
                resetGesture()
            }
        }
        return super.onTouchEvent(event)
    }




}