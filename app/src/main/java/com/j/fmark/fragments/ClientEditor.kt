package com.j.fmark.fragments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.gms.drive.DriveClient
import com.google.android.gms.drive.DriveFile
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.metadata.MetadataField
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.Query
import com.google.android.gms.drive.query.SearchableField
import com.google.android.gms.drive.query.SortOrder
import com.google.android.gms.drive.query.SortableField
import com.j.fmark.BACK_IMAGE_NAME
import com.j.fmark.COMMENT_FILE_NAME
import com.j.fmark.FACE_IMAGE_NAME
import com.j.fmark.FMark
import com.j.fmark.FRONT_IMAGE_NAME
import com.j.fmark.LocalSecond
import com.j.fmark.R
import com.j.fmark.drive.createSessionForClient
import com.j.fmark.drive.decodeName
import com.j.fmark.drive.decodeSessionDate
import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.tasks.await
import java.io.InputStream
import java.util.Locale

val EMPTY_BITMAP : Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
val DEFERRED_EMPTY_BITMAP = CompletableDeferred(EMPTY_BITMAP)
private val EMPTY_METADATA = object : Metadata() {
  override fun <T : Any?> zza(p0 : MetadataField<T>?) : T = throw NotImplementedError()
  override fun freeze() : Metadata = this
  override fun isDataValid() : Boolean = false
}
private val INCOMPLETE_SESSIONDATA = SessionData(CompletableDeferred(), DEFERRED_EMPTY_BITMAP, DEFERRED_EMPTY_BITMAP, DEFERRED_EMPTY_BITMAP)

private fun Metadata?.isEmpty() = this == null || this === EMPTY_METADATA
private fun InputStream?.decodeBitmap() = if (null == this) EMPTY_BITMAP else BitmapFactory.decodeStream(this)
private fun Deferred<String>?.orElse(s : String) = this ?: CompletableDeferred(s)
private fun Deferred<Bitmap>?.orEmpty() = this ?: DEFERRED_EMPTY_BITMAP
private fun t() = Thread.currentThread()



private class Poke
{
  interface Pokable { fun poke() }
  @Volatile var listener : Pokable? = null
  fun poke() = listener?.poke()
}


private data class SessionData(val comment : Deferred<String>, val face : Deferred<Bitmap>, val front : Deferred<Bitmap>, val back : Deferred<Bitmap>)
{
  val hasImages : Boolean get() = face !== DEFERRED_EMPTY_BITMAP || front !== DEFERRED_EMPTY_BITMAP || back !== DEFERRED_EMPTY_BITMAP
}
private data class Session(val folder : Metadata, val poke : Poke, val data : Deferred<SessionData>)


class ClientEditor(private val fmarkHost : FMark, private val driveApi : DriveResourceClient, private val driveRefreshClient : DriveClient, private val clientFolder : Metadata) : Fragment()
{
  val name = decodeName(clientFolder)

  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View?
  {
    val view = inflater.inflate(R.layout.fragment_client_editor, container, false)
    view.setOnKeyListener { v, keycode, event -> if (KeyEvent.KEYCODE_BACK == keycode) { fmarkHost.supportFragmentManager.popBackStack(); true } else false }
    view.findViewById<LinearLayout>(R.id.new_session_button).setOnClickListener {
      GlobalScope.launch {
        val session = createSessionForClient(driveApi, clientFolder.driveId.asDriveFolder(), LocalSecond(System.currentTimeMillis()))
        GlobalScope.launch(Dispatchers.Main) {
          fmarkHost.startSessionEditor(driveApi, driveRefreshClient, session)
        }
      }
    }
    populateClientHistory(view)
    return view
  }

  private suspend fun loadFile(file : DriveFile) = driveApi.openFile(file, DriveFile.MODE_READ_ONLY)?.await()?.inputStream
  private fun loadComment(file : DriveFile, poke : Poke) = GlobalScope.async {
    val res = loadFile(file)?.bufferedReader()?.readText() ?: ""
    poke.poke()
    res
  }
  private fun loadImage(file : DriveFile, poke : Poke) = GlobalScope.async {
    val bitmap = loadFile(file).decodeBitmap()
    poke.poke()
    bitmap
  }

  private fun populateClientHistory(view : View)
  {
    fmarkHost.spinnerVisible = true
    GlobalScope.launch {
      Log.e("Launch ${t()}", "Start loading client history from ${clientFolder.title}")
      val clientFolder = clientFolder.driveId.asDriveFolder()
      val query = Query.Builder().apply {
        addFilter(Filters.eq(SearchableField.TRASHED, false))
        setSortOrder(SortOrder.Builder().addSortDescending(SortableField.TITLE).build())
      }.build()
      val result = driveApi.queryChildren(clientFolder, query).await()
      val inProgress = result.mapNotNull { folderMetadata ->
        if (null == folderMetadata || !folderMetadata.isFolder) return@mapNotNull null
        val sessionFolder = folderMetadata.driveId?.asDriveFolder() ?: return@mapNotNull null
        val poke = Poke()
        Session(folderMetadata, poke, async(start = CoroutineStart.LAZY) {
          val sessionContents = driveApi.queryChildren(sessionFolder, Query.Builder().addFilter(Filters.eq(SearchableField.TRASHED, false)).build()).await()
          var comment : Deferred<String>? = null
          var face : Deferred<Bitmap>? = null
          var front : Deferred<Bitmap>? = null
          var back : Deferred<Bitmap>? = null
          sessionContents.forEach { metadata ->
            if (metadata.isFolder) return@forEach
            val file = metadata.driveId.asDriveFile()
            when (metadata.title)
            {
              COMMENT_FILE_NAME -> comment = loadComment(file, poke)
              FACE_IMAGE_NAME   -> face = loadImage(file, poke)
              FRONT_IMAGE_NAME  -> front = loadImage(file, poke)
              BACK_IMAGE_NAME   -> back = loadImage(file, poke)
            }
          }
          SessionData(comment.orElse(""), face.orEmpty(), front.orEmpty(), back.orEmpty())
        })
      }
      if (inProgress.isNotEmpty()) inProgress.first().data.start()

      GlobalScope.launch(Dispatchers.Main) {
        val list = view.findViewById<RecyclerView>(R.id.client_history)
        if (null == list.adapter)
        {
          list.addItemDecoration(DividerItemDecoration(context, (list.layoutManager as LinearLayoutManager).orientation))
          list.adapter = ClientHistoryAdapter(this@ClientEditor, inProgress)
        }
        else (list.adapter as ClientHistoryAdapter).setSource(inProgress)
        fmarkHost.spinnerVisible = false
      }
    }
  }

