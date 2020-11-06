package com.funnywolf.hollowkit.view.scroll.behavior

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.FrameLayout
import android.widget.Scroller
import androidx.annotation.IntDef
import androidx.core.view.*
import com.funnywolf.hollowkit.view.constrains
import com.funnywolf.hollowkit.view.findChildUnder
import com.funnywolf.hollowkit.view.findHorizontalNestedScrollingTarget
import com.funnywolf.hollowkit.view.findVerticalNestedScrollingTarget
import kotlin.math.abs

/**
 * 基础的嵌套滚动布局，处理嵌套滚动相关的通用逻辑
 *
 * @author https://github.com/funnywolfdadada
 * @since 2020/9/26
 */
abstract class BehavioralScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
): FrameLayout(context, attrs, defStyleAttr), NestedScrollingParent3, NestedScrollingChild3 {

    /**
     * 滚动的最小值
     */
    var minScroll = 0
        protected set

    /**
     * 滚动的最大值
     */
    var maxScroll = 0
        protected set

    /**
     * 上次滚动的方向
     */
    var lastScrollDir = 0
        private set

    /**
     * 当前的滚动状态
     */
    @ScrollState
    var state: Int = ScrollState.NONE
        private set(value) {
            if (field != value) {
                val from = field
                field = value
                listeners.forEach { it.onStateChanged(this, from, value) }
            }
        }

    /**
     * 滚动相关状态的回调
     */
    val listeners = HashSet<BehavioralScrollListener>()

    /**
     * 当前发生嵌套滚动的直接子 view
     */
    var nestedScrollChild: View? = null
        private set

    /**
     * 当前发生嵌套滚动的目标 view
     */
    var nestedScrollTarget: View? = null
        private set

    var enableLog = false

    var prevView: View? = null
        protected set
    var midView: View? = null
        protected set
    var nextView: View? = null
        protected set

    protected abstract var behavior: NestedScrollBehavior?

    /**
     * 上次触摸事件的 x 值，或者 scroller.currX，用于处理自身的滑动事件或动画
     */
    private var lastX = 0F
    /**
     * 上次触摸事件的 y 值，或者 scroller.currY，用于处理自身的滑动事件或动画
     */
    private var lastY = 0F

    /**
     * 拦截事件的最小位移量
     */
    private val touchInterceptSlop = 8

    /**
     * 当前的滚动动画
     */
    private var scrollAnimator: Animator? = null

    /**
     * 用来处理松手时的连续滚动
     */
    private val scroller = Scroller(context)

    /**
     * scroller fling结束时的回调
     */
    private var onEndListener: ((BehavioralScrollView)->Unit)? = null

    /**
     * 用于计算抬手时的速度
     */
    private val velocityTracker by lazy { VelocityTracker.obtain() }

    /**
     * 嵌套滚动帮助类
     */
    private val parentHelper by lazy { NestedScrollingParentHelper(this) }
    private val childHelper by lazy { NestedScrollingChildHelper(this) }

    init {
        isNestedScrollingEnabled = true
    }

    /**
     * 动画移动到某个滚动量，动画过程中滚动量不会进行分发
     */
    fun smoothScrollTo(scroll: Int, duration: Long = 300, onEnd: ((BehavioralScrollView)->Unit)? = null) {
        smoothScroll(intArrayOf(scroll), duration, onEnd)
    }

    /**
     * 动画滚动经过某几个位置，动画过程中滚动量不会进行分发
     */
    fun smoothScroll(scrolls: IntArray, duration: Long = 300, onEnd: ((BehavioralScrollView)->Unit)? = null) {
        log("smoothScrollSelf $scrolls")
        if (scrolls.isEmpty()) {
            return
        }
        // 构造动画
        val animator = when (nestedScrollAxes) {
            ViewCompat.SCROLL_AXIS_HORIZONTAL -> if (scrolls.size == 1 && scrollX == scrolls[0]) {
                null
            } else {
                ObjectAnimator.ofInt(this, "scrollX" , *scrolls)
            }
            ViewCompat.SCROLL_AXIS_VERTICAL -> if (scrolls.size == 1 && scrollY == scrolls[0]) {
                null
            } else {
                ObjectAnimator.ofInt(this, "scrollY" , *scrolls)
            }
            else -> null
        } ?: return
        // 停止当前的所有动画
        scrollAnimator?.cancel()
        scrollAnimator = animator
        scroller.forceFinished(true)
        // 更改状态，开始动画
        state = ScrollState.ANIMATION
        animator.duration = duration
        animator.addListener(object: AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                onEnd?.invoke(this@BehavioralScrollView)
            }
        })
        animator.start()
    }

    /**
     * 以某个速度进行惯性滚动，fling 过程中滚动量会进行分发
     */
    fun fling(vx: Float, vy: Float, onEnd: ((BehavioralScrollView)->Unit)? = null) {
        log("fling $vx $vy")
        // 停止当前的所有动画
        scrollAnimator?.cancel()
        scrollAnimator = null
        scroller.forceFinished(true)
        // 这里不区分滚动方向，在分发滚动量的时候会处理
        state = ScrollState.FLING
        lastX = 0F
        lastY = 0F
        scroller.fling(
            lastX.toInt(), lastY.toInt(), vx.toInt(), vy.toInt(),
            Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE
        )
        onEndListener = onEnd
        invalidate()
    }

    // region layout
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        minScroll = 0
        maxScroll = 0
        when (nestedScrollAxes) {
            ViewCompat.SCROLL_AXIS_HORIZONTAL -> layoutHorizontal()
            ViewCompat.SCROLL_AXIS_VERTICAL -> layoutVertical()
            else -> {}
        }
        behavior?.afterLayout()

        // 重新 layout 后，滚动范围可能已经变了，当前的滚动量可能超范围了
        // 需要重新矫正，scrollBy 内部会进行滚动范围的矫正
        scrollBy(0, 0)
    }

    private fun layoutVertical() {
        // midView 位置不变
        val t = midView?.top ?: 0
        val b = midView?.bottom ?: 0
        // prevView 移动到 midView 之上，bottom 和 midView 的 top 对齐
        prevView?.also {
            it.offsetTopAndBottom(t - it.bottom)
            minScroll = it.top
        }
        // nextView 移动到 midView 之下，top 和 midView 的 bottom 对齐
        nextView?.also {
            it.offsetTopAndBottom(b - it.top)
            maxScroll = it.bottom - height
        }
    }

    private fun layoutHorizontal() {
        val l = midView?.left ?: 0
        val r = midView?.right ?: 0
        prevView?.also {
            it.offsetLeftAndRight(l - it.right)
            minScroll = it.left
        }
        nextView?.also {
            it.offsetLeftAndRight(r - it.left)
            maxScroll = it.right - width
        }
    }
    // endregion

    // region touch event
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // behavior 优先处理，不处理走默认逻辑
        behavior?.handleDispatchTouchEvent(e)?.also {
            log("handleDispatchTouchEvent $it")
            return it
        }
        // 在 down 时复位一些标志位，停掉 scroller 的动画
        if (e.action == MotionEvent.ACTION_DOWN) {
            lastScrollDir = 0
            state = ScrollState.NONE
            scroller.abortAnimation()
        }
        return super.dispatchTouchEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        return when (e.action) {
            // down 时不拦截，但需要记录触点位置，寻找触点位置的 child 和支持嵌套滚动的 target
            MotionEvent.ACTION_DOWN -> {
                lastX = e.rawX
                lastY = e.rawY
                nestedScrollChild = findChildUnder(e.rawX, e.rawY)
                // 如果子 view 有重叠的情况，这里记录的 target 并不完全准确，不过这里只做为是否拦截事件的判断
                nestedScrollTarget = when (nestedScrollAxes) {
                    ViewCompat.SCROLL_AXIS_HORIZONTAL -> findHorizontalNestedScrollingTarget(e.rawX, e.rawY)
                    ViewCompat.SCROLL_AXIS_VERTICAL -> findVerticalNestedScrollingTarget(e.rawX, e.rawY)
                    else -> null
                }
                false
            }
            // move 时如果移动，且没有 target 就自己拦截
            MotionEvent.ACTION_MOVE -> when (nestedScrollAxes) {
                ViewCompat.SCROLL_AXIS_HORIZONTAL -> if (
                        abs(e.rawX - lastX) > touchInterceptSlop
                        && abs(e.rawX - lastX) > abs(e.rawY - lastY)
                        && nestedScrollTarget == null
                ) {
                    lastX = e.rawX
                    true
                } else {
                    false
                }
                ViewCompat.SCROLL_AXIS_VERTICAL -> if (
                        abs(e.rawY - lastY) > touchInterceptSlop
                        && abs(e.rawY - lastY) > abs(e.rawX - lastX)
                        && nestedScrollTarget == null
                ) {
                    lastY = e.rawY
                    true
                } else {
                    false
                }
                else -> false
            }
            else -> super.onInterceptTouchEvent(e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // behavior 优先处理，不处理时自己处理 touch 事件
        behavior?.handleTouchEvent(e)?.also {
            log("handleTouchEvent $it")
            return it
        }
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker.addMovement(e)
                lastX = e.rawX
                lastY = e.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker.addMovement(e)
                val dx = (lastX - e.rawX).toInt()
                val dy = (lastY - e.rawY).toInt()
                lastX = e.rawX
                lastY = e.rawY
                // 不再 dragging 状态时判断是否要进行拖拽
                if (state != ScrollState.DRAGGING) {
                    val canDrag = when (nestedScrollAxes) {
                        ViewCompat.SCROLL_AXIS_HORIZONTAL -> abs(dx) > abs(dy)
                        ViewCompat.SCROLL_AXIS_VERTICAL -> abs(dx) < abs(dy)
                        else -> false
                    }
                    if (canDrag) {
                        startNestedScroll(nestedScrollAxes, ViewCompat.TYPE_TOUCH)
                        state = ScrollState.DRAGGING
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                // dragging 时计算并分发滚动量
                if (state == ScrollState.DRAGGING) {
                    dispatchScrollInternal(dx, dy, ViewCompat.TYPE_TOUCH)
                }
            }
            MotionEvent.ACTION_UP -> {
                stopNestedScroll(ViewCompat.TYPE_TOUCH)
                velocityTracker.addMovement(e)
                velocityTracker.computeCurrentVelocity(1000)
                val vx = -velocityTracker.xVelocity
                val vy = -velocityTracker.yVelocity
                if (!dispatchNestedPreFling(vx, vy)) {
                    dispatchNestedFling(vx, vy, true)
                    fling(vx, vy)
                }
                velocityTracker.clear()
            }
        }
        return true
    }
    // endregion

    // region NestedScrollChild
    override fun isNestedScrollingEnabled(): Boolean {
        return childHelper.isNestedScrollingEnabled
    }

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        childHelper.isNestedScrollingEnabled = enabled
    }

    override fun hasNestedScrollingParent(): Boolean {
        return childHelper.hasNestedScrollingParent()
    }

    override fun hasNestedScrollingParent(type: Int): Boolean {
        return childHelper.hasNestedScrollingParent(type)
    }

    override fun startNestedScroll(axes: Int): Boolean {
        return childHelper.startNestedScroll(axes)
    }

    override fun startNestedScroll(axes: Int, type: Int): Boolean {
        return childHelper.startNestedScroll(axes, type)
    }

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?
    ): Boolean {
        return childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)
    }

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
        type: Int
    ): Boolean {
        return childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?
    ): Boolean {
        return childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow)
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int
    ): Boolean {
        return childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type)
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
        consumed: IntArray
    ) {
        return childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type, consumed)
    }

    override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float): Boolean {
        return childHelper.dispatchNestedPreFling(velocityX, velocityY)
    }

    override fun dispatchNestedFling(
        velocityX: Float,
        velocityY: Float,
        consumed: Boolean
    ): Boolean {
        return childHelper.dispatchNestedFling(velocityX, velocityY, consumed)
    }

    override fun stopNestedScroll() {
        childHelper.stopNestedScroll()
    }

    override fun stopNestedScroll(type: Int) {
        childHelper.stopNestedScroll(type)
    }
    // endregion

    // region NestedScrollParent
    override fun getNestedScrollAxes(): Int {
        return behavior?.scrollAxis() ?: ViewCompat.SCROLL_AXIS_NONE
    }

    override fun onStartNestedScroll(child: View, target: View, nestedScrollAxes: Int): Boolean {
        return onStartNestedScroll(child, target, nestedScrollAxes, ViewCompat.TYPE_TOUCH)
    }

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
        return (axes and nestedScrollAxes) != 0
    }

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int) {
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH)
    }

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        parentHelper.onNestedScrollAccepted(child, target, axes, type)
        startNestedScroll(axes, type)
        nestedScrollChild = child
        nestedScrollTarget = target
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) {
        dispatchNestedPreScrollInternal(dx, dy, consumed)
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        dispatchNestedPreScrollInternal(dx, dy, consumed, type)
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int
    ) {
        dispatchNestedScrollInternal(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed)
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int
    ) {
        dispatchNestedScrollInternal(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type)
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray
    ) {
        dispatchNestedScrollInternal(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed)
    }

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean {
        return dispatchNestedPreFling(velocityX, velocityY)
    }

    override fun onNestedFling(
        target: View,
        velocityX: Float,
        velocityY: Float,
        consumed: Boolean
    ): Boolean {
        if (!consumed) {
            dispatchNestedFling(velocityX, velocityY, true)
            fling(velocityX, velocityY)
            return true
        }
        return false
    }

    override fun onStopNestedScroll(target: View) {
        onStopNestedScroll(target, ViewCompat.TYPE_TOUCH)
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        parentHelper.onStopNestedScroll(target, type)
        stopNestedScroll(type)
    }
    // endregion

    override fun computeScroll() {
        // computeScroll 只用于 fling
        if(scroller.computeScrollOffset()) {
            val dx = (scroller.currX - lastX).toInt()
            val dy = (scroller.currY - lastY).toInt()
            lastX = scroller.currX.toFloat()
            lastY = scroller.currY.toFloat()
            dispatchScrollInternal(dx, dy, ViewCompat.TYPE_NON_TOUCH)
            invalidate()
        } else {
            state = ScrollState.NONE
            stopNestedScroll(ViewCompat.TYPE_NON_TOUCH)
            onEndListener?.invoke(this)
            onEndListener = null
        }
    }

    /**
     * 分发来自自身 touch 事件或 fling 的滚动量
     * -> [dispatchNestedPreScrollInternal]
     * -> [dispatchScrollSelf]
     * -> [dispatchNestedScrollInternal]
     */
    private fun dispatchScrollInternal(dx: Int, dy: Int, type: Int) {
        log("dispatchScrollInternal: type=$type, x=$dx, y=$dy")
        val consumed = IntArray(2)
        when (nestedScrollAxes) {
            ViewCompat.SCROLL_AXIS_HORIZONTAL -> {
                var consumedX = 0
                dispatchNestedPreScrollInternal(dx, dy, consumed, type)
                consumedX += consumed[0]
                consumedX += dispatchScrollSelf(dx - consumedX, type)
                val consumedY = consumed[1]
                // 复用数组
                consumed[0] = 0
                consumed[1] = 0
                dispatchNestedScrollInternal(consumedX, consumedY, dx - consumedX, dy - consumedY, type, consumed)
            }
            ViewCompat.SCROLL_AXIS_VERTICAL -> {
                var consumedY = 0
                dispatchNestedPreScrollInternal(dx, dy, consumed, type)
                consumedY += consumed[1]
                consumedY += dispatchScrollSelf(dy - consumedY, type)
                val consumedX = consumed[0]
                // 复用数组
                consumed[0] = 0
                consumed[1] = 0
                dispatchNestedScrollInternal(consumedX, consumedY, dx - consumedX, dy - consumedY, type, consumed)
            }
            else -> {
                dispatchNestedPreScrollInternal(dx, dy, consumed, type)
                val consumedX = consumed[0]
                val consumedY = consumed[1]
                // 复用数组
                consumed[0] = 0
                consumed[1] = 0
                dispatchNestedScrollInternal(consumedX, consumedY, dx - consumedX, dy - consumedY, type, consumed)
            }
        }
    }

    /**
     * 分发 pre scroll 的滚动量
     */
    private fun dispatchNestedPreScrollInternal(
        dx: Int,
        dy: Int,
        consumed: IntArray,
        type: Int = ViewCompat.TYPE_TOUCH
    ) {
        log("dispatchNestedPreScrollInternal: type=$type, x=$dx, y=$dy")
        when (nestedScrollAxes) {
            ViewCompat.SCROLL_AXIS_HORIZONTAL -> {
                val handleFirst = behavior?.handleNestedPreScrollFirst(dx, type)
                log("handleNestedPreScrollFirst = $handleFirst")
                when (handleFirst) {
                    true -> {
                        val selfConsumed = dispatchScrollSelf(dx, type)
                        dispatchNestedPreScroll(dx - selfConsumed, dy, consumed, null, type)
                        consumed[0] += selfConsumed
                    }
                    false -> {
                        dispatchNestedPreScroll(dx, dy, consumed, null, type)
                        val selfConsumed = dispatchScrollSelf(dx - consumed[0], type)
                        consumed[0] += selfConsumed
                    }
                    null -> dispatchNestedPreScroll(dx, dy, consumed, null, type)
                }
            }
            ViewCompat.SCROLL_AXIS_VERTICAL ->{
                val handleFirst =  behavior?.handleNestedPreScrollFirst(dy, type)
                log("handleNestedPreScrollFirst = $handleFirst")
                when (handleFirst) {
                    true -> {
                        val selfConsumed = dispatchScrollSelf(dy, type)
                        dispatchNestedPreScroll(dx, dy - selfConsumed, consumed, null, type)
                        consumed[1] += selfConsumed
                    }
                    false -> {
                        dispatchNestedPreScroll(dx, dy, consumed, null, type)
                        val selfConsumed = dispatchScrollSelf(dy - consumed[1], type)
                        consumed[1] += selfConsumed
                    }
                    null -> dispatchNestedPreScroll(dx, dy, consumed, null, type)
                }
            }
            else -> dispatchNestedPreScroll(dx, dy, consumed, null, type)
        }
    }

    /**
     * 分发 nested scroll 的滚动量
     */
    private fun dispatchNestedScrollInternal(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int = ViewCompat.TYPE_TOUCH,
        consumed: IntArray = intArrayOf(0, 0)
    ) {
        log("dispatchNestedScrollInternal: type=$type, x=$dxUnconsumed, y=$dyUnconsumed")
        when (nestedScrollAxes) {
            ViewCompat.SCROLL_AXIS_HORIZONTAL -> {
                val handleFirst = behavior?.handleNestedScrollFirst(dxUnconsumed, type)
                log("handleNestedScrollFirst = $handleFirst")
                when (handleFirst) {
                    true -> {
                        val selfConsumed = dispatchScrollSelf(dxUnconsumed, type)
                        dispatchNestedScroll(dxConsumed + selfConsumed, dyConsumed, dxUnconsumed - selfConsumed, dyUnconsumed, null, type, consumed)
                        consumed[0] += selfConsumed
                    }
                    false -> {
                        dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, null, type, consumed)
                        val selfConsumed = dispatchScrollSelf(dxUnconsumed - consumed[0], type)
                        consumed[0] += selfConsumed
                    }
                    null -> dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, null, type, consumed)
                }
            }
            ViewCompat.SCROLL_AXIS_VERTICAL -> {
                val handleFirst = behavior?.handleNestedScrollFirst(dyUnconsumed, type)
                log("handleNestedScrollFirst = $handleFirst")
                when (handleFirst) {
                    true -> {
                        val selfConsumed = dispatchScrollSelf(dyUnconsumed, type)
                        dispatchNestedScroll(dxConsumed, dyConsumed + selfConsumed, dxUnconsumed, dyUnconsumed - selfConsumed, null, type, consumed)
                        consumed[1] += selfConsumed
                    }
                    false -> {
                        dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, null, type, consumed)
                        val selfConsumed = dispatchScrollSelf(dyUnconsumed - consumed[1], type)
                        consumed[1] += selfConsumed
                    }
                    null -> dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, null, type, consumed)
                }
            }
            else -> dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, null, type, consumed)
        }
    }

    /**
     * 处理自身滚动
     */
    private fun dispatchScrollSelf(scroll: Int, @ViewCompat.NestedScrollType type: Int): Int {
        // 没有滚动量就不用回调 behavior 了
        if (scroll == 0) {
            return 0
        }
        // behavior 优先决定是否滚动自身
        val handle = behavior?.handleScrollSelf(scroll, type)
        val consumed = when(handle) {
            true -> scroll
            false -> 0
            else -> if (canScrollSelf(scroll)) {
                log("canScrollSelf")
                scrollBy(scroll, scroll)
                scroll
            } else {
                0
            }
        }
        log("handleScrollSelf: type=$type, $handle $scroll -> $consumed")
        return consumed
    }

    /**
     * 根据当前方向判断自身是否可以滚动
     */
    private fun canScrollSelf(dir: Int): Boolean {
        return when (nestedScrollAxes) {
            ViewCompat.SCROLL_AXIS_HORIZONTAL -> canScrollHorizontally(dir)
            ViewCompat.SCROLL_AXIS_VERTICAL -> canScrollVertically(dir)
            else -> false
        }
    }

    override fun canScrollVertically(direction: Int): Boolean {
        return when {
            nestedScrollAxes != ViewCompat.SCROLL_AXIS_VERTICAL -> false
            direction > 0 -> scrollY < maxScroll
            direction < 0 -> scrollY > minScroll
            else -> true
        }
    }

    override fun canScrollHorizontally(direction: Int): Boolean {
        return when {
            nestedScrollAxes != ViewCompat.SCROLL_AXIS_HORIZONTAL -> false
            direction > 0 -> scrollX < maxScroll
            direction < 0 -> scrollX > minScroll
            else -> true
        }
    }

    override fun scrollBy(x: Int, y: Int) {
        val xx = getScrollByX(x)
        val yy = getScrollByY(y)
        super.scrollBy(xx, yy)
        log("scrollBy $x -> $xx, $y -> $yy")
    }

    /**
     * 根据方向计算 x 轴的真正滚动量
     */
    private fun getScrollByX(dx: Int): Int {
        val newX = scrollX + dx
        return when {
            nestedScrollAxes != ViewCompat.SCROLL_AXIS_HORIZONTAL -> scrollX
            scrollX > 0 -> newX.constrains(0, maxScroll)
            scrollX < 0 -> newX.constrains(minScroll, 0)
            else -> newX.constrains(minScroll, maxScroll)
        } - scrollX
    }

    /**
     * 根据方向计算 y 轴的真正滚动量
     */
    private fun getScrollByY(dy: Int): Int {
        val newY = scrollY + dy
        return when {
            nestedScrollAxes != ViewCompat.SCROLL_AXIS_VERTICAL -> scrollY
            scrollY > 0 -> newY.constrains(0, maxScroll)
            scrollY < 0 -> newY.constrains(minScroll, 0)
            else -> newY.constrains(minScroll, maxScroll)
        } - scrollY
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)

        when (nestedScrollAxes) {
            ViewCompat.SCROLL_AXIS_HORIZONTAL -> {
                lastScrollDir = l - oldl
                listeners.forEach { it.onScrollChanged(this, oldl, l) }
            }
            ViewCompat.SCROLL_AXIS_VERTICAL -> {
                lastScrollDir = t - oldt
                listeners.forEach { it.onScrollChanged(this, oldt, t) }
            }
            else -> lastScrollDir = 0
        }
    }

    private fun log(text: String) {
        if (enableLog) {
            Log.d(javaClass.simpleName, text)
        }
    }

}

