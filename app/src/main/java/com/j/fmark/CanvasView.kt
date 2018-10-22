package com.j.fmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.j.fmark.fragments.FEditorDataType

const val BASE_STROKE_WIDTH = 48f
const val CODE_ACTION_DOWN : FEditorDataType = 0.0
const val CODE_ACTION_MOVE : FEditorDataType = 1.0
const val CODE_ACTION_UP : FEditorDataType = 2.0

class CanvasView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : View(context, attrs, defStyleAttr, defStyleRes)
{
  private val data : ArrayList<FEditorDataType> = ArrayList()
  private val startCommandIndices : ArrayList<Int> = ArrayList()
  private var pic : Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
  private var canvas : Canvas = Canvas(pic)
  private val paint = Paint().apply { color = color(0xFF005F00); isAntiAlias = true; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; strokeWidth = BASE_STROKE_WIDTH }
  private val path = Path()
  private var width : Double = 0.0
  private var height : Double = 0.0
  private var lastX : FEditorDataType = 0.0
  private var lastY : FEditorDataType = 0.0

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
    canvas.drawBitmap(pic, 0f, 0f, paint)
    canvas.drawPath(path, paint)
  }

  fun getBitmap() = pic.copy(pic.config, false)
  fun saveData(dest : ArrayList<FEditorDataType>) = dest.apply { clear(); addAll(data) }
  fun readData(source : ArrayList<FEditorDataType>) = replayData(source)

  override fun onTouchEvent(event : MotionEvent?) : Boolean
  {
    if (null == event) return false
    when (event.action)
    {
      MotionEvent.ACTION_DOWN -> addDown(event.x / width, event.y / height, (event.pressure * event.pressure).toDouble())
      MotionEvent.ACTION_MOVE -> addMove(event.x / width, event.y / height, (event.pressure * event.pressure).toDouble())
      MotionEvent.ACTION_UP ->   addUp(event.x / width, event.y / height)
    }
    invalidate()
    return true
  }

  private fun replayData(replayData : ArrayList<FEditorDataType>)
  {
    data.clear()
    pic.eraseColor(Color.TRANSPARENT)
    var i = 0
    while (i < replayData.size)
      when (replayData[i++])
      {
        CODE_ACTION_DOWN -> addDown(replayData[i++], replayData[i++], replayData[i++])
        CODE_ACTION_MOVE -> addMove(replayData[i++], replayData[i++], replayData[i++])
        CODE_ACTION_UP ->   addUp(replayData[i++], replayData[i++])
      }
    invalidate()
  }

  fun undo()
  {
    val index = startCommandIndices.removeAt(startCommandIndices.size - 1)
    replayData(ArrayList(data.subList(0, index)))
  }

  private fun addDown(x : FEditorDataType, y : FEditorDataType, pressure : FEditorDataType)
  {
    val screenX = (x * width).toFloat()
    val screenY = (y * height).toFloat()
    paint.strokeWidth = (BASE_STROKE_WIDTH * pressure).toFloat()
    path.moveTo(screenX, screenY)
    startCommandIndices.add(data.size)
    data.add(CODE_ACTION_DOWN)
    data.add(x); data.add(y); data.add(pressure)
    lastX = x; lastY = y
  }

  private fun addMove(x : FEditorDataType, y : FEditorDataType, pressure : FEditorDataType)
  {
    val screenX = (((lastX + x) / 2) * width).toFloat(); val screenY = (((lastY + y) / 2) * height).toFloat()
    paint.strokeWidth = (BASE_STROKE_WIDTH * pressure).toFloat()
    path.quadTo((lastX * width).toFloat(), (lastY * height).toFloat(), screenX, screenY)
    canvas.drawPath(path, paint)
    path.reset()
    path.moveTo(screenX, screenY)
    data.add(CODE_ACTION_MOVE)
    data.add(x); data.add(y); data.add(pressure)
    lastX = x; lastY = y
  }

  private fun addUp(x : FEditorDataType, y : FEditorDataType)
  {
    val screenX = (x * width).toFloat()
    val screenY = (y * height).toFloat()
    path.lineTo(screenX, screenY)
    canvas.drawPath(path, paint)
    path.reset()
    data.add(CODE_ACTION_UP)
    data.add(x); data.add(y)
    lastX = x; lastY = y
  }
}
