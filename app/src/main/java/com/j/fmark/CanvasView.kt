package com.j.fmark

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import kotlinx.collections.immutable.toImmutableList
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("CanvasView", s, e) }

// Context#getColor is only supported from API 23 onward
const val DEFAULT_BRUSH_COLOR = 0xFFE53935.toInt()

private fun codeToImageName(code : Int) = when (code) {
  FACE_CODE  -> FACE_IMAGE_NAME
  FRONT_CODE -> FRONT_IMAGE_NAME
  BACK_CODE  -> BACK_IMAGE_NAME
  else       -> throw IllegalArgumentException("Unknown image code ${code}")
}

enum class Action(val value : FEditorDataType) {
  NONE(0.0), DOWN(1.0), MOVE(2.0), UP(3.0)
}

const val DRAW = 1.0
const val ERASE = 2.0

data class Brush(val mode : PorterDuff.Mode, val color : Int, val width : Float) {
  companion object { @JvmStatic fun isEraser(v : FEditorDataType) = v == ERASE }
}

abstract class BrushView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0) : AppCompatImageView(context, attrs, defStyleAttr) {
  abstract fun changeBrush(brush : Brush) : Brush
  abstract fun isActive(brush : Brush) : Boolean
}

class PaletteView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0) : BrushView(context, attrs, defStyleAttr) {
  override fun changeBrush(brush : Brush) : Brush = Brush(PorterDuff.Mode.SRC_OVER, imageTintList!!.defaultColor, brush.width)
  override fun isActive(brush : Brush) : Boolean = brush.color == imageTintList!!.defaultColor && brush.mode == PorterDuff.Mode.SRC_OVER
}

class BrushWidthView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : BrushView(context, attrs, defStyleAttr) {
  private val width : Float = context.obtainStyledAttributes(attrs, R.styleable.BrushWidthView, defStyleAttr, defStyleRes)?.getFloat(R.styleable.BrushWidthView_widthFactor, 1.0f) ?: 1.0f
  override fun changeBrush(brush : Brush) : Brush = Brush(brush.mode, brush.color, width)
  override fun isActive(brush : Brush) : Boolean = brush.width == width && brush.mode == PorterDuff.Mode.SRC_OVER
}

class EraserView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0) : BrushView(context, attrs, defStyleAttr) {
  override fun changeBrush(brush : Brush) : Brush = Brush(PorterDuff.Mode.CLEAR, brush.color, brush.width)
  override fun isActive(brush : Brush) : Boolean = brush.mode == PorterDuff.Mode.CLEAR
}

class CanvasView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : AppCompatImageView(context, attrs, defStyleAttr) {
  class CommandList : ArrayList<FEditorDataType>() {
    fun addCommand(command : Action) = add(command.value)
  }
  val fileName get() = codeToImageName(code)
  private val code : Int = context.obtainStyledAttributes(attrs, R.styleable.CanvasView, defStyleAttr, defStyleRes)?.getInt(R.styleable.CanvasView_imageCode, 0)!!
  private val touchEnabled : Boolean = context.obtainStyledAttributes(attrs, R.styleable.CanvasView, defStyleAttr, defStyleRes)?.getBoolean(R.styleable.CanvasView_touchEnabled, true) ?: true
  private val data = CommandList()
  private val oldData = CommandList()
  private val defaultColor = DEFAULT_BRUSH_COLOR
  private val defaultWidth = 1.0f
  private val startCommandIndices = ArrayList<Int>()
  private val eraserRadius = context.resources.getDimension(R.dimen.eraserRadius)
  private var pic = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
  private var canvas = Canvas(pic)
  private val bitmapPaint = Paint()
  private val paint = Paint().apply { color = defaultColor; isAntiAlias = true; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; strokeWidth = 1f }
  private val path = Path()
  private var width : Double = 0.0
  private var height : Double = 0.0
  private var lastX : Float = 0.0f
  private var lastY : Float = 0.0f
  private val eraserFeedbackPaint = Paint().apply { color = color(0xFF000000); isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1f }
  private var eraserX = -1.0f;
  private var eraserY = -1.0f
  private var dirty = false
    set(b) { field = b; if (dirty) changeListeners.forEach { it.onCanvasChanged() } }
  public fun hasDirtyData() = dirty // isDirty is a method in View used by the framework, must not override
  var brush : Brush = Brush(PorterDuff.Mode.SRC_OVER, defaultColor, defaultWidth)

  interface OnChangeListener { fun onCanvasChanged() }
  private val changeListeners = CopyOnWriteArrayList<OnChangeListener>()
  fun addOnChangeListener(l : OnChangeListener) = changeListeners.add(l).also { log("Add change listener ${l}") }
  fun removeChangeListener(l : OnChangeListener) = changeListeners.remove(l).also { log("Remove change listener ${l}") }

