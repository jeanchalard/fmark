package com.j.fmark.fragments

import android.os.Bundle
import android.support.design.widget.FloatingActionButton
import android.support.v4.app.Fragment
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.google.android.gms.drive.DriveFolder
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.query.*
import com.j.fmark.ClientAdapter
import com.j.fmark.FMark
import com.j.fmark.R
import com.j.fmark.drive.FDrive
import com.j.fmark.drive.FDrive.getFMarkFolder
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.tasks.await

/**
 * A fragment implementing the client list.
 */
class ClientList(private val fmarkHost : FMark, private val client : DriveResourceClient) : Fragment(), TextWatcher
{
  private lateinit var searchField : EditText
  private val currentSearchLock = Object()
  private var currentSearch : Job? = null
    set(newSearch) = synchronized(currentSearchLock) { field?.cancel(); field = newSearch }

  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View
  {
    val view = inflater.inflate(R.layout.fragment_client_list, container, false)
    searchField = view.findViewById(R.id.client_name_search)
    searchField.addTextChangedListener(this)
    view.findViewById<FloatingActionButton>(R.id.client_add).setOnClickListener {
      fmarkHost.showClientDetails(client, null)
    }
    return view
  }

  override fun onResume()
  {
    super.onResume()
    populateClientList(0L)
  }

  override fun afterTextChanged(s : Editable?) = Unit
  override fun beforeTextChanged(s : CharSequence?, start : Int, count : Int, after : Int) = Unit
  override fun onTextChanged(s : CharSequence?, start : Int, before : Int, count : Int) = populateClientList(if (currentSearch == null) 0 else 1000)

  private fun startSearch(start: CoroutineStart = CoroutineStart.DEFAULT, block: suspend CoroutineScope.() -> Unit)
  {
    currentSearch = GlobalScope.launch(Dispatchers.Main, start, { block(); currentSearch = null })
  }

  private fun populateClientList(delayMs : Long)
  {
    val searchString = searchField.text?.toString() ?: return
    startSearch {
      if (delayMs > 0) delay(delayMs)
      readClients(if (searchString.isEmpty() or searchString.isBlank()) null else searchString)
    }
  }

  private suspend fun readClients(searchString : String?)
  {
    val context = context ?: return
    val view = view ?: return

    val folder = FDrive.getFMarkFolder(client, context)
    val query = Query.Builder().apply {
      if (null != searchString) addFilter(Filters.contains(SearchableField.TITLE, searchString))
      addFilter(Filters.eq(SearchableField.TRASHED, false))
      setSortOrder(SortOrder.Builder().addSortAscending(SortableField.TITLE).build())
    }.build()
    val result = client.queryChildren(folder, query).await()
    val list = view.findViewById<RecyclerView>(R.id.client_list)
    if (null == list.adapter)
    {
      list.addItemDecoration(DividerItemDecoration(context, (list.layoutManager as LinearLayoutManager).orientation))
      list.adapter = ClientAdapter(result, this)
    }
    else (list.adapter as ClientAdapter).setSource(result)
  }

  fun startEditor(clientFolder : Metadata) = fmarkHost.startEditor(client, clientFolder)
}