  fun startSessionEditor(sessionFolder : Metadata) = fmarkHost.startSessionEditor(driveApi, driveRefreshClient, sessionFolder)
}

private fun Boolean.toVisi() = if (this) View.VISIBLE else View.GONE
private class ClientHistoryAdapter(private val parent : ClientEditor, private var source : List<Session>) : RecyclerView.Adapter<ClientHistoryAdapter.Holder>()
{
  class Holder(private val adapter : ClientHistoryAdapter, view : View) : RecyclerView.ViewHolder(view), View.OnClickListener, Poke.Pokable
  {
    init { view.setOnClickListener(this) }

    private val dateLabel : TextView = view.findViewById(R.id.client_history_date)
    private val commentTextView : TextView = view.findViewById(R.id.client_history_comment)
    private val commentLoading : ProgressBar = view.findViewById(R.id.client_history_comment_loading)
    private val lastUpdateLabel : TextView = view.findViewById(R.id.client_history_last_update)
    private val faceImage : ImageView = view.findViewById(R.id.client_history_face)
    private var faceImageLoading : ProgressBar = view.findViewById(R.id.client_history_face_loading)
    private val frontImage : ImageView = view.findViewById(R.id.client_history_front)
    private val frontImageLoading : ProgressBar = view.findViewById(R.id.client_history_front_loading)
    private val backImage : ImageView = view.findViewById(R.id.client_history_back)
    private val backImageLoading : ProgressBar = view.findViewById(R.id.client_history_back_loading)
    private val imageHolder : LinearLayout = view.findViewById(R.id.client_history_images)

    private var comment : Deferred<String> = CompletableDeferred()
      set(value)
      {
        val completed = value.isCompleted
        commentTextView.text = if (completed) value.getCompleted() else ""
        commentLoading.visibility = (!completed).toVisi()
        commentTextView.visibility = (commentTextView.text == "").toVisi()
      }

    private var face : Deferred<Bitmap> = CompletableDeferred()
      set(value)
      {
        val completed = value.isCompleted
        faceImage.setImageBitmap(if (completed) value.getCompleted() else EMPTY_BITMAP)
        faceImageLoading.visibility = (!completed).toVisi()
      }

    private var front : Deferred<Bitmap> = CompletableDeferred()
      set(value)
      {
        val completed = value.isCompleted
        frontImage.setImageBitmap(if (completed) value.getCompleted() else EMPTY_BITMAP)
        frontImageLoading.visibility = (!completed).toVisi()
      }

    private var back : Deferred<Bitmap> = CompletableDeferred()
      set(value)
      {
        val completed = value.isCompleted
        backImage.setImageBitmap(if (completed) value.getCompleted() else EMPTY_BITMAP)
        backImageLoading.visibility = (!completed).toVisi()
      }

    var session : Session = Session(EMPTY_METADATA, Poke(), CompletableDeferred())
      set(data)
      {
        field = data
        data.poke.listener = this
        if (!data.data.isActive) data.data.start()
        GlobalScope.launch(Dispatchers.Main) {
          dateLabel.text = if (!data.folder.isEmpty()) decodeSessionDate(data.folder).toShortString() else ""
          val lastUpdateDateString = if (!data.folder.isEmpty()) LocalSecond(data.folder.modifiedDate).toString() else ""
          lastUpdateLabel.text = String.format(Locale.getDefault(), lastUpdateLabel.context.getString(R.string.update_time_with_placeholder), lastUpdateDateString)
          val completedData = if (data.data.isCompleted) data.data.getCompleted() else INCOMPLETE_SESSIONDATA
          comment = completedData.comment
          face = completedData.face
          front = completedData.front
          back = completedData.back
          imageHolder.visibility = (completedData.hasImages).toVisi()
        }
      }

    override fun poke() {
      if (session.poke.listener === this) session = session
    }

    override fun onClick(v : View?) { adapter.startClientEditor(session.folder) }
  }

  override fun getItemCount() : Int = source.size
  override fun onCreateViewHolder(parent : ViewGroup, viewType : Int) : Holder = Holder(this, LayoutInflater.from(parent.context).inflate(R.layout.client_history_view, parent, false))
  override fun onBindViewHolder(holder : Holder, position : Int)
  {
    holder.session = source[position]
  }

  fun setSource(source : List<Session>)
  {
    this.source = source
  }

  fun startClientEditor(sessionFolder : Metadata) = parent.startSessionEditor(sessionFolder)
}
