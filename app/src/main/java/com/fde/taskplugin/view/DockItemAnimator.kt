package com.fde.taskplugin.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.fde.taskplugin.R

/**
 * dock 图标增删与位移动画：
 * - 新增：图标从下往上弹出（上移 + 淡入，带轻微回弹）
 * - 移除：图标向下滑出并淡出
 * - 位置变化：图标左右平移让位/合拢，带轻微回弹
 */
class DockItemAnimator : SimpleItemAnimator() {

    private class MoveInfo(
        val holder: RecyclerView.ViewHolder,
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
    )

    private class ChangeInfo(
        val oldHolder: RecyclerView.ViewHolder,
        val newHolder: RecyclerView.ViewHolder,
    )

    private val pendingAdds = ArrayList<RecyclerView.ViewHolder>()
    private val pendingRemoves = ArrayList<RecyclerView.ViewHolder>()
    private val pendingMoves = ArrayList<MoveInfo>()
    private val pendingChanges = ArrayList<ChangeInfo>()

    private val addAnimations = ArrayList<RecyclerView.ViewHolder>()
    private val removeAnimations = ArrayList<RecyclerView.ViewHolder>()
    private val moveAnimations = ArrayList<RecyclerView.ViewHolder>()
    private val changeAnimations = ArrayList<RecyclerView.ViewHolder>()

    private val runningAnimators = HashMap<RecyclerView.ViewHolder, Animator>()

    private val addInterpolator = OvershootInterpolator(1.1f)
    private val removeInterpolator = AccelerateInterpolator(1.4f)
    private val moveInterpolator = OvershootInterpolator(0.7f)

    init {
        addDuration = ADD_DURATION
        removeDuration = REMOVE_DURATION
        moveDuration = MOVE_DURATION
        changeDuration = MOVE_DURATION
        supportsChangeAnimations = false
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        resetAnimation(holder)
        val view = holder.itemView
        view.alpha = 0f
        view.translationY = enterOffset(view).toFloat()
        pendingAdds.add(holder)
        return true
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        resetAnimation(holder)
        pendingRemoves.add(holder)
        return true
    }

