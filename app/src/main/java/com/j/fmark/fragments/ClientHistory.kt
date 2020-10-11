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
import com.j.fmark.FMark
import com.j.fmark.LOGEVERYTHING
import com.j.fmark.LocalSecond
import com.j.fmark.R
import com.j.fmark.SessionData
import com.j.fmark.codeToResource
import com.j.fmark.fdrive.ClientFolder
import com.j.fmark.fdrive.SessionFolder
import com.j.fmark.logAlways
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("ClientHistory", s, e) }

private data class LoadSession(val session : SessionFolder, val data : Flow<SessionData>)

val EMPTY_SESSION = object : SessionFolder {
  override val date : LocalSecond get() = throw IllegalStateException("Must not call EMPTY_SESSION#date")
  override val lastUpdateDate : LocalSecond get() = throw IllegalStateException("Must not call EMPTY_SESSION#lastUpdateDate")
  override val path : String get() = throw IllegalStateException("Must not call EMPTY_SESSION#path")
  override suspend fun openData() = emptyFlow<SessionData>()
  override suspend fun saveData(data : SessionData) {}
  override suspend fun saveComment(comment : String) {}
  override suspend fun saveImage(image : Bitmap, fileName : String) {}
}

class ClientHistory(private val fmarkHost : FMark, private val clientFolder : ClientFolder) : Fragment() {
  val name get() = clientFolder.name

  // Main thread only
  private var currentJob : Job? = null

  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View? {
    log("onCreateView icicle = ${savedInstanceState}")
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
    log("onResume")
    view?.let { populateClientHistory(it) }
  }

  private fun populateClientHistory(view : View) {
    GlobalScope.launch(Dispatchers.Main) {
      log("populateClientHistory, currentJob = ${currentJob}")
      // This works though currentJob is not locked because this always runs on the
      // main thread courtesy of Dispatchers.Main. If it was not the case the
      // insertSpinnerVisible = true instruction would crash, too.
      if (null == currentJob) {
        fmarkHost.insertSpinnerVisible = true
        currentJob = launch {
          log("populateClientHistory job getting sessions")
          clientFolder.getSessions().collect { sessions ->
            log("populateClientHistory job obtained sessions with count ${sessions.count}")
            val inProgress = sessions.map { session -> LoadSession(session, session.openData()) }
            withContext(Dispatchers.Main) {
              val list = view.findViewById<RecyclerView>(R.id.client_history)
              log("Adding adapter")
              if (null == list.adapter) {
                list.addItemDecoration(DividerItemDecoration(context, (list.layoutManager as LinearLayoutManager).orientation))
                list.adapter = ClientHistoryAdapter(this@ClientHistory, inProgress)
              } else (list.adapter as ClientHistoryAdapter).setSource(inProgress)
              fmarkHost.insertSpinnerVisible = false
              currentJob = null
              log("Client history populated")
            }
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

    var sessionField = LoadSession(EMPTY_SESSION, emptyFlow()) to SupervisorJob()
    var session : LoadSession
      set(session) {
        sessionField.second.cancel()
        sessionField = session to SupervisorJob()
        CoroutineScope(Dispatchers.Main + sessionField.second).launch {
          dateLabel.text = ""
          loadingView.visibility = View.VISIBLE
          session.data.collect {
            populate(session.session, it) // Whether this is from cache or network or network after cache, just update.
          }
        }
      }
      get() = sessionField.first

    private fun populate(session : SessionFolder, data : SessionData) {
      log("Populating with ${session.date} ${data.comment}")
      dateLabel.text = session.date.toShortString()
      val lastUpdateDateString = if (session !== EMPTY_SESSION) session.lastUpdateDate.toShortString() else ""
      lastUpdateLabel.text = String.format(Locale.getDefault(), lastUpdateLabel.context.getString(R.string.update_time_with_placeholder), lastUpdateDateString)
      log("Populated, comment = ${data.comment}")
      commentTextView.text = data.comment
      faceImage.setImageResource(codeToResource(data.face.code))
      faceImage.readData(data.face.data)
      frontImage.setImageResource(codeToResource(data.front.code))
      frontImage.readData(data.front.data)
      backImage.setImageResource(codeToResource(data.back.code))
      backImage.readData(data.back.data)
      commentTextView.visibility = View.VISIBLE
      loadingView.visibility = View.INVISIBLE
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
    log("setSource with size ${source.size}")
    this.source = source
    notifyDataSetChanged()
  }

  fun startClientEditor(sessionFolder : SessionFolder) = parent.startSessionEditor(sessionFolder)
}
