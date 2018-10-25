package com.j.fmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import com.j.fmark.fragments.FEditorDataType

const val BASE_STROKE_WIDTH = 48f
enum class Action(val value : FEditorDataType)
{
  NONE(0.0), DOWN(1.0), MOVE(2.0), UP(3.0)
}
const val DRAW = 1.0
const val ERASE = 2.0
data class Brush(val mode : PorterDuff.Mode, val color : Int)
{
  companion object { @JvmStatic fun isEraser(v : FEditorDataType) = v == ERASE }
}
abstract class BrushView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : ImageView(context, attrs, defStyleAttr, defStyleRes)
{
  abstract val brush : Brush
}
class PaletteView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : BrushView(context, attrs, defStyleAttr, defStyleRes)
{
  override val brush : Brush = Brush(PorterDuff.Mode.SRC_OVER, imageTintList.defaultColor)
}
class EraserView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : BrushView(context, attrs, defStyleAttr, defStyleRes)
{
  companion object { val CLEAR_BRUSH = Brush(PorterDuff.Mode.CLEAR, 0) }
  override val brush : Brush = CLEAR_BRUSH
}
class CanvasView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : View(context, attrs, defStyleAttr, defStyleRes)
{
  class CommandList : ArrayList<FEditorDataType>()
  {
    fun addCommand(command : Action) = add(command.value)
  }
  private val data = CommandList()
  private val oldData = CommandList()
  private val defaultColor = context.getColor(R.color.defaultBrushColor)
  private val startCommandIndices = ArrayList<Int>()
  private var pic = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
  private var canvas = Canvas(pic)
  private val bitmapPaint = Paint()
  private val paint = Paint().apply { color = defaultColor; isAntiAlias = true; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; strokeWidth = BASE_STROKE_WIDTH }
  private val path = Path()
  private var width : Double = 0.0
  private var height : Double = 0.0
  private var lastX : FEditorDataType = 0.0
  private var lastY : FEditorDataType = 0.0
  private val eraserFeedbackPaint = Paint().apply { color = color(0xFF000000); isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1f}
  private val eraserRadius = context.resources.getDimension(R.dimen.eraserRadius)
  private var eraserX = -1.0f; private var eraserY = -1.0f
  var brush : Brush = Brush(PorterDuff.Mode.SRC_OVER, defaultColor)

  override fun onSizeChanged(w : Int, h : Int, oldw : Int, oldh : Int)
  {
    super.onSizeChanged(w, h, oldw, oldh)
    width = w.toDouble()
    height = h.toDouble()
    pic = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    canvas = Canvas(pic)
  }

  override fun onDraw(canvas : Canvas?)
  {
    if (null == canvas) return
    canvas.drawBitmap(pic, 0f, 0f, bitmapPaint)
    canvas.drawPath(path, paint)
    if (eraserX > 0f) canvas.drawCircle(eraserX, eraserY, eraserRadius, eraserFeedbackPaint)
  }

  fun getBitmap() : Bitmap = pic.copy(pic.config, false)
  fun saveData(dest : ArrayList<FEditorDataType>) = dest.apply { clear(); addAll(data) }
  fun readData(source : ArrayList<FEditorDataType>) = replayData(source)

  override fun onTouchEvent(event : MotionEvent?) : Boolean
  {
    if (null == event) return false
    when (event.action)
    {
      MotionEvent.ACTION_DOWN -> addDown(event.x / width, event.y / height, (event.pressure * event.pressure).toDouble(), brush.mode.toInt().toDouble(), brush.color.toDouble())
      MotionEvent.ACTION_MOVE -> addMove(event.x / width, event.y / height, (event.pressure * event.pressure).toDouble(), if (brush.mode == PorterDuff.Mode.CLEAR) ERASE else DRAW)
      MotionEvent.ACTION_UP ->   addUp(event.x / width, event.y / height)
    }
    invalidate()
    return true
  }

  private fun replayData(replayData : ArrayList<FEditorDataType>)
  {
    data.clear()
    startCommandIndices.clear()
    pic.eraseColor(Color.TRANSPARENT)
    var i = 0
    while (i < replayData.size)
      when (replayData[i++])
      {
        Action.DOWN.value ->  addDown(replayData[i++], replayData[i++], replayData[i++], replayData[i++], replayData[i++])
        Action.MOVE.value ->  addMove(replayData[i++], replayData[i++], replayData[i++], replayData[i++])
        Action.UP.value ->    addUp(replayData[i++], replayData[i++])
      }
    invalidate()
  }

  fun clear()
  {
    oldData.clear()
    oldData.addAll(data)
    replayData(ArrayList())
  }

  fun undo()
  {
    if (startCommandIndices.size > 0)
    {
      val index = startCommandIndices.removeAt(startCommandIndices.size - 1)
      replayData(ArrayList(data.subList(0, index)))
    }
    else
    {
      replayData(oldData)
      oldData.clear()
    }
  }

  private fun addDown(x : FEditorDataType, y : FEditorDataType, pressure : FEditorDataType, mode : FEditorDataType, color : FEditorDataType)
  {
    val screenX = (x * width).toFloat()
    val screenY = (y * height).toFloat()
    paint.xfermode = PorterDuffXfermode(mode.toInt().toPorterDuffMode())
    paint.color = color.toInt()
    paint.strokeWidth = (BASE_STROKE_WIDTH * pressure).toFloat()
    path.moveTo(screenX, screenY)
    startCommandIndices.add(data.size)
    data.addCommand(Action.DOWN)
    data.add(x); data.add(y); data.add(pressure)
    data.add(mode); data.add(color)
    lastX = x; lastY = y
    if (brush.mode == PorterDuff.Mode.CLEAR)
    {
      eraserX = screenX; eraserY = screenY
    }
  }

  private fun addMove(x : FEditorDataType, y : FEditorDataType, pressure : FEditorDataType, erase : FEditorDataType)
  {
    val isEraser = Brush.isEraser(erase)
    val screenX : Float; val screenY : Float
    if (isEraser)
    {
      screenX = (x * width).toFloat()
      screenY = (y * height).toFloat()
      eraserX = screenX; eraserY = screenY
      paint.strokeWidth = eraserRadius * 2
      path.lineTo(screenX, screenY)
    }
    else
    {
      screenX = (((lastX + x) / 2) * width).toFloat()
      screenY = (((lastY + y) / 2) * height).toFloat()
      paint.strokeWidth = (BASE_STROKE_WIDTH * pressure).toFloat()
    }
    path.quadTo((lastX * width).toFloat(), (lastY * height).toFloat(), screenX, screenY)
    canvas.drawPath(path, paint)
    path.reset()
    path.moveTo(screenX, screenY)
    data.addCommand(Action.MOVE)
    data.add(x); data.add(y); data.add(pressure); data.add(erase)
    lastX = x; lastY = y
  }

  private fun addUp(x : FEditorDataType, y : FEditorDataType)
  {
    val screenX = (x * width).toFloat()
    val screenY = (y * height).toFloat()
    path.lineTo(screenX, screenY)
    canvas.drawPath(path, paint)
    path.reset()
    data.addCommand(Action.UP)
    data.add(x); data.add(y)
    lastX = x; lastY = y
    eraserX = -1.0f; eraserY = -1.0f
  }
}
