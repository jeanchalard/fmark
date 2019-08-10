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
import android.widget.ImageView

// Context#getColor is only supported from API 23 onward
const val DEFAULT_BRUSH_COLOR = 0xFFE53935.toInt()

enum class Action(val value : FEditorDataType) {
  NONE(0.0), DOWN(1.0), MOVE(2.0), UP(3.0)
}

const val DRAW = 1.0
const val ERASE = 2.0

data class Brush(val mode : PorterDuff.Mode, val color : Int, val width : Float) {
  companion object { @JvmStatic fun isEraser(v : FEditorDataType) = v == ERASE }
}

abstract class BrushView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : ImageView(context, attrs, defStyleAttr, defStyleRes) {
  abstract fun changeBrush(brush : Brush) : Brush
  abstract fun isActive(brush : Brush) : Boolean
}

class PaletteView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : BrushView(context, attrs, defStyleAttr, defStyleRes) {
  override fun changeBrush(brush : Brush) : Brush = Brush(PorterDuff.Mode.SRC_OVER, imageTintList.defaultColor, brush.width)
  override fun isActive(brush : Brush) : Boolean = brush.color == imageTintList.defaultColor && brush.mode == PorterDuff.Mode.SRC_OVER
}

class BrushWidthView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : BrushView(context, attrs, defStyleAttr, defStyleRes) {
  private val width : Float = context.obtainStyledAttributes(attrs, R.styleable.BrushWidthView, defStyleAttr, defStyleRes)?.getFloat(R.styleable.BrushWidthView_widthFactor, 1.0f) ?: 1.0f
  override fun changeBrush(brush : Brush) : Brush = Brush(brush.mode, brush.color, width)
  override fun isActive(brush : Brush) : Boolean = brush.width == width && brush.mode == PorterDuff.Mode.SRC_OVER
}

class EraserView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : BrushView(context, attrs, defStyleAttr, defStyleRes) {
  override fun changeBrush(brush : Brush) : Brush = Brush(PorterDuff.Mode.CLEAR, brush.color, brush.width)
  override fun isActive(brush : Brush) : Boolean = brush.mode == PorterDuff.Mode.CLEAR
}

class CanvasView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : ImageView(context, attrs, defStyleAttr, defStyleRes) {
  class CommandList : ArrayList<FEditorDataType>() {
    fun addCommand(command : Action) = add(command.value)
  }

  interface ChangeDelegate { fun onDataChanged() }

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
  private var changeDelegate : ChangeDelegate? = null
  var brush : Brush = Brush(PorterDuff.Mode.SRC_OVER, defaultColor, defaultWidth)

  fun setOnChangeDelegate(cd : ChangeDelegate) {
    changeDelegate = cd
  }

  override fun onSizeChanged(w : Int, h : Int, oldw : Int, oldh : Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    width = w.toDouble()
    height = h.toDouble()
    pic = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    canvas = Canvas(pic)
  }

  private var viewToImage = Matrix()
  override fun onLayout(changed : Boolean, left : Int, top : Int, right : Int, bottom : Int) {
    super.onLayout(changed, left, top, right, bottom)
    imageMatrix.invert(viewToImage)
    cacheVector[0] = drawable.intrinsicWidth.toFloat(); cacheVector[1] = drawable.intrinsicHeight.toFloat()
    imageMatrix.mapPoints(cacheVector)
    replayData(ArrayList(data))
  }

  override fun onDraw(canvas : Canvas?) {
    if (null == canvas) return
    super.onDraw(canvas)
    canvas.drawBitmap(pic, 0f, 0f, bitmapPaint)
    canvas.drawPath(path, paint)
    if (eraserX > 0f) canvas.drawCircle(eraserX, eraserY, imageMatrix.mapRadius(eraserRadius), eraserFeedbackPaint)
  }

  fun getBitmap() : Bitmap = pic.copy(pic.config, false)
  fun saveData(dest : ArrayList<FEditorDataType>) = dest.apply { clear(); addAll(data) }
  fun readData(source : ArrayList<FEditorDataType>) = replayData(source)

  private fun clamp(i : Float, min : Float, max : Float) = if (i < min) min else if (max < i) max else i

  private var cacheVector = FloatArray(2)
  @SuppressLint("ClickableViewAccessibility") // This view is not clickable.
  override fun onTouchEvent(event : MotionEvent?) : Boolean {
    if (null == event || !touchEnabled) return false
    cacheVector[0] = event.x; cacheVector[1] = event.y
    viewToImage.mapPoints(cacheVector)
    val radius = clamp(event.touchMajor / 12, 5f, 250f) * brush.width
    when (event.action) {
      MotionEvent.ACTION_DOWN -> addDown(cacheVector[0].toDouble(), cacheVector[1].toDouble(), viewToImage.mapRadius(radius / 2).toDouble(), brush.mode.toInt().toDouble(), brush.color.toDouble())
      MotionEvent.ACTION_MOVE -> addMove(cacheVector[0].toDouble(), cacheVector[1].toDouble(), viewToImage.mapRadius(radius / 2).toDouble(), if (brush.mode == PorterDuff.Mode.CLEAR) ERASE else DRAW)
      MotionEvent.ACTION_UP   -> addUp(cacheVector[0].toDouble(), cacheVector[1].toDouble())
    }
    changeDelegate?.onDataChanged()
    invalidate()
    return true
  }

  private fun replayData(replayData : ArrayList<FEditorDataType>) {
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
    oldData.clear()
    oldData.addAll(data)
    replayData(ArrayList())
  }

  fun undo() {
    if (startCommandIndices.size > 0) {
      val index = startCommandIndices.removeAt(startCommandIndices.size - 1)
      replayData(ArrayList(data.subList(0, index)))
    } else {
      replayData(oldData)
      oldData.clear()
    }
    changeDelegate?.onDataChanged()
  }

  private fun addDown(x : FEditorDataType, y : FEditorDataType, pressure : FEditorDataType, mode : FEditorDataType, color : FEditorDataType) {
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
}
