package com.j.fmark.fragments

import android.content.Context
import android.os.Bundle
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
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ApiException
import com.j.fmark.BACK_CODE
import com.j.fmark.BrushView
import com.j.fmark.CanvasView
import com.j.fmark.FACE_CODE
import com.j.fmark.FMark
import com.j.fmark.FRONT_CODE
import com.j.fmark.LOGEVERYTHING
import com.j.fmark.R
import com.j.fmark.SessionData
import com.j.fmark.fdrive.SessionFolder
import com.j.fmark.logAlways
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("FEditor", s, e) }

private fun EditText.addAfterTextChangedListener(f : (String) -> Unit) {
  this.addTextChangedListener(object : TextWatcher {
    override fun afterTextChanged(s : Editable?) {
      if (s != null) f(s.toString())
    }

    override fun beforeTextChanged(s : CharSequence?, start : Int, count : Int, after : Int) {}
    override fun onTextChanged(s : CharSequence?, start : Int, before : Int, count : Int) {}
  })
}

private fun ViewFlipper.flipTo(v : View) {
  log("flipTo ${v}")
  displayedChild = indexOfChild(v)
}
private val ViewFlipper.shownView get() = getChildAt(displayedChild)

private data class SaveString(val string : String, val dirty : Boolean)

class FEditor(private val fmarkHost : FMark, private val session : SessionFolder) : Fragment() {
  private lateinit var impl : Impl // Limit the horror of the no-arg constructor to this member

  // Return the regular LayoutInflater so that this fragment can be put fullscreen on top of the existing interface.
  override fun onGetLayoutInflater(savedFragmentState : Bundle?) = fmarkHost.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
  fun onBackPressed() = impl.onBackPressed()
  fun onOptionsItemSelected(itemId : Int) : Boolean = impl.onOptionsItemSelected(itemId)
  override fun onPause() { super.onPause(); impl.onPause() }
  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View? {
    impl = Impl(fmarkHost, session, inflater, container)
    return impl.view
  }

