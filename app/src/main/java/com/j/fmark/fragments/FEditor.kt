package com.j.fmark.fragments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.widget.AppCompatImageButton
import androidx.fragment.app.Fragment
import com.google.android.gms.common.api.ApiException
import com.j.fmark.BACK_CODE
import com.j.fmark.BrushView
import com.j.fmark.CanvasView
import com.j.fmark.Drawing
import com.j.fmark.FACE_CODE
import com.j.fmark.FMark
import com.j.fmark.FRONT_CODE
import com.j.fmark.R
import com.j.fmark.SessionData
import com.j.fmark.color
import com.j.fmark.fdrive.SessionFolder
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private const val SAVE_COMMENT_MSG = 1
private const val SAVE_PICTURE_MSG = 2

private fun EditText.addAfterTextChangedListener(f : (String) -> Unit) {
  this.addTextChangedListener(object : TextWatcher {
    override fun afterTextChanged(s : Editable?) {
      if (s != null) f(s.toString())
    }
    override fun beforeTextChanged(s : CharSequence?, start : Int, count : Int, after : Int) {}
    override fun onTextChanged(s : CharSequence?, start : Int, before : Int, count : Int) {}
  })
}

private fun View?.hasChild(v : View) : Boolean {
  if (this !is ViewGroup) return false
  for (i in 0..childCount) getChildAt(i).let { child -> if (v === child || v.hasChild(v)) return true }
  return false
}

data class SaveBitmapData(val drawing : Drawing, val bitmap : Bitmap, val guide : Drawable)

class FEditor(private val fmarkHost : FMark, private val session : SessionFolder) : Fragment(), CanvasView.ChangeDelegate {
  // val name : String = session.name // TODO : is this really write-only ?
  private val handler = FEditorHandler(this)
  private val contents = SessionData()
  private lateinit var shownPicture : Drawing
  private val brushViews = ArrayList<BrushView>()
  private val executor = Executors.newSingleThreadExecutor()
  private lateinit var canvasView : CanvasView

  init {
    fmarkHost.topSpinnerVisible = true
    fmarkHost.saveIndicator.hideOk()
    executor.execute {
      runBlocking {
        val data = session.openData()
        contents.comment = data.comment
        data.forEach { contents[it.code].data.addAll(it.data) }
        fmarkHost.runOnUiThread {
          val view = view ?: return@runOnUiThread
          val commentView = view.findViewById<EditText>(R.id.feditor_comment_text)
          commentView.setText(data.comment)
          val canvasView = view.findViewById<CanvasView>(R.id.feditor_canvas)
          canvasView.setOnChangeDelegate(this@FEditor)
          canvasView.readData(shownPicture.data)
          fmarkHost.topSpinnerVisible = false
        }
      }
    }
  }

  private class FEditorHandler(private val parent : FEditor) : Handler() {
    override fun handleMessage(msg : Message?) {
      if (null == msg) return
      when (msg.what) {
        SAVE_COMMENT_MSG -> parent.startSaveComment()
        SAVE_PICTURE_MSG -> parent.startSaveBitmap()
      }
    }
  }

  fun onBackPressed() {
    fmarkHost.topSpinnerVisible = true
    startSaveEverything()
    executor.execute {
      executor.shutdown()
      fmarkHost.runOnUiThread { fmarkHost.supportFragmentManager.popBackStack(); fmarkHost.topSpinnerVisible = false }
    }
  }

  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View? {
    val view = inflater.inflate(R.layout.fragment_feditor, container, false)
    view.findViewById<AppCompatImageButton>(R.id.feditor_comment)?.setOnClickListener { switchToComment() }
    view.findViewById<AppCompatImageButton>(R.id.feditor_face).setOnClickListener { switchDrawing(contents[FACE_CODE]) }
    view.findViewById<AppCompatImageButton>(R.id.feditor_front).setOnClickListener { switchDrawing(contents[FRONT_CODE]) }
    view.findViewById<AppCompatImageButton>(R.id.feditor_back).setOnClickListener { switchDrawing(contents[BACK_CODE]) }

    view.findViewById<TextView>(R.id.feditor_date).text = session.date.toShortString()
    view.findViewById<EditText>(R.id.feditor_comment_text).addAfterTextChangedListener { text -> contents.comment = text; onCommentChanged() }

    shownPicture = contents[FACE_CODE]
    canvasView = view.findViewById(R.id.feditor_canvas)
    canvasView.setImageResource(shownPicture.guideId)
    canvasView.readData(shownPicture.data)

    val palette = view.findViewById<LinearLayout>(R.id.feditor_palette)
    for (i in 0 until palette.childCount) {
      val child = palette.getChildAt(i)
      if (child is BrushView) brushViews.add(child)
    }
    brushViews.forEach { it.setOnClickListener { v ->
      val bv = v as BrushView
      canvasView.brush = bv.changeBrush(canvasView.brush)
      brushViews.forEach { brushView -> brushView.isActivated = brushView.isActive(canvasView.brush) }
    }}
    brushViews.forEach { it.isActivated = it.isActive(canvasView.brush) }
    return view
  }

