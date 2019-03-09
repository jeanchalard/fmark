package com.j.fmark.fragments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.v4.app.Fragment
import android.support.v7.widget.AppCompatImageButton
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
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.drive.DriveClient
import com.google.android.gms.drive.DriveFile
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.MetadataChangeSet
import com.j.fmark.BACK_CODE
import com.j.fmark.BrushView
import com.j.fmark.CanvasView
import com.j.fmark.DATA_FILE_NAME
import com.j.fmark.Drawing
import com.j.fmark.FACE_CODE
import com.j.fmark.FMark
import com.j.fmark.FRONT_CODE
import com.j.fmark.R
import com.j.fmark.SessionData
import com.j.fmark.color
import com.j.fmark.drive.decodeName
import com.j.fmark.drive.decodeSessionDate
import com.j.fmark.drive.findFile
import com.j.fmark.save
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.tasks.await
import java.io.ObjectOutputStream
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private const val SAVE_PICTURE_MSG = 1

private fun EditText.addAfterTextChangedListener(f : (String) -> Unit)
{
  this.addTextChangedListener(object : TextWatcher {
    override fun afterTextChanged(s : Editable?) { if (s != null) f(s.toString()) }
    override fun beforeTextChanged(s : CharSequence?, start : Int, count : Int, after : Int) {}
    override fun onTextChanged(s : CharSequence?, start : Int, before : Int, count : Int) {}
  })
}

class FEditor(private val fmarkHost : FMark, private val driveApi : DriveResourceClient, private val driveRefreshClient : DriveClient, private val clientFolder : Metadata) : Fragment(), CanvasView.ChangeDelegate
{
  val name : String = decodeName(clientFolder)
  private val handler = FEditorHandler(this)
  private val contents = SessionData()
  private lateinit var shownPicture : Drawing
  private val brushViews = ArrayList<BrushView>()
  private val executor = Executors.newSingleThreadExecutor()

  init
  {
    fmarkHost.spinnerVisible = true
    fmarkHost.saveIndicator.hideOk()
    executor.execute { runBlocking {
      val data = SessionData(driveApi, clientFolder.driveId.asDriveFolder())
      contents.comment = data.comment
      data.forEach { contents[it.code].data.addAll(it.data) }
      fmarkHost.runOnUiThread {
        val view = view ?: return@runOnUiThread
        val commentView = view.findViewById<EditText>(R.id.feditor_comment_text)
        commentView.setText(data.comment)
        val canvasView = view.findViewById<CanvasView>(R.id.feditor_canvas)
        canvasView.setOnChangeDelegate(this@FEditor)
        canvasView.readData(shownPicture.data)
        fmarkHost.spinnerVisible = false
      }
    }}
  }

  private class FEditorHandler(private val parent : FEditor) : Handler()
  {
    override fun handleMessage(msg : Message?)
    {
      if (null == msg) return
      when (msg.what)
      {
        SAVE_PICTURE_MSG -> parent.startSave()
      }
    }
  }

  fun onBackPressed() {
    fmarkHost.spinnerVisible = true
    startSave()
    executor.execute {
      executor.shutdown()
      fmarkHost.runOnUiThread { fmarkHost.supportFragmentManager.popBackStack(); fmarkHost.spinnerVisible = false }
    }
  }

  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View?
  {
    val view = inflater.inflate(R.layout.fragment_feditor, container, false)
    view.findViewById<AppCompatImageButton>(R.id.feditor_comment).setOnClickListener { switchToComment() }
    view.findViewById<AppCompatImageButton>(R.id.feditor_face)   .setOnClickListener { switchDrawing(contents[FACE_CODE]) }
    view.findViewById<AppCompatImageButton>(R.id.feditor_front)  .setOnClickListener { switchDrawing(contents[FRONT_CODE]) }
    view.findViewById<AppCompatImageButton>(R.id.feditor_back)   .setOnClickListener { switchDrawing(contents[BACK_CODE]) }

    view.findViewById<TextView>(R.id.feditor_date).text = decodeSessionDate(clientFolder).toShortString()
    view.findViewById<EditText>(R.id.feditor_comment_text).addAfterTextChangedListener { text -> contents.comment = text }

    shownPicture = contents[FACE_CODE]
    val canvasView = view.findViewById<CanvasView>(R.id.feditor_canvas)
    canvasView.setImageResource(shownPicture.guideId)
    canvasView.readData(shownPicture.data)

    val palette = view.findViewById<LinearLayout>(R.id.feditor_palette)
    for (i in 0 until palette.childCount)
    {
      val child = palette.getChildAt(i)
      if (child is BrushView) brushViews.add(child)
    }
    brushViews.forEach { it.setOnClickListener { v ->
      val bv = v as BrushView
      canvasView.brush = bv.changeBrush(canvasView.brush)
      brushViews.forEach { it.isActivated = it.isActive(canvasView.brush) }
    }}
    brushViews.forEach { it.isActivated = it.isActive(canvasView.brush) }
    return view
  }

  override fun onPause()
  {
    super.onPause()
    handler.removeMessages(SAVE_PICTURE_MSG)
    if (!executor.isShutdown) startSave()
  }