  init {
    log("Create CanvasView ${this}")
    setImageResource(codeToResource(code))
  }

  override fun onSizeChanged(w : Int, h : Int, oldw : Int, oldh : Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    log("CanvasView changed ${oldw}x${oldh} → ${w}→${h}")
    width = w.toDouble()
    height = h.toDouble()
    pic = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    canvas = Canvas(pic)
    replayData(ArrayList(data))
  }

  private var viewToImage = Matrix()
  override fun onLayout(changed : Boolean, left : Int, top : Int, right : Int, bottom : Int) {
    super.onLayout(changed, left, top, right, bottom)
    log("Layout CanvasView ${left}x${top}-${right}x${bottom}, changed = ${changed}")
    imageMatrix.invert(viewToImage)
    cacheVector[0] = drawable.intrinsicWidth.toFloat(); cacheVector[1] = drawable.intrinsicHeight.toFloat()
    imageMatrix.mapPoints(cacheVector)
    replayData(ArrayList(data))
    dirty = false
  }

  override fun onDraw(canvas : Canvas?) {
    log(if (null == canvas) "onDraw but canvas is null" else "onDraw")
    if (null == canvas) return
    super.onDraw(canvas)
    canvas.drawBitmap(pic, 0f, 0f, bitmapPaint)
    canvas.drawPath(path, paint)
    if (eraserX > 0f) canvas.drawCircle(eraserX, eraserY, imageMatrix.mapRadius(eraserRadius), eraserFeedbackPaint)
  }

  fun readData(source : List<FEditorDataType>) = replayData(source)
  fun getDrawing() = Drawing(code, data.toImmutableList())
  fun getSaveBitmap() : Bitmap? = (if (dirty) composePicture(pic) else null).also { dirty = false }

  private fun clamp(i : Float, min : Float, max : Float) = if (i < min) min else if (max < i) max else i

  private var cacheVector = FloatArray(2)
  @SuppressLint("ClickableViewAccessibility") // This view is not clickable.
  override fun onTouchEvent(event : MotionEvent?) : Boolean {
    log("onTouchEvent ${event}, touchEnabled = ${touchEnabled}")
    if (null == event || !touchEnabled) return false
    cacheVector[0] = event.x; cacheVector[1] = event.y
    viewToImage.mapPoints(cacheVector)
    val radius = clamp(event.touchMajor / 12, 5f, 250f) * brush.width
    when (event.action) {
      MotionEvent.ACTION_DOWN -> addDown(cacheVector[0].toDouble(), cacheVector[1].toDouble(), viewToImage.mapRadius(radius / 2).toDouble(), brush.mode.toInt().toDouble(), brush.color.toDouble())
      MotionEvent.ACTION_MOVE -> addMove(cacheVector[0].toDouble(), cacheVector[1].toDouble(), viewToImage.mapRadius(radius / 2).toDouble(), if (brush.mode == PorterDuff.Mode.CLEAR) ERASE else DRAW)
      MotionEvent.ACTION_UP   -> addUp(cacheVector[0].toDouble(), cacheVector[1].toDouble())
    }
    dirty = true
    invalidate()
    return true
  }

  private fun replayData(replayData : List<FEditorDataType>) {
    log("Replaying data on ${this} with ${replayData.size} data points")
    data.clear()
    startCommandIndices.clear()
    pic.eraseColor(Color.TRANSPARENT)
    var i = 0
    while (i < replayData.size)
      when (replayData[i++]) {
        Action.DOWN.value -> addDown(replayData[i++], replayData[i++], replayData[i++], replayData[i++], replayData[i++])
        Action.MOVE.value -> addMove(replayData[i++], replayData[i++], replayData[i++], replayData[i++])
        Action.UP.value   -> addUp(replayData[i++], replayData[i++])
      }
    invalidate()
  }

  fun clear() {
    log("Clearing data")
    oldData.clear()
    oldData.addAll(data)
    replayData(ArrayList())
    dirty = true
  }

  fun undo() {
    if (startCommandIndices.size > 0) {
      log("Undo command number ${startCommandIndices.size - 1}")
      val index = startCommandIndices.removeAt(startCommandIndices.size - 1)
      replayData(ArrayList(data.subList(0, index)))
    } else {
      log("Undo clear")
      replayData(oldData)
      oldData.clear()
    }
    dirty = true
  }

