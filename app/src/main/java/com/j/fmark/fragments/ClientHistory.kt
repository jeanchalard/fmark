package com.j.fmark.fragments

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.j.fmark.CanvasView
import com.j.fmark.DBGLOG
import com.j.fmark.FMark
import com.j.fmark.LocalSecond
import com.j.fmark.R
import com.j.fmark.SessionData
import com.j.fmark.codeToResource
import com.j.fmark.fdrive.ClientFolder
import com.j.fmark.fdrive.SessionFolder
import com.j.fmark.log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class LoadSession(val session : SessionFolder, val data : Deferred<SessionData>)

val EMPTY_SESSION = object : SessionFolder {
  override val date : LocalSecond get() = LocalSecond(System.currentTimeMillis())
  override val lastUpdateDate : LocalSecond get() = LocalSecond(System.currentTimeMillis())
  override suspend fun openData() : SessionData = SessionData()
  override suspend fun saveData(data : SessionData) {}
  override suspend fun saveComment(comment : String) {}
  override suspend fun saveImage(image : Bitmap, fileName : String) {}
}

class ClientHistory(private val fmarkHost : FMark, private val clientFolder : ClientFolder) : Fragment() {
  val name get() = clientFolder.name

  // Main thread only
  private var currentJob : Job? = null

  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View? {
    val view = inflater.inflate(R.layout.fragment_client_history, container, false)
    view.findViewById<LinearLayout>(R.id.new_session_button).setOnClickListener {
      GlobalScope.launch {
        val session = clientFolder.newSession()
        launch(Dispatchers.Main) {
          fmarkHost.startSessionEditor(session)
        }
      }
    }
    populateClientHistory(view)
    return view
  }

  override fun onResume() {
    super.onResume()
    view?.let { populateClientHistory(it) }
  }

  private fun populateClientHistory(view : View) {
    GlobalScope.launch(Dispatchers.Main) {
      // This works though currentJob is not locked because this always runs on the
      // main thread courtesy of Dispatchers.Main. If it was not the case the
      // insertSpinnerVisible = true instruction would crash, too.
      if (null == currentJob) {
        fmarkHost.insertSpinnerVisible = true
        currentJob = launch {
          val sessions = clientFolder.getSessions()
          val inProgress = sessions.map { session -> LoadSession(session, async(start = CoroutineStart.LAZY) { session.openData() }) }
          if (inProgress.isNotEmpty()) inProgress.first().data.start()
          withContext(Dispatchers.Main) {
            val list = view.findViewById<RecyclerView>(R.id.client_history)
            if (null == list.adapter) {
              list.addItemDecoration(DividerItemDecoration(context, (list.layoutManager as LinearLayoutManager).orientation))
              list.adapter = ClientHistoryAdapter(this@ClientHistory, inProgress)
            } else (list.adapter as ClientHistoryAdapter).setSource(inProgress)
            fmarkHost.insertSpinnerVisible = false
            currentJob = null
          }
        }
      }
    }
  }

  fun startSessionEditor(sessionFolder : SessionFolder) = fmarkHost.startSessionEditor(sessionFolder)
}

private class ClientHistoryAdapter(private val parent : ClientHistory, private var source : List<LoadSession>) : RecyclerView.Adapter<ClientHistoryAdapter.Holder>() {
  class Holder(private val adapter : ClientHistoryAdapter, view : View) : RecyclerView.ViewHolder(view), View.OnClickListener {
    init { view.setOnClickListener(this) }

    private val dateLabel : TextView = view.findViewById(R.id.client_history_date)
    private val loadingView : ProgressBar = view.findViewById(R.id.client_history_loading)
    private val commentTextView : TextView = view.findViewById(R.id.client_history_comment)
    private val lastUpdateLabel : TextView = view.findViewById(R.id.client_history_last_update)
    private val faceImage : CanvasView = view.findViewById<CanvasView>(R.id.client_history_face).also { it.setImageResource(R.drawable.face) }
    private val frontImage : CanvasView = view.findViewById<CanvasView>(R.id.client_history_front).also { it.setImageResource(R.drawable.front) }
    private val backImage : CanvasView = view.findViewById<CanvasView>(R.id.client_history_back).also { it.setImageResource(R.drawable.back) }

    var session : LoadSession = LoadSession(EMPTY_SESSION, CompletableDeferred())
      set(session) {
        field = session
        if (DBGLOG) log("Setting session ${session.session.date} in ${this}")
        session.data.invokeOnCompletion { populate(session) }
        if (!session.data.isActive) session.data.start().also { log("Start loading ${session.session.date} now") }
      }

    private fun populate(session : LoadSession) {
      if (DBGLOG) log("Populating with ${session.session.date} ${if (session.data.isCompleted) session.data.getCompleted().comment else session.data.toString()}")
      dateLabel.text = if (session.session !== EMPTY_SESSION) session.session.date.toShortString() else ""
      val lastUpdateDateString = if (session.session !== EMPTY_SESSION) session.session.lastUpdateDate.toShortString() else ""
      lastUpdateLabel.text = String.format(Locale.getDefault(), lastUpdateLabel.context.getString(R.string.update_time_with_placeholder), lastUpdateDateString)
      if (session.data.isCompleted) {
        val completedData = session.data.getCompleted()
        commentTextView.text = completedData.comment
        faceImage.setImageResource(codeToResource(completedData.face.code))
        faceImage.readData(completedData.face.data)
        frontImage.setImageResource(codeToResource(completedData.front.code))
        frontImage.readData(completedData.front.data)
        backImage.setImageResource(codeToResource(completedData.back.code))
        backImage.readData(completedData.back.data)
        commentTextView.visibility = View.VISIBLE
        loadingView.visibility = View.INVISIBLE
      } else loadingView.visibility = View.VISIBLE
    }

    override fun onClick(v : View?) {
      adapter.startClientEditor(session.session)
    }
  }

  override fun getItemCount() : Int = source.size
  override fun onCreateViewHolder(parent : ViewGroup, viewType : Int) : Holder = Holder(this, LayoutInflater.from(parent.context).inflate(R.layout.client_history_view, parent, false))
  override fun onBindViewHolder(holder : Holder, position : Int) {
    holder.session = source[position]
  }

  fun setSource(source : List<LoadSession>) {
    this.source = source
    notifyDataSetChanged()
  }

  fun startClientEditor(sessionFolder : SessionFolder) = parent.startSessionEditor(sessionFolder)
}
