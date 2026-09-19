package es.gamingtroop.reader

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView

/** Native zoom can leave a fraction of a CSS pixel at an otherwise visible edge.
 * Use scroll metrics with a small physical tolerance, not an unreachable exact zero. */
class ReaderWebView(context: android.content.Context): WebView(context) {
    var eink=false
    var onSettled: (() -> Unit)? = null
    private val settled=Runnable { onSettled?.invoke() }
    override fun onScrollChanged(l: Int,t: Int,oldl: Int,oldt: Int) {
        super.onScrollChanged(l,t,oldl,oldt)
        if(eink) { removeCallbacks(settled);postDelayed(settled,220) }
    }
    override fun flingScroll(vx: Int,vy: Int) { if(!eink) super.flingScroll(vx,vy) }
    fun pageViewport(direction: Int): Boolean {
        if(!canScrollVertically(direction)) return false
        scrollBy(0,(height*.9f).toInt()*direction)
        onSettled?.invoke()
        return true
    }
    var imagePage = -1
    var onManualTouch: (() -> Unit)? = null
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) onManualTouch?.invoke()
        return super.dispatchTouchEvent(event)
    }
    var onDictionary: (() -> Unit)? = null
    var onAnnotate: (() -> Unit)? = null
    override fun startActionMode(callback: android.view.ActionMode.Callback, type: Int): android.view.ActionMode? {
        val action = onAnnotate ?: return super.startActionMode(callback,type)
        val wrapped = object: android.view.ActionMode.Callback2() {
            override fun onGetContentRect(mode: android.view.ActionMode, view: android.view.View, outRect: android.graphics.Rect) {
                if(callback is android.view.ActionMode.Callback2) callback.onGetContentRect(mode,view,outRect)
                else super.onGetContentRect(mode,view,outRect)
            }
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                val result=callback.onCreateActionMode(mode,menu)
                if(result) { menu.add(0,17001,0,"Subrayar / Nota").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    menu.add(0,17002,1,"Diccionario").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM) }
                if(eink) post { onSettled?.invoke() }
                return result
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = callback.onPrepareActionMode(mode,menu)
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                if(item.itemId==17002) { onDictionary?.invoke();return true }
                if(item.itemId==17001) { action();return true }
                return callback.onActionItemClicked(mode,item)
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                callback.onDestroyActionMode(mode);if(eink) post { onSettled?.invoke() }
            }
        }
        return super.startActionMode(wrapped,type)
    }
    private var imageRequest = 0
    private var requestedPage = -1
    private var released = false

    /** Poll only this local, bundled script while decoding. Old DOM stays painted. */
    fun showImages(page: Int, onReady: () -> Unit, onFailure: () -> Unit) {
        if(requestedPage == page || imagePage == page || released) return
        requestedPage = page
        val request = ++imageRequest
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        fun current() = !released && request == imageRequest && isAttachedToWindow
        fun check(result: String?) {
            if(!current()) return
            when {
                result == "1" -> postVisualStateCallback(request.toLong(), object: VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if(current()) { imagePage = page; requestedPage = -1; onReady() }
                    }
                })
                result == "-1" || android.os.SystemClock.uptimeMillis() >= deadline -> {
                    evaluateJavascript("window.troopCancelImages && window.troopCancelImages($request)", null)
                    requestedPage = -1; onFailure()
                }
                else -> postDelayed({ if(current()) evaluateJavascript("window.troopImageResult($request)") { check(it) } },16)
            }
        }
        evaluateJavascript("window.troopShowImages($page,$request)") { check(it) }
    }
    override fun destroy() { removeCallbacks(settled); released = true; imageRequest++; super.destroy() }
    fun canPanForPageTurn(direction: Int): Boolean = PageTurnPolicy.canPan(
        computeHorizontalScrollOffset(), computeHorizontalScrollRange(), computeHorizontalScrollExtent(),
        direction, (2 * resources.displayMetrics.density).toInt().coerceAtLeast(1))
}

/** Leave scrolling, link clicks, long press and pinch zoom to WebView. */
@SuppressLint("ClickableViewAccessibility")
fun WebView.readingGestures(rtl: Boolean, mode: String, toggle: () -> Unit, next: () -> Unit, previous: () -> Unit) {
    var downX = 0f; var downY = 0f; var multi = false
    var moved = false; var edge = 0; var link = false
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    var panLeft = false; var panRight = false; var zoomedAtStart = false
    val density = resources.displayMetrics.density
    fun turn(direction: Int) { if (direction > 0) next() else if (direction < 0) previous() }
    val tap = GestureDetector(context, object: GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            // Only the centre waits for double tap (zoom). Edges turn on release.
            // A settings change/page turn may already have detached and destroyed this WebView.
            if(!isAttachedToWindow) return false
            if(!multi && PageTurnPolicy.tap(mode,event.x,width,rtl) == 0 && hitTestResult.type !in listOf(WebView.HitTestResult.SRC_ANCHOR_TYPE,WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                val direction = PageTurnPolicy.tap(mode, event.x, width, rtl)
                if (direction == 0) toggle() else turn(direction)
            }
            return false
        }
    })
    setOnTouchListener { _, event ->
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; multi = false
                moved = false; edge = PageTurnPolicy.tap(mode,event.x,width,rtl)
                link = hitTestResult.type in listOf(WebView.HitTestResult.SRC_ANCHOR_TYPE,WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
                panLeft = (this@readingGestures as? ReaderWebView)?.canPanForPageTurn(-1) ?: canScrollHorizontally(-1)
                panRight = (this@readingGestures as? ReaderWebView)?.canPanForPageTurn(1) ?: canScrollHorizontally(1)
                @Suppress("DEPRECATION")
                zoomedAtStart = scale > density * 1.05f
            }
            MotionEvent.ACTION_POINTER_DOWN -> multi = true
            MotionEvent.ACTION_MOVE -> {
                if(kotlin.math.abs(event.x-downX)>slop || kotlin.math.abs(event.y-downY)>slop) moved = true
            }
            MotionEvent.ACTION_CANCEL -> { moved = true; edge = 0 }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX; val dy = event.y - downY
                val edgeTap = edge != 0 && !multi && !moved && !link &&
                    kotlin.math.abs(dx)<=slop && kotlin.math.abs(dy)<=slop &&
                    event.eventTime-event.downTime < ViewConfiguration.getLongPressTimeout() &&
                    edge == PageTurnPolicy.tap(mode,event.x,width,rtl)
                val direction = if(edgeTap) edge else PageTurnPolicy.swipe(mode, dx, dy, density, rtl, multi,
                    zoomedAtStart, panLeft, panRight)
                if(direction != 0) {
                    // Cancel WebView's in-flight touch so the gesture cannot also click a link.
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    onTouchEvent(cancel); tap.onTouchEvent(cancel); cancel.recycle()
                    if(isAttachedToWindow) turn(direction)
                    return@setOnTouchListener true
                }
            }
        }
        tap.onTouchEvent(event)
        false
    }
}