/**
 * 用于描述正处于的嵌套滚动状态，和滚动类型 [ViewCompat.NestedScrollType] 共同描述滚动量
 */
@IntDef(ScrollState.NONE, ScrollState.DRAGGING, ScrollState.ANIMATION, ScrollState.FLING)
@Retention(AnnotationRetention.SOURCE)
annotation class ScrollState {
    companion object {
        /**
         * 无状态
         */
        const val NONE = 0
        /**
         * 正在拖拽
         */
        const val DRAGGING = 1
        /**
         * 正在动画，动画产生的滚动不会被分发
         */
        const val ANIMATION = 2
        /**
         * 正在 fling
         */
        const val FLING = 3
    }
}

interface NestedScrollBehavior {

    /**
     * 当前的可滚动方向
     */
    @ViewCompat.ScrollAxis
    fun scrollAxis(): Int = ViewCompat.SCROLL_AXIS_VERTICAL

    /**
     * 在 layout 之后的回调
     */
    fun afterLayout() {
        // do nothing
    }

    /**
     * 在 dispatchTouchEvent 时是否处理 touch 事件
     *
     * @param e touch 事件
     * @return true -> 处理，会在 dispatchTouchEvent 中直接返回 true，false -> 直接返回 false，null -> 不关心，会执行默认逻辑
     */
    fun handleDispatchTouchEvent(e: MotionEvent): Boolean? = null