  private fun addDown(x : FEditorDataType, y : FEditorDataType, pressure : FEditorDataType, mode : FEditorDataType, color : FEditorDataType) {
    // This commented out because it will trigger also on replayData, which is called whenever data is loaded and the like
    // log("addDown ${x}x${y}x${pressure} with mode ${mode} and color ${color}")
    cacheVector[0] = x.toFloat(); cacheVector[1] = y.toFloat()
    imageMatrix.mapPoints(cacheVector)
    val radius = imageMatrix.mapRadius(pressure.toFloat())
    val screenX = cacheVector[0]; val screenY = cacheVector[1]
    paint.xfermode = PorterDuffXfermode(mode.toInt().toPorterDuffMode())
    paint.color = color.toInt()
    paint.strokeWidth = radius
    path.moveTo(screenX, screenY)
    startCommandIndices.add(data.size)
    data.addCommand(Action.DOWN)
    data.add(x); data.add(y); data.add(pressure)
    data.add(mode); data.add(color)
    lastX = cacheVector[0]; lastY = cacheVector[1]
    if (brush.mode == PorterDuff.Mode.CLEAR) {
      eraserX = screenX; eraserY = screenY
    }
  }

  private fun addMove(x : FEditorDataType, y : FEditorDataType, pressure : FEditorDataType, erase : FEditorDataType) {
    // This commented out because it will trigger also on replayData, which is called whenever data is loaded and the like
    // log("addMove ${x}x${y}x${pressure} with erase ${erase}")
    val isEraser = Brush.isEraser(erase)
    cacheVector[0] = x.toFloat(); cacheVector[1] = y.toFloat()
    imageMatrix.mapPoints(cacheVector)
    val screenX : Float; val screenY : Float
    if (isEraser) {
      screenX = cacheVector[0]; screenY = cacheVector[1]
      eraserX = screenX; eraserY = screenY
      paint.strokeWidth = imageMatrix.mapRadius(eraserRadius) * 2
      path.lineTo(screenX, screenY)
    } else {
      val radius = imageMatrix.mapRadius(pressure.toFloat())
      screenX = ((lastX + cacheVector[0]) / 2)
      screenY = ((lastY + cacheVector[1]) / 2)
      paint.strokeWidth = (paint.strokeWidth * 8 + radius * 2) / 10
    }
    path.quadTo(lastX, lastY, screenX, screenY)
    canvas.drawPath(path, paint)
    path.reset()
    path.moveTo(screenX, screenY)
    data.addCommand(Action.MOVE)
    data.add(x); data.add(y); data.add(pressure); data.add(erase)
    lastX = cacheVector[0]; lastY = cacheVector[1]
  }

  private fun addUp(x : FEditorDataType, y : FEditorDataType) {
    // This commented out because it will trigger also on replayData, which is called whenever data is loaded and the like
    // log("addUp ${x}x${y}")
    cacheVector[0] = x.toFloat(); cacheVector[1] = y.toFloat()
    imageMatrix.mapPoints(cacheVector)
    val screenX = cacheVector[0]; val screenY = cacheVector[1]
    path.lineTo(screenX, screenY)
    canvas.drawPath(path, paint)
    path.reset()
    data.addCommand(Action.UP)
    data.add(x); data.add(y)
    lastX = cacheVector[0]; lastY = cacheVector[1]
    eraserX = -1.0f; eraserY = -1.0f
  }

  private fun composePicture(drawnBitmap : Bitmap) : Bitmap =
   Bitmap.createBitmap(drawnBitmap.width, drawnBitmap.height, drawnBitmap.config).also { composedBitmap ->
     val guide = resources.getDrawable(codeToResource(code))
     log("${this} composing picture with guide ${guide}")

     // Floodfill the bitmap with white first.
     val canvas = Canvas(composedBitmap)
     canvas.drawColor(color(0xFFFFFFFF))

     // Compute the aspect-ratio-respecting size of the guide and blit it.
     val srcAspect = guide.intrinsicWidth.toDouble() / guide.intrinsicHeight
     val dstAspect = composedBitmap.width.toDouble() / composedBitmap.height
     if (srcAspect < dstAspect) // Guide is taller (relative to its width) than Dst : fit the height and center in width
     {
       val width = (composedBitmap.height * srcAspect).roundToInt()
       val x = (composedBitmap.width - width) / 2
       guide.setBounds(x, 0, x + width, composedBitmap.height)
     } else { // Fit the width and center in height
       val height = (composedBitmap.width / srcAspect).roundToInt()
       val y = (composedBitmap.height - height) / 2
       guide.setBounds(0, y, composedBitmap.width, y + height)
     }
     guide.draw(canvas)

     // Blit the drawing.
     canvas.drawBitmap(drawnBitmap, 0f, 0f, Paint())
   }
}
