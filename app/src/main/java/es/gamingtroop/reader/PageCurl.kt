package es.gamingtroop.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.webkit.WebView
import kotlin.math.*

/** Animation is presentation only: it never records progress or invokes navigation. */
object PageEffectPolicy {
    fun enabled(effect: String, animationsEnabled: Boolean) = effect == "curl" && animationsEnabled
    fun toLeft(forward: Boolean, rtl: Boolean) = forward != rtl
}

data class PageTurnFrame(val bitmap: Bitmap, val toLeft: Boolean, val paper: Int)

/** Capture just the visible viewport, including native zoom/scroll, never the whole book. */
fun WebView.pageTurnFrame(toLeft: Boolean, theme: String): PageTurnFrame? {
    if (width <= 0 || height <= 0) return null
    val scale = min(1.0, sqrt(1_500_000.0 / (width.toDouble() * height))).toFloat()
    var bitmap: Bitmap? = null
    return try {
        bitmap = Bitmap.createBitmap(max(1, (width * scale).toInt()), max(1, (height * scale).toInt()), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        // draw(Canvas) uses content coordinates; its parent normally supplies this
        // scroll translation. Without it the curl jumps to an unscrolled crop.
        canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
        draw(canvas)
        val paper = when(theme) { "dark" -> Color.rgb(54,58,61); "sepia" -> Color.rgb(238,222,188); else -> Color.rgb(249,247,239) }
        PageTurnFrame(bitmap, toLeft, paper)
    } catch (_: OutOfMemoryError) {
        bitmap?.recycle(); null // A low-memory phone still changes page without an effect.
    } catch (_: RuntimeException) {
        bitmap?.recycle(); null
    }
}

/** A bounded, local bitmap curls away to reveal the already-painted next WebView below. */
class PageCurlView(context: Context) : View(context) {
    var frame: PageTurnFrame? = null
    var fraction: Float = 0f
        private set
    var onFinished: () -> Unit = {}
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val crease = Path()
    private val shadeMatrix = Matrix()
    private val shadeValues = floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
    private val imageBounds = RectF()
    private val reverseInk = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(.18f) })
    // Reuse shaders/paths: no per-frame bitmap or gradient allocations.
    private val castShadow = LinearGradient(0f,0f,1f,0f,
        intArrayOf(0x48000000,0x18000000,0x00000000),floatArrayOf(0f,.35f,1f),Shader.TileMode.CLAMP)
    private val paperShade = LinearGradient(0f,0f,1f,0f,
        intArrayOf(0x36000000,0x00ffffff,0x50ffffff,0x08ffffff,0x60000000),
        floatArrayOf(0f,.22f,.5f,.76f,1f),Shader.TileMode.CLAMP)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Also blocks taps/pinches directed at the underlying, newly loading page.
        isClickable = true
    }
    private var revealScheduled = false
    fun revealWhenDrawn() {
        if(revealScheduled) return
        revealScheduled = true
        // Allow the replacement AndroidView's display list to reach the compositor.
        postOnAnimation { postOnAnimation { onFinished() } }
    }
    fun start() {
        if(animator != null || frame == null) return
        if(!ValueAnimator.areAnimatorsEnabled()) { onFinished(); return }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360
            // Lift promptly, then settle gently, with no abrupt linear stop.
            interpolator = PathInterpolator(.22f,.55f,.3f,1f)
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
                if(fraction >= 1f) onFinished()
            }
            start()
        }
    }
    fun release() {
        animator?.removeAllUpdateListeners(); animator?.cancel(); animator = null
        onFinished = {}; frame = null
        // Do not recycle a bitmap referenced by a hardware display list; allow GC after detach.
    }
    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frame?.let { drawPage(canvas, it, width.toFloat(), height.toFloat(), fraction) }
    }

    /** Exposed internally for deterministic rendering checks at start/mid/end. */
    internal fun drawPage(canvas: Canvas, frame: PageTurnFrame, w: Float, h: Float, progress: Float) {
        if(w <= 0 || h <= 0) return
        val t = progress.coerceIn(0f,1f)
        if(t >= 1f) return
        val saved = canvas.save()
        if(!frame.toLeft) { canvas.translate(w,0f); canvas.scale(-1f,1f) }
        imageBounds.set(0f,0f,w,h)
        fun image() {
            // Mirror geometry, not the printed text, when turning to the right.
            val s = canvas.save()
            if(!frame.toLeft) { canvas.translate(w,0f); canvas.scale(-1f,1f) }
            canvas.drawBitmap(frame.bitmap,null,imageBounds,paint)
            canvas.restoreToCount(s)
        }
        paint.reset(); paint.isAntiAlias = true; paint.isFilterBitmap = true
        if(t <= 0f) { image(); canvas.restoreToCount(saved); return }
        val bend = w * .27f * sin(Math.PI.toFloat() * t)
        val edge = w * (1f - t)
        val tilt = bend * .58f
        val top = edge + tilt
        val bottom = edge - tilt
        val waist = edge - bend * .2f
        // A diagonal, bowed crease lifts the lower corner before the upper one.
        crease.reset();crease.moveTo(top,0f)
        crease.cubicTo(top-bend*.2f,h*.27f,waist,h*.73f,bottom,h)
        path.reset();path.moveTo(0f,0f);path.lineTo(top,0f)
        path.cubicTo(top-bend*.2f,h*.27f,waist,h*.73f,bottom,h)
        path.lineTo(0f,h);path.close()
        var layer=canvas.save();canvas.clipPath(path);image();canvas.restoreToCount(layer)
        // Shadow follows the slope of the lifted sheet, with a soft outer falloff.
        fun shade(shader: LinearGradient, left: Float, width: Float) {
            shadeValues[0]=width;shadeValues[1]=-2f*tilt/h;shadeValues[2]=left+tilt
            shadeMatrix.setValues(shadeValues)
            shader.setLocalMatrix(shadeMatrix);paint.shader=shader
        }
        val shadowWidth = bend*.42f
        path.set(crease);path.lineTo(bottom+shadowWidth,h)
        path.cubicTo(waist+shadowWidth,h*.73f,top-bend*.2f+shadowWidth,h*.27f,top+shadowWidth,0f);path.close()
        shade(castShadow,edge-bend*.12f,shadowWidth+bend*.12f)
        canvas.drawPath(path,paint);paint.shader=null
        // Curved reverse: reflected real print, lightly desaturated under the paper.
        // Tapering the upper tip avoids the old uniform vertical ribbon appearance.
        path.set(crease);path.lineTo(bottom-bend*.94f,h)
        path.cubicTo(waist-bend*1.12f,h*.72f,top-bend*.92f,h*.24f,top-bend*.54f,0f);path.close()
        paint.color=frame.paper;canvas.drawPath(path,paint)
        layer=canvas.save();canvas.clipPath(path)
        canvas.translate(2f*(edge+tilt)-bend*.22f,0f)
        canvas.skew(-4f*tilt/h,0f);canvas.scale(-1f,1f)
        paint.alpha=48;paint.colorFilter=reverseInk;image()
        paint.alpha=255;paint.colorFilter=null;canvas.restoreToCount(layer)
        shade(paperShade,edge-bend,bend)
        canvas.drawPath(path,paint);paint.shader=null
        // Thin reflected edge; proportional to the viewport, never a heavy outline.
        paint.style=Paint.Style.STROKE;paint.strokeWidth=max(.65f,w/1100f)
        paint.color=0x65ffffff;canvas.drawPath(crease,paint)
        paint.style=Paint.Style.FILL
        canvas.restoreToCount(saved)
    }
}