  override fun onStop()
  {
    super.onStop()
    if (!executor.isShutdown) executor.shutdown()
  }

  // Return the regular LayoutInflater so that this fragment can be put fullscreen on top of the existing interface.
  override fun onGetLayoutInflater(savedFragmentState : Bundle?) = fmarkHost.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

  private fun switchToComment()
  {
    handler.removeMessages(SAVE_PICTURE_MSG)
    startSave()
    val switcher = view?.findViewById<ViewFlipper>(R.id.feditor_comment_canvas_flipper) ?: return
    val comment = view?.findViewById<EditText>(R.id.feditor_comment_text)
    if (switcher.currentView != comment) switcher.showPrevious()
  }

  private fun switchDrawing(drawing : Drawing)
  {
    handler.removeMessages(SAVE_PICTURE_MSG)
    startSave()
    val switcher = view?.findViewById<ViewFlipper>(R.id.feditor_comment_canvas_flipper) ?: return
    val canvasView = view?.findViewById<CanvasView>(R.id.feditor_canvas) ?: return
    if (switcher.currentView != canvasView) switcher.showNext()
    val guideId = drawing.guideId
    canvasView.saveData(shownPicture.data)
    shownPicture = drawing
    canvasView.setImageResource(guideId)
    canvasView.readData(drawing.data)
  }

  override fun onOptionsItemSelected(menuItem : MenuItem?) : Boolean
  {
    if (fmarkHost.spinnerVisible) return false
    val item = menuItem ?: return super.onOptionsItemSelected(menuItem)
    when (item.itemId) {
      R.id.action_button_save -> { handler.removeMessages(SAVE_PICTURE_MSG); startSave() }
      R.id.action_button_undo -> view?.findViewById<CanvasView>(R.id.feditor_canvas)?.undo()
      R.id.action_button_clear -> view?.findViewById<CanvasView>(R.id.feditor_canvas)?.clear()
    }
    return true
  }

  override fun onDataChanged()
  {
    fmarkHost.saveIndicator.hideOk()
    handler.removeMessages(SAVE_PICTURE_MSG)
    handler.sendEmptyMessageDelayed(SAVE_PICTURE_MSG, 4_000)
  }

  private data class BitmapSaveData(val drawnBitmap : Bitmap?, val picToSave : Drawing?, val guide : Drawable?)
  private fun startSave()
  {
    val currentView = view?.findViewById<ViewFlipper>(R.id.feditor_comment_canvas_flipper)?.currentView ?: return
    val canvasView = view?.findViewById<CanvasView>(R.id.feditor_canvas)
    val (drawnBitmap, picToSave, guide) = if (currentView === canvasView)
    {
      val drawnBitmap = canvasView.getBitmap()
      canvasView.saveData(shownPicture.data)
      val picToSave = shownPicture
      val guide = fmarkHost.getDrawable(picToSave.guideId)
      BitmapSaveData(drawnBitmap, picToSave, guide)
    } else BitmapSaveData(null, null, null)

    fmarkHost.saveIndicator.showInProgress()
    executor.execute { runBlocking {
      try
      {
        saveData()
        if (picToSave != null && drawnBitmap != null && guide != null) savePicture(picToSave, drawnBitmap, guide)
        fmarkHost.runOnUiThread { fmarkHost.saveIndicator.showOk() }
      }
      catch (e : ApiException)
      {
        fmarkHost.runOnUiThread { fmarkHost.saveIndicator.showError() }
      }
    }}
  }

  private suspend fun saveData()
  {
    val dataFile = driveApi.findFile(clientFolder.driveId.asDriveFolder(), DATA_FILE_NAME) ?: return
    val dataContents = driveApi.openFile(dataFile, DriveFile.MODE_WRITE_ONLY).await()
    ObjectOutputStream(dataContents.outputStream).use {
      contents.save(it)
      it.flush()
    }
    driveApi.commitContents(dataContents, null)
  }

  private suspend fun savePicture(picToSave : Drawing, drawnBitmap : Bitmap, guide : Drawable)
  {
    // Get the file on Drive
    val file = driveApi.findFile(clientFolder.driveId.asDriveFolder(), picToSave.fileName)
    val contents = (if (file != null) driveApi.openFile(file, DriveFile.MODE_WRITE_ONLY) else driveApi.createContents()).await()

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
    }
    else // Fit the width and center in height
    {
      val height = (composedBitmap.width / srcAspect).roundToInt()
      val y = (composedBitmap.height - height) / 2
      guide.setBounds(0, y, composedBitmap.width, y + height)
    }
    guide.draw(canvas)

    // Blit the drawing.
    canvas.drawBitmap(drawnBitmap, 0f, 0f, Paint())

    // Compress the image and save it to Drive.
    composedBitmap.compress(Bitmap.CompressFormat.PNG, 85, contents.outputStream)
    val cs = MetadataChangeSet.Builder()
     .setTitle(picToSave.fileName)
     .build()
    if (file != null)
      driveApi.commitContents(contents, cs).await()
    else
      driveApi.createFile(clientFolder.driveId.asDriveFolder(), cs, contents).await()
  }
}