    /**
     * 在 onTouchEvent 时是否处理 touch 事件
     *
     * @param e touch 事件
     * @return true -> 处理，会直接返回 true，false -> 不处理，会直接返回 false，null -> 不关心，会执行默认逻辑
     */
    fun handleTouchEvent(e: MotionEvent): Boolean? = null

    /**
     * 在 onNestedPreScroll 时，是否优先自己处理
     *
     * @param scroll 滚动量
     * @param type 滚动类型
     * @return true -> 自己优先，false -> 自己不优先，null -> 不处理 onNestedPreScroll
     */
    fun handleNestedPreScrollFirst(scroll: Int, @ViewCompat.NestedScrollType type: Int): Boolean? = null

    /**
     * 在 onNestedScroll 时，是否优先自己处理
     *
     * @param scroll 滚动量
     * @param type 滚动类型
     * @return true -> 自己优先，false -> 自己不优先，null -> 不处理 onNestedPreScroll
     */
    fun handleNestedScrollFirst(scroll: Int, @ViewCompat.NestedScrollType type: Int): Boolean? = null

    /**
     * 在需要 自身滚动时，是否需要处理
     *
     * @param scroll 滚动量
     * @param type 滚动类型
     * @return 是否处理自身滚动，true -> 处理，false -> 不处理，null -> 不关心，会执行默认自身滚动
     */
    fun handleScrollSelf(scroll: Int, @ViewCompat.NestedScrollType type: Int): Boolean? = null

}

interface BehavioralScrollListener {

    /**
     * 滚动状态变化的回调
     */
    fun onStateChanged(v: BehavioralScrollView, @ScrollState from: Int, @ScrollState to: Int) {}

    /**
     * 滚动值变化的回调
     */
    fun onScrollChanged(v: BehavioralScrollView, from: Int, to: Int) {}

}