  override fun onPause() {
    super.onPause()
    if (!executor.isShutdown) startSaveEverything()
  }

  override fun onDetach() {
    super.onDetach()
    if (!executor.isShutdown) executor.shutdown()
  }

  // Return the regular LayoutInflater so that this fragment can be put fullscreen on top of the existing interface.
  override fun onGetLayoutInflater(savedFragmentState : Bundle?) = fmarkHost.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

  private fun switchToComment() {
    startSaveBitmap()
    val switcher = view?.findViewById<ViewFlipper>(R.id.feditor_comment_canvas_flipper) ?: return
    val comment = view?.findViewById<EditText>(R.id.feditor_comment_text) ?: return
    if (!switcher.currentView.hasChild(comment)) switcher.showPrevious()
  }

  private fun switchDrawing(drawing : Drawing) {
    val canvasView = view?.findViewById<CanvasView>(R.id.feditor_canvas) ?: return
    val switcher = view?.findViewById<ViewFlipper>(R.id.feditor_comment_canvas_flipper)
    if (null != switcher && !switcher.currentView.hasChild(canvasView)) {
      startSaveComment()
      switcher.showNext()
    } else
      startSaveBitmap()
    shownPicture = drawing
    canvasView.setImageResource(drawing.guideId)
    canvasView.readData(drawing.data)
  }

  override fun onOptionsItemSelected(item : MenuItem) : Boolean {
    if (fmarkHost.topSpinnerVisible) return false
    when (item.itemId) {
      R.id.action_button_save  -> startSaveEverything()
      R.id.action_button_undo  -> view?.findViewById<CanvasView>(R.id.feditor_canvas)?.undo()
      R.id.action_button_clear -> view?.findViewById<CanvasView>(R.id.feditor_canvas)?.clear()
    }
    return true
  }

  private fun onCommentChanged() {
    fmarkHost.saveIndicator.hideOk()
    handler.sendEmptyMessageDelayed(SAVE_COMMENT_MSG, 4_000)
  }

  override fun onDataChanged() {
    fmarkHost.saveIndicator.hideOk()
    handler.sendEmptyMessageDelayed(SAVE_PICTURE_MSG, 4_000)
  }

  private fun startSaveEverything() {
    val switcher = view?.findViewById<ViewFlipper>(R.id.feditor_comment_canvas_flipper)
    if (null == switcher) {
      canvasView.saveData(shownPicture.data)
      startSave(contents.comment, SaveBitmapData(shownPicture, canvasView.getBitmap(), fmarkHost.getDrawable(shownPicture.guideId)!!))
    } else if (switcher.currentView.hasChild(canvasView)) startSaveBitmap() else startSaveComment()
  }

  private fun startSaveComment() = startSave(contents.comment, null)
  private fun startSaveBitmap() {
    canvasView.saveData(shownPicture.data)
    startSave(null, SaveBitmapData(shownPicture, canvasView.getBitmap(), fmarkHost.getDrawable(shownPicture.guideId)!!))
  }

  // Always saves the entire data file, but only the separate big file corresponding to the arg that was non-null
  private fun startSave(comment : String?, saveBitmapData : SaveBitmapData?) {
    if (null != comment) handler.removeMessages(SAVE_COMMENT_MSG)
    if (null != saveBitmapData) handler.removeMessages(SAVE_PICTURE_MSG)
    fmarkHost.saveIndicator.showInProgress()
    executor.execute {
      runBlocking {
        try {
          session.saveData(contents)
          if (null != comment) session.saveComment(comment)
          if (null != saveBitmapData) savePicture(saveBitmapData.drawing, saveBitmapData.bitmap, saveBitmapData.guide)
          fmarkHost.runOnUiThread { fmarkHost.saveIndicator.showOk() }
        } catch (e : ApiException) {
          fmarkHost.runOnUiThread { fmarkHost.saveIndicator.showError() }
        }
      }
    }
  }

  private suspend fun savePicture(picToSave : Drawing, drawnBitmap : Bitmap, guide : Drawable) {
    // Create a new bitmap to compose and floodfill it with white.
    val composedBitmap = Bitmap.createBitmap(drawnBitmap.width, drawnBitmap.height, drawnBitmap.config)
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

    // Save it.
    session.saveImage(composedBitmap, picToSave.fileName)
  }
}
