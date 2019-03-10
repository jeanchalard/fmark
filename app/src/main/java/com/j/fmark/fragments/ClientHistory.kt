package com.j.fmark.fragments

import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.j.fmark.CanvasView
import com.j.fmark.DATA_FILE_NAME
import com.j.fmark.FMark
import com.j.fmark.LocalSecond
import com.j.fmark.R
import com.j.fmark.SessionData
import com.j.fmark.drive.createSessionForClient
import com.j.fmark.drive.decodeName
import com.j.fmark.drive.decodeSessionDate
import com.j.fmark.drive.findFile
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

private val EMPTY_METADATA = object : Metadata() {
  override fun <T : Any?> zza(p0 : MetadataField<T>?) : T = throw NotImplementedError()
  override fun freeze() : Metadata = this
  override fun isDataValid() : Boolean = false
}
private fun Metadata?.isEmpty() = this == null || this === EMPTY_METADATA
private fun InputStream?.decodeSessionData() = if (null == this) SessionData() else SessionData(this)
private fun t() = Thread.currentThread()


private class Poke
{
  interface Pokable { fun poke() }
  @Volatile var listener : Pokable? = null
  fun poke() = listener?.poke()
}

private data class Session(val folder : Metadata, val poke : Poke, val data : Deferred<SessionData>)

class ClientHistory(private val fmarkHost : FMark, private val driveApi : DriveResourceClient, private val driveRefreshClient : DriveClient, private val clientFolder : Metadata) : Fragment()
{
  val name = decodeName(clientFolder)

  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View?
  {
    val view = inflater.inflate(R.layout.fragment_client_history, container, false)
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

  override fun onResume()
  {
    super.onResume()
    Log.e("wat", "> ${view}")
    view?.let { populateClientHistory(it) }
  }

  private suspend fun loadData(file : DriveFile?) : SessionData =
    if (null != file) driveApi.openFile(file, DriveFile.MODE_READ_ONLY)?.await()?.inputStream.decodeSessionData()
    else SessionData()

  private fun populateClientHistory(view : View)
  {
    fmarkHost.insertSpinnerVisible = true
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
          val sessionContents = driveApi.findFile(sessionFolder, DATA_FILE_NAME)
          loadData(sessionContents).also { poke.poke() }
        })
      }
      if (inProgress.isNotEmpty()) inProgress.first().data.start()

      GlobalScope.launch(Dispatchers.Main) {
        val list = view.findViewById<RecyclerView>(R.id.client_history)
        if (null == list.adapter)
        {
          list.addItemDecoration(DividerItemDecoration(context, (list.layoutManager as LinearLayoutManager).orientation))
          list.adapter = ClientHistoryAdapter(this@ClientHistory, inProgress)
        }
        else (list.adapter as ClientHistoryAdapter).setSource(inProgress)
        fmarkHost.insertSpinnerVisible = false
      }
    }
  }

  fun startSessionEditor(sessionFolder : Metadata) = fmarkHost.startSessionEditor(driveApi, driveRefreshClient, sessionFolder)
}

private class ClientHistoryAdapter(private val parent : ClientHistory, private var source : List<Session>) : RecyclerView.Adapter<ClientHistoryAdapter.Holder>()
{
  class Holder(private val adapter : ClientHistoryAdapter, view : View) : RecyclerView.ViewHolder(view), View.OnClickListener, Poke.Pokable
  {
    init { view.setOnClickListener(this) }

    private val dateLabel : TextView = view.findViewById(R.id.client_history_date)
    private val loadingView : ProgressBar = view.findViewById(R.id.client_history_loading)
    private val commentTextView : TextView = view.findViewById(R.id.client_history_comment)
    private val lastUpdateLabel : TextView = view.findViewById(R.id.client_history_last_update)
    private val faceImage : CanvasView = view.findViewById<CanvasView>(R.id.client_history_face).also { it.setImageResource(R.drawable.face) }
    private val frontImage : CanvasView = view.findViewById<CanvasView>(R.id.client_history_front).also { it.setImageResource(R.drawable.front) }
    private val backImage : CanvasView = view.findViewById<CanvasView>(R.id.client_history_back).also { it.setImageResource(R.drawable.back) }

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
          if (data.data.isCompleted)
          {
            val completedData = data.data.getCompleted()
            commentTextView.text = completedData.comment
            faceImage.setImageResource(completedData.face.guideId)
            faceImage.readData(completedData.face.data)
            frontImage.setImageResource(completedData.front.guideId)
            frontImage.readData(completedData.front.data)
            backImage.setImageResource(completedData.back.guideId)
            backImage.readData(completedData.back.data)
            commentTextView.visibility = View.VISIBLE
            loadingView.visibility = View.INVISIBLE
          } else loadingView.visibility = View.VISIBLE
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
    notifyDataSetChanged()
  }

  fun startClientEditor(sessionFolder : Metadata) = parent.startSessionEditor(sessionFolder)
}