  private inner class Impl(private val fmarkHost : FMark, private val session : SessionFolder, inflater : LayoutInflater, container : ViewGroup?) : CanvasView.OnChangeListener {
    val view : View = inflater.inflate(R.layout.fragment_feditor, container, false)
    private val commentContainer = view.findViewById<View>(R.id.feditor_comment_container)
    private val canvasFlipper = view.findViewById<ViewFlipper>(R.id.feditor_canvas_flipper)!!
    private val faceCanvas = view.findViewById<CanvasView>(R.id.feditor_canvas_face)!!
    private val frontCanvas = view.findViewById<CanvasView>(R.id.feditor_canvas_front)!!
    private val backCanvas = view.findViewById<CanvasView>(R.id.feditor_canvas_back)!!

    private var commentData = SaveString("", false)
    private val brushViews = ArrayList<BrushView>()
    private val loadedData = lifecycle.coroutineScope.async(Dispatchers.IO) { session.openData() }

    init {
      log("Starting editor for ${session}")
      fmarkHost.topSpinnerVisible = true

      view.findViewById<AppCompatImageButton>(R.id.feditor_comment)?.setOnClickListener { switchToComment() }
      view.findViewById<AppCompatImageButton>(R.id.feditor_face).setOnClickListener { switchDrawing(faceCanvas) }
      view.findViewById<AppCompatImageButton>(R.id.feditor_front).setOnClickListener { switchDrawing(frontCanvas) }
      view.findViewById<AppCompatImageButton>(R.id.feditor_back).setOnClickListener { switchDrawing(backCanvas) }

      view.findViewById<TextView>(R.id.feditor_date).text = session.date.toShortString()
      view.findViewById<EditText>(R.id.feditor_comment_text).addAfterTextChangedListener { text ->
        if (text != commentData.string) {
          fmarkHost.cloudButton.signalDirty()
          commentData = SaveString(text, dirty = true)
        }
      }

      val palette = view.findViewById<LinearLayout>(R.id.feditor_palette)
      for (i in 0 until palette.childCount) {
        val child = palette.getChildAt(i)
        if (child is BrushView) brushViews.add(child)
      }
      brushViews.forEach {
        it.setOnClickListener { v ->
          val bv = v as BrushView
          faceCanvas.brush = bv.changeBrush(faceCanvas.brush)
          frontCanvas.brush = bv.changeBrush(frontCanvas.brush)
          backCanvas.brush = bv.changeBrush(backCanvas.brush)
          brushViews.forEach { brushView -> brushView.isActivated = brushView.isActive(faceCanvas.brush) }
        }
      }
      canvasFlipper.flipTo(faceCanvas)
      brushViews.forEach { it.isActivated = it.isActive(faceCanvas.brush) }

      lifecycle.coroutineScope.launch(Dispatchers.Main) {
        log("Loading editor data for ${session}...")
        val data = loadedData.await()
        log("Session loaded, comment = ${data.comment}, face data has ${data[FACE_CODE].data.size} data points")
        val commentView = view.findViewById<EditText>(R.id.feditor_comment_text)
        commentData = SaveString(data.comment, dirty = false)
        commentView.setText(data.comment)
        faceCanvas.readData(data[FACE_CODE].data)
        frontCanvas.readData(data[FRONT_CODE].data)
        backCanvas.readData(data[BACK_CODE].data)
        faceCanvas.addOnChangeListener(this@Impl)
        frontCanvas.addOnChangeListener(this@Impl)
        backCanvas.addOnChangeListener(this@Impl)
        fmarkHost.topSpinnerVisible = false
      }
    }

    override fun onCanvasChanged() = fmarkHost.cloudButton.signalDirty()

    fun onBackPressed() {
      log("Back pressed, quitting")
      fmarkHost.topSpinnerVisible = true
      lifecycleScope.launch(Dispatchers.Main) {
        save()?.join()
        log("Data saved, exiting")
        if (isVisible) {
          fmarkHost.supportFragmentManager.popBackStack()
          fmarkHost.topSpinnerVisible = false
        }
      }
    }

    fun onPause() = save()

    private fun switchToComment() {
      log("switchToComment")
      commentContainer.visibility = View.VISIBLE
    }

    private fun switchDrawing(canvas : CanvasView) {
      log("switchDrawing ${canvas}")
      commentContainer?.visibility = View.GONE
      canvasFlipper.flipTo(canvas)
    }

    fun onOptionsItemSelected(itemId : Int) : Boolean {
      if (fmarkHost.topSpinnerVisible) {
        log("Option tapped but spinner visible, ignoring")
        return false
      }
      when (itemId) {
        R.id.action_button_save  -> save().also { log("Option save tapped") }
        R.id.action_button_undo  -> (view.findViewById<ViewFlipper>(R.id.feditor_canvas_flipper)?.shownView as CanvasView).undo().also { log("Option undo tapped") }
        R.id.action_button_clear -> (view.findViewById<ViewFlipper>(R.id.feditor_canvas_flipper)?.shownView as CanvasView).clear().also { log("Option clear tapped") }
      }
      return true
    }

    // Only saves dirty data, though the data file contains the comment and all drawing data and has to be saved together anyway
    private fun save() : Job? {
      log("Saving data...")

      val comment = commentData
      commentData = SaveString(comment.string, dirty = false)
      val faceData = faceCanvas.getDrawing()
      val faceBitmap = faceCanvas.getSaveBitmap()
      val frontData = frontCanvas.getDrawing()
      val frontBitmap = frontCanvas.getSaveBitmap()
      val backData = backCanvas.getDrawing()
      val backBitmap = backCanvas.getSaveBitmap()

      // Using lifecyclescope here would mean saving would be interrupted as soon as the fragment is paused.
      // This is pretty bad when the home button is pressed. It also means the save indicator can't be set
      // to showOk() because that message has to be thrown on the main thread and by the time it would be
      // processed when exiting this editor this fragment has been terminated.
      log("Launching save job on IO pool, comment = ${if (comment.dirty) comment.string else "//not dirty//"}, face = ${faceBitmap}, front = ${frontBitmap}, back = ${backBitmap}")
      if (!comment.dirty && faceBitmap == null && frontBitmap == null && backBitmap == null)
        return null
      return GlobalScope.launch(Dispatchers.IO) {
        try {
          val tasks = mutableListOf<Deferred<Unit>>()
          tasks.add(async { session.saveData(SessionData(comment.string, faceData, frontData, backData))}).also { log("Saving data") }
          if (comment.dirty)
            tasks.add(async { session.saveComment(comment.string) }).also { log("Saving comment") }
          if (faceBitmap != null) tasks.add(async { session.saveImage(faceBitmap, faceCanvas.fileName) }).also { log("Saving face") }
          if (frontBitmap != null) tasks.add(async { session.saveImage(frontBitmap, frontCanvas.fileName) }).also { log("Saving front") }
          if (backBitmap != null) tasks.add(async { session.saveImage(backBitmap, backCanvas.fileName) }).also { log("Saving back") }
          log("Waiting for ${tasks.size} to finish")
          tasks.forEach { it.await() }
          log("Data saved successfully.")
        } catch (e : ApiException) {
          log("Error saving data", e)
        }
      }
    }

    /* ***** PROBLÈMES *****
    * ✓ L'image n'a ni le fond, ni le drawable, uniquement le dessin par dessus
    * ✓ L'image n'est pas sauvegardée quand il faut parce que dirty est mis à jour quand on change de dessin au lieu de quand on le touche ce qui est complètement con
    * ✓ La sauvegarde est horriblement lente parce que :
    *   ✓ les ids des DriveFile ne sont pas cachés, ce qui fait qu'il faut 800ms pour choper l'ID avant de pouvoir uploader le fichier
    *   ✓ les images sont uploadées séquentiellement au lieu de parallèlement
    *   ✗ par défaut l'upload est multipart au lieu de media, ce qui fait 2 requêtes au lieu d'une... pas pris en charge par l'API
    * ✓ Les thumbnails et les previews ne marchent pas sur drive, parce qu'apparemment ce connard de drive se repère uniquement à l'extension et pas au type mime
    * ⑤ Si tu coupes le réseau pendant que ça rame, ça plante parce que filelist().execute (ou autre) throw ConnectException
    * ⑥ Il semblerait que dans certains cas la session est vide (et écrase la bonne session) mais je ne sais pas quand
    * ✓ L'icône de save reste affichée quand un dessin est dirty
    */
  }
}
