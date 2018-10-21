package com.j.fmark.fragments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.AppCompatImageButton
import android.util.Log
import android.util.SparseArray
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.google.android.gms.drive.DriveFile
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.MetadataChangeSet
import com.j.fmark.CanvasView
import com.j.fmark.FMark
import com.j.fmark.R
import com.j.fmark.color
import com.j.fmark.drive.decodeName
import com.j.fmark.drive.findFile
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.tasks.await
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.StreamCorruptedException

const val DATA_FILE_NAME = "data"
const val FACE_IMAGE_NAME = "顔"
const val FRONT_IMAGE_NAME = "前"
const val BACK_IMAGE_NAME = "後"

typealias FEditorDataType = Double
data class Drawing(val code : Int, val guideId : Int, val fileName : String, val data : ArrayList<FEditorDataType>)

class FEditor(private val fmarkHost : FMark, private val driveApi : DriveResourceClient, private val clientFolder : Metadata) : Fragment()
{
  val name : String = decodeName(clientFolder)
  private val contents = SparseArray<Drawing>()
  private lateinit var shownPicture : Drawing

  val initJob : Job
  init
  {
    contents.put(R.id.feditor_face,  Drawing(R.id.feditor_face,  R.drawable.face,  FACE_IMAGE_NAME,  ArrayList()))
    contents.put(R.id.feditor_front, Drawing(R.id.feditor_front, R.drawable.front, FRONT_IMAGE_NAME, ArrayList()))
    contents.put(R.id.feditor_back,  Drawing(R.id.feditor_back,  R.drawable.back,  BACK_IMAGE_NAME,  ArrayList()))
    initJob = GlobalScope.launch {
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
      GlobalScope.launch(Dispatchers.Main) {
        val view = view ?: return@launch
        val canvasView = view.findViewById<CanvasView>(R.id.feditor_canvas)
        canvasView.readData(shownPicture.data)
      }
    }
  }

  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View?
  {
    val view = inflater.inflate(R.layout.fragment_feditor, container, false)
    view.setOnKeyListener { v, keycode, event -> if (KeyEvent.KEYCODE_BACK == keycode) { fmarkHost.supportFragmentManager.popBackStack(); true } else false }

    for (i in 0 until contents.size()) { // Come on SparseArray not iterable ? Really ? :(
      val it = contents.valueAt(i)
      view.findViewById<AppCompatImageButton>(it.code).setOnClickListener { _ -> switchDrawing(it) }
    }

    shownPicture = contents[R.id.feditor_face]
    view.findViewById<ImageView>(R.id.feditor_guide)?.setImageResource(shownPicture.guideId)
    val canvasView = view.findViewById<CanvasView>(R.id.feditor_canvas)
    canvasView.readData(shownPicture.data)

    return view
  }

  // Return the regular LayoutInflater so that this fragment can be put fullscreen on top of the existing interface.
  override fun onGetLayoutInflater(savedFragmentState : Bundle?) = fmarkHost.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

  private fun switchDrawing(drawing : Drawing)
  {
    val guideId = drawing.guideId
    val view = view ?: return
    val canvasView = view.findViewById<CanvasView>(R.id.feditor_canvas)
    canvasView.saveData(shownPicture.data)
    shownPicture = drawing
    view.findViewById<ImageView>(R.id.feditor_guide)?.setImageResource(guideId)
    canvasView.readData(drawing.data)
  }

  override fun onOptionsItemSelected(item : MenuItem?) : Boolean
  {
    val item = item ?: return super.onOptionsItemSelected(item)
    when (item.itemId) {
      R.id.action_button_save -> savePicture()
      R.id.action_button_undo -> {}
    }
    return true
  }

  private fun savePicture()
  {
    val canvasView = view?.findViewById<CanvasView>(R.id.feditor_canvas) ?: return
    val drawnBitmap = canvasView.getBitmap()
    canvasView.saveData(shownPicture.data)
    val picToSave = shownPicture
    val guide = fmarkHost.getDrawable(picToSave.guideId)
    GlobalScope.launch {
      initJob.join()
      saveData()
      savePicture(picToSave, drawnBitmap, guide)
    }
  }

  private suspend fun saveData()
  {
    val dataFile = driveApi.findFile(clientFolder.driveId.asDriveFolder(), DATA_FILE_NAME) ?: return
    val dataContents = driveApi.openFile(dataFile, DriveFile.MODE_WRITE_ONLY).await()
    val os = ObjectOutputStream(dataContents.outputStream)
    for (i in 0 until contents.size()) { // SparseArray still not iterable since the start of this file ? Seriously.
      val it = contents.valueAt(i)
      os.writeInt(it.code)
      os.writeObject(it.data)
    }
    os.flush()
    os.close()
    driveApi.commitContents(dataContents, null)
  }

  private suspend fun savePicture(picToSave : Drawing, drawnBitmap : Bitmap, guide : Drawable)
  {
    val file = driveApi.findFile(clientFolder.driveId.asDriveFolder(), picToSave.fileName)
    val contents = (if (file != null) driveApi.openFile(file, DriveFile.MODE_WRITE_ONLY) else driveApi.createContents()).await()
    val composedBitmap = Bitmap.createBitmap(drawnBitmap.width, drawnBitmap.height, drawnBitmap.config)
    val canvas = Canvas(composedBitmap)
    canvas.drawColor(color(0xFFFFFFFF))
    guide.setBounds(0, 0, composedBitmap.width, composedBitmap.height)
    guide.draw(canvas)
    canvas.drawBitmap(drawnBitmap, 0f, 0f, Paint())
    composedBitmap.compress(Bitmap.CompressFormat.PNG, 85, contents.outputStream)
    val cs = MetadataChangeSet.Builder()
     .setTitle(picToSave.fileName)
     .build()
    if (file != null)
      driveApi.commitContents(contents, cs)
    else
      driveApi.createFile(clientFolder.driveId.asDriveFolder(), cs, contents)
  }
}
