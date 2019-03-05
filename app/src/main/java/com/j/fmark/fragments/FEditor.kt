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
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.drive.DriveClient
import com.google.android.gms.drive.DriveFile
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.MetadataChangeSet
import com.j.fmark.BACK_IMAGE_NAME
import com.j.fmark.BrushView
import com.j.fmark.CanvasView
import com.j.fmark.DATA_FILE_NAME
import com.j.fmark.FACE_IMAGE_NAME
import com.j.fmark.FMark
import com.j.fmark.FRONT_IMAGE_NAME
import com.j.fmark.R
import com.j.fmark.color
import com.j.fmark.drive.decodeName
import com.j.fmark.drive.findFile
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.tasks.await
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.StreamCorruptedException
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private const val FACE_CODE = 0
private const val FRONT_CODE = 1
private const val BACK_CODE = 2
private const val SAVE_PICTURE = 1

typealias FEditorDataType = Double
data class Drawing(val code : Int, val guideId : Int, val fileName : String, val data : ArrayList<FEditorDataType>)

class FEditor(private val fmarkHost : FMark, private val driveApi : DriveResourceClient, private val driveRefreshClient : DriveClient, private val clientFolder : Metadata) : Fragment(), CanvasView.ChangeDelegate
{
  val name : String = decodeName(clientFolder)
  private val handler = FEditorHandler(this)
  private val contents = ArrayList<Drawing>()
  private lateinit var shownPicture : Drawing
  private val brushViews = ArrayList<BrushView>()
  private val executor = Executors.newSingleThreadExecutor()

  init
  {
    contents.add(FACE_CODE,  Drawing(FACE_CODE,  R.drawable.face,  FACE_IMAGE_NAME,  ArrayList()))
    contents.add(FRONT_CODE, Drawing(FRONT_CODE, R.drawable.front, FRONT_IMAGE_NAME, ArrayList()))
    contents.add(BACK_CODE,  Drawing(BACK_CODE,  R.drawable.back,  BACK_IMAGE_NAME,  ArrayList()))
    fmarkHost.spinnerVisible = true
    fmarkHost.saveIndicator.hideOk()
    executor.execute { runBlocking {
      val folder = clientFolder.driveId.asDriveFolder()
      val file = driveApi.findFile(folder, DATA_FILE_NAME) ?: driveApi.createFile(folder, MetadataChangeSet.Builder().setTitle(DATA_FILE_NAME).build(), null).await()
      val dataContents = driveApi.openFile(file, DriveFile.MODE_READ_WRITE).await()
      try
      {
        ObjectInputStream(BufferedInputStream(FileInputStream(dataContents.parcelFileDescriptor.fileDescriptor))).use {
          while (true)
          {
            val code = it.readInt()
            val data = it.readObject() as ArrayList<FEditorDataType>
            contents[code].data.addAll(data)
          }
        }
      }
      catch (e : EOFException) { /* done */ }
      catch (e : StreamCorruptedException) { /* done */ }
      fmarkHost.runOnUiThread {
        val view = view ?: return@runOnUiThread
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
        SAVE_PICTURE -> parent.startSave()
      }
    }
  }

  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View?
  {
    val view = inflater.inflate(R.layout.fragment_feditor, container, false)
    view.setOnKeyListener { v, keycode, event ->
      if (KeyEvent.KEYCODE_BACK == keycode)
      {
        fmarkHost.supportFragmentManager.popBackStack()
        executor.shutdown()
        true
      }
      else false
    }

    view.findViewById<AppCompatImageButton>(R.id.feditor_face) .setOnClickListener { switchDrawing(contents[FACE_CODE]) }
    view.findViewById<AppCompatImageButton>(R.id.feditor_front).setOnClickListener { switchDrawing(contents[FRONT_CODE]) }
    view.findViewById<AppCompatImageButton>(R.id.feditor_back) .setOnClickListener { switchDrawing(contents[BACK_CODE]) }

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
    handler.removeMessages(SAVE_PICTURE)
    startSave()
  }

  // Return the regular LayoutInflater so that this fragment can be put fullscreen on top of the existing interface.
  override fun onGetLayoutInflater(savedFragmentState : Bundle?) = fmarkHost.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

  private fun switchDrawing(drawing : Drawing)
  {
    handler.removeMessages(SAVE_PICTURE)
    startSave()
    val guideId = drawing.guideId
    val view = view ?: return
    val canvasView = view.findViewById<CanvasView>(R.id.feditor_canvas)
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
      R.id.action_button_save -> { handler.removeMessages(SAVE_PICTURE); startSave() }
      R.id.action_button_undo -> view?.findViewById<CanvasView>(R.id.feditor_canvas)?.undo()
      R.id.action_button_clear -> view?.findViewById<CanvasView>(R.id.feditor_canvas)?.clear()
    }
    return true
  }

  override fun onDataChanged()
  {
    fmarkHost.saveIndicator.hideOk()
    handler.removeMessages(SAVE_PICTURE)
    handler.sendEmptyMessageDelayed(SAVE_PICTURE, 4_000)
  }

  private fun startSave()
  {
    val canvasView = view?.findViewById<CanvasView>(R.id.feditor_canvas) ?: return
    val drawnBitmap = canvasView.getBitmap()
    canvasView.saveData(shownPicture.data)
    val picToSave = shownPicture
    val guide = fmarkHost.getDrawable(picToSave.guideId)
    fmarkHost.saveIndicator.showInProgress()
    executor.execute { runBlocking {
      try
      {
        saveData()
        savePicture(picToSave, drawnBitmap, guide)
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
    val os = ObjectOutputStream(dataContents.outputStream)
    contents.forEach {
      os.writeInt(it.code)
      os.writeObject(it.data)
    }
    os.flush()
    os.close()
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
