package com.j.fmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View

const val BASE_STROKE_WIDTH = 48f

class CanvasView @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : View(context, attrs, defStyleAttr, defStyleRes)
{
  private var pic : Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
  private var canvas : Canvas = Canvas(pic)
  private val paint = Paint().apply { color = color(0xFF005F00); isAntiAlias = true; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; strokeWidth = BASE_STROKE_WIDTH }
  private val path = Path()

  private fun color(l : Long) : Int
  {
    return (l and -1L).toInt()
  }

  override fun onSizeChanged(w : Int, h : Int, oldw : Int, oldh : Int)
  {
    super.onSizeChanged(w, h, oldw, oldh)
    pic = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    canvas = Canvas(pic)
  }

  override fun onDraw(canvas : Canvas?)
  {
    if (null == canvas) return
    canvas.drawBitmap(pic, 0f, 0f, paint)
    canvas.drawPath(path, paint)
  }

  private var lastX : Float = 0f
  private var lastY : Float = 0f
  override fun onTouchEvent(event : MotionEvent?) : Boolean
  {
    if (null == event) return false
    paint.strokeWidth = BASE_STROKE_WIDTH * event.pressure * event.pressure
    when (event.action)
    {
      MotionEvent.ACTION_DOWN -> path.moveTo(event.x, event.y)
      MotionEvent.ACTION_MOVE ->
      {
        val x = (lastX + event.x) / 2; val y = (lastY + event.y) / 2
        path.quadTo(lastX, lastY, x, y)
        canvas.drawPath(path, paint)
        path.reset()
        path.moveTo(x, y)
      }
      MotionEvent.ACTION_UP ->
      {
        path.lineTo(event.x, event.y)
        canvas.drawPath(path, paint)
        path.reset()
      }
    }
    lastX = event.x
    lastY = event.y
    invalidate()
    return true
  }
}