    override fun animateMove(
        holder: RecyclerView.ViewHolder,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
    ): Boolean {
        val view = holder.itemView
        val startX = fromX + view.translationX.toInt()
        val startY = fromY + view.translationY.toInt()
        resetAnimation(holder)
        val deltaX = toX - startX
        val deltaY = toY - startY
        if (deltaX == 0 && deltaY == 0) {
            dispatchMoveFinished(holder)
            return false
        }
        if (deltaX != 0) {
            view.translationX = (-deltaX).toFloat()
        }
        if (deltaY != 0) {
            view.translationY = (-deltaY).toFloat()
        }
        pendingMoves.add(MoveInfo(holder, startX, startY, toX, toY))
        return true
    }

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder,
        newHolder: RecyclerView.ViewHolder,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
    ): Boolean {
        if (oldHolder === newHolder) {
            return animateMove(oldHolder, fromX, fromY, toX, toY)
        }
        resetAnimation(oldHolder)
        resetAnimation(newHolder)
        pendingChanges.add(ChangeInfo(oldHolder, newHolder))
        return true
    }

    override fun runPendingAnimations() {
        val removesPending = pendingRemoves.isNotEmpty()
        val movesPending = pendingMoves.isNotEmpty()
        val changesPending = pendingChanges.isNotEmpty()
        val addsPending = pendingAdds.isNotEmpty()
        if (!removesPending && !movesPending && !changesPending && !addsPending) {
            return
        }

        if (removesPending) {
            val removes = ArrayList(pendingRemoves)
            pendingRemoves.clear()
            for (holder in removes) {
                removeAnimations.add(holder)
                animateRemoveImpl(holder)
            }
        }

        if (movesPending) {
            val moves = ArrayList(pendingMoves)
            pendingMoves.clear()
            for (move in moves) {
                moveAnimations.add(move.holder)
                animateMoveImpl(move)
            }
        }

        if (changesPending) {
            val changes = ArrayList(pendingChanges)
            pendingChanges.clear()
            for (change in changes) {
                changeAnimations.add(change.oldHolder)
                changeAnimations.add(change.newHolder)
                animateChangeImpl(change)
            }
        }

        if (addsPending) {
            val adds = ArrayList(pendingAdds)
            pendingAdds.clear()
            for (holder in adds) {
                addAnimations.add(holder)
                animateAddImpl(holder)
            }
        }
    }

    private fun animateAddImpl(holder: RecyclerView.ViewHolder) {
        val view = holder.itemView
        val animator = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f),
        )
        animator.duration = addDuration
        animator.interpolator = addInterpolator
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                dispatchAddStarting(holder)
            }

            override fun onAnimationCancel(animation: Animator) {
                resetView(view)
            }

            override fun onAnimationEnd(animation: Animator) {
                runningAnimators.remove(holder)
                dispatchAddFinished(holder)
                addAnimations.remove(holder)
                dispatchFinishedWhenDone()
            }
        })
        runningAnimators[holder] = animator
        animator.start()
    }

    private fun animateRemoveImpl(holder: RecyclerView.ViewHolder) {
        val view = holder.itemView
        val animator = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, exitOffset(view).toFloat()),
        )
        animator.duration = removeDuration
        animator.interpolator = removeInterpolator
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                dispatchRemoveStarting(holder)
            }

            override fun onAnimationCancel(animation: Animator) {
                resetView(view)
            }

            override fun onAnimationEnd(animation: Animator) {
                runningAnimators.remove(holder)
                dispatchRemoveFinished(holder)
                removeAnimations.remove(holder)
                dispatchFinishedWhenDone()
            }
        })
        runningAnimators[holder] = animator
        animator.start()
    }

    private fun animateMoveImpl(move: MoveInfo) {
        val holder = move.holder
        val view = holder.itemView
        val animator = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f),
        )
        animator.duration = moveDuration
        animator.interpolator = moveInterpolator
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                dispatchMoveStarting(holder)
            }

            override fun onAnimationCancel(animation: Animator) {
                resetView(view)
            }

            override fun onAnimationEnd(animation: Animator) {
                runningAnimators.remove(holder)
                dispatchMoveFinished(holder)
                moveAnimations.remove(holder)
                dispatchFinishedWhenDone()
            }
        })
        runningAnimators[holder] = animator
        animator.start()
    }

    private fun animateChangeImpl(change: ChangeInfo) {
        val oldHolder = change.oldHolder
        val newHolder = change.newHolder
        val oldView = oldHolder.itemView
        val newView = newHolder.itemView

        val oldAnimator = ObjectAnimator.ofFloat(oldView, View.ALPHA, 0f)
        oldAnimator.duration = changeDuration
        oldAnimator.interpolator = removeInterpolator
        oldAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                dispatchChangeStarting(oldHolder, true)
            }

            override fun onAnimationCancel(animation: Animator) {
                resetView(oldView)
            }

            override fun onAnimationEnd(animation: Animator) {
                runningAnimators.remove(oldHolder)
                oldView.alpha = 1f
                dispatchChangeFinished(oldHolder, true)
                changeAnimations.remove(oldHolder)
                dispatchFinishedWhenDone()
            }
        })
        runningAnimators[oldHolder] = oldAnimator
        oldAnimator.start()

        val newAnimator = ObjectAnimator.ofFloat(newView, View.ALPHA, 1f)
        newAnimator.duration = changeDuration
        newAnimator.interpolator = addInterpolator
        newAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                dispatchChangeStarting(newHolder, false)
            }

            override fun onAnimationCancel(animation: Animator) {
                resetView(newView)
            }

            override fun onAnimationEnd(animation: Animator) {
                runningAnimators.remove(newHolder)
                dispatchChangeFinished(newHolder, false)
                changeAnimations.remove(newHolder)
                dispatchFinishedWhenDone()
            }
        })
        runningAnimators[newHolder] = newAnimator
        newView.alpha = 0f
        newAnimator.start()
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        runningAnimators.remove(item)?.cancel()

        if (pendingAdds.remove(item)) {
            resetView(item.itemView)
            dispatchAddFinished(item)
        }
        if (pendingRemoves.remove(item)) {
            resetView(item.itemView)
            dispatchRemoveFinished(item)
        }
        val moveIterator = pendingMoves.iterator()
        while (moveIterator.hasNext()) {
            if (moveIterator.next().holder === item) {
                moveIterator.remove()
                resetView(item.itemView)
                dispatchMoveFinished(item)
                break
            }
        }
        val changeIterator = pendingChanges.iterator()
        while (changeIterator.hasNext()) {
            val change = changeIterator.next()
            if (change.oldHolder === item || change.newHolder === item) {
                changeIterator.remove()
                dispatchChangeFinished(item, change.oldHolder === item)
            }
        }

        addAnimations.remove(item)
        removeAnimations.remove(item)
        moveAnimations.remove(item)
        changeAnimations.remove(item)
        dispatchFinishedWhenDone()
    }

    override fun endAnimations() {
        for (i in pendingRemoves.indices.reversed()) {
            val holder = pendingRemoves.removeAt(i)
            resetView(holder.itemView)
            dispatchRemoveFinished(holder)
        }
        for (i in pendingAdds.indices.reversed()) {
            val holder = pendingAdds.removeAt(i)
            resetView(holder.itemView)
            dispatchAddFinished(holder)
        }
        for (i in pendingMoves.indices.reversed()) {
            val move = pendingMoves.removeAt(i)
            resetView(move.holder.itemView)
            dispatchMoveFinished(move.holder)
        }
        for (i in pendingChanges.indices.reversed()) {
            val change = pendingChanges.removeAt(i)
            dispatchChangeFinished(change.oldHolder, true)
            dispatchChangeFinished(change.newHolder, false)
        }

        val running = ArrayList(runningAnimators.values)
        runningAnimators.clear()
        for (animator in running) {
            animator.cancel()
        }
        addAnimations.clear()
        removeAnimations.clear()
        moveAnimations.clear()
        changeAnimations.clear()

        dispatchAnimationsFinished()
    }

    override fun isRunning(): Boolean {
        return pendingAdds.isNotEmpty() || pendingRemoves.isNotEmpty() ||
                pendingMoves.isNotEmpty() || pendingChanges.isNotEmpty() ||
                addAnimations.isNotEmpty() || removeAnimations.isNotEmpty() ||
                moveAnimations.isNotEmpty() || changeAnimations.isNotEmpty()
    }

    private fun resetAnimation(holder: RecyclerView.ViewHolder) {
        endAnimation(holder)
        resetView(holder.itemView)
    }

    private fun resetView(view: View) {
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    private fun enterOffset(view: View): Int {
        val height = view.height
        return if (height > 0) {
            height
        } else {
            view.resources.getDimensionPixelSize(R.dimen.dock_app_layout_height)
        }
    }

    private fun exitOffset(view: View): Int {
        return enterOffset(view)
    }

    private fun dispatchFinishedWhenDone() {
        if (!isRunning) {
            dispatchAnimationsFinished()
        }
    }

    companion object {
        private const val ADD_DURATION = 320L
        private const val REMOVE_DURATION = 220L
        private const val MOVE_DURATION = 280L
    }
}
