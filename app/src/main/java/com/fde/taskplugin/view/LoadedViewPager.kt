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
    // 一次手势最多翻一页：滚轮合成的竖直滑动会被拆成多帧 MOVE，
    // 原判据（连续两帧 X 相同即翻页）会在同一手势内反复命中，导致一次最小滚动连翻多页。
    private var mPageTurnedInGesture = false
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
                mPageTurnedInGesture = false
            }
            MotionEvent.ACTION_MOVE -> {
                if(blockScroll){
                    lastInterceptX = -1f
                    lastInterceptY = -1f
                    blockScroll = false
                } else if(lastInterceptX == -1f){
                    lastInterceptX = ev!!.x
                    lastInterceptY = ev!!.y
                } else {
                    val moveDy = ev!!.y - lastInterceptY
                    val moveDx = ev!!.x - lastInterceptX
                    if(!mPageTurnedInGesture
                        && Math.abs(moveDx) < ViewConfiguration.get(context).scaledTouchSlop
                        && Math.abs(moveDy) >= mTriggerDistance){
                        lastInterceptX = -1f
                        if(moveDy < 0){
                            goToNextPage()
                        } else {
                            goToPreviousPage()
                        }
                        mPageTurnedInGesture = true
                        blockScroll = true
                        lastInterceptY = -1f
                    }
                }

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
                mPageTurnedInGesture = false
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
                mPageTurnedInGesture = false
            }

            MotionEvent.ACTION_MOVE ->  {
                val dx = (event.x - mStartX).toDouble().toFloat()
                val dy = (event.y - mStartY).toDouble().toFloat()
//                mIsHorizontalScroll = dx > dy && dx > ViewConfiguration.get(context).scaledTouchSlop
                Log.d(TAG, "handleTouchEvent: dx:$dx dy:$dy $blockScroll")
                if(!blockScroll && !mPageTurnedInGesture && Math.abs(dy) >= mTriggerDistance) {
                    blockScroll = true
                    mPageTurnedInGesture = true
                    if(dy < 0){
                        goToNextPage()
                    } else {
                        goToPreviousPage()
                    }
                }
            }

            MotionEvent.ACTION_UP ->

//                if (mIsHorizontalScroll) {
            {
                mIsTouching = false
                val dx = event.x - mStartX
                Log.d(TAG, "handleTouchEvent() called with: dx = $dx")
                if (abs(dx.toDouble()) > mTriggerDistance) {
//                    val current = currentItem
//                    val next = if (dx > 0) current - 1 else current + 1
//
//                    val toIndex = max(0.0, min(next.toDouble(), (adapter!!.count - 1).toDouble()))
//                        .toInt()
//                    setCurrentItem(
//                        toIndex, true
//                    )
//                    return true // 已处理滑动
//                }
                }

            }
        }
        return super.onTouchEvent(event)
    }




}