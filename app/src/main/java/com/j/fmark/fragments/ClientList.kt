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
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.drive.DriveClient
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.Query
import com.google.android.gms.drive.query.SearchableField
import com.google.android.gms.drive.query.SortOrder
import com.google.android.gms.drive.query.SortableField
import com.j.fmark.ClientAdapter
import com.j.fmark.FMark
import com.j.fmark.R
import com.j.fmark.drive.FDrive
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.tasks.await

/**
 * A fragment implementing the client list.
 */
class ClientList(private val fmarkHost : FMark, private val driveClient : DriveResourceClient, private val refreshClient : DriveClient) : Fragment(), TextWatcher
{
  private lateinit var searchField : EditText
  private val currentSearchLock = Object()
  private var currentSearch : Job? = null
    set(newSearch) = synchronized(currentSearchLock) { field?.cancel(); field = newSearch }
  private var refreshRequested = true

  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View
  {
    val view = inflater.inflate(R.layout.fragment_client_list, container, false)
    view.clipToOutline = true // This should be set in XML but a bug in the resource parser makes it impossible
    searchField = view.findViewById(R.id.client_name_search)
    searchField.addTextChangedListener(this)
    view.findViewById<FloatingActionButton>(R.id.client_add).setOnClickListener {
      fmarkHost.showClientDetails(driveClient, refreshClient, null)
    }
    return view
  }

  override fun onResume()
  {
    super.onResume()
    fmarkHost.spinnerVisible = true
    populateClientList(0L)
  }

  fun refresh()
  {
    fmarkHost.spinnerVisible = true
    refreshRequested = true
    populateClientList(0L)
  }

  override fun afterTextChanged(s : Editable?) = Unit
  override fun beforeTextChanged(s : CharSequence?, start : Int, count : Int, after : Int) = Unit
  override fun onTextChanged(s : CharSequence?, start : Int, before : Int, count : Int) = populateClientList(if (currentSearch == null) 0 else 1000)

  private fun startSearch(start: CoroutineStart = CoroutineStart.DEFAULT, block: suspend CoroutineScope.() -> Unit)
  {
    currentSearch = GlobalScope.launch(Dispatchers.Main, start) { block(); currentSearch = null }
  }

  // If refresh is requested, this function will request a sync to make sure the data is fresh.
  private fun populateClientList(delayMs : Long)
  {
    val searchString = searchField.text?.toString() ?: return
    startSearch {
      if (delayMs > 0) delay(delayMs)
      try
      {
        if (refreshRequested)
        {
          refreshClient.requestSync()?.await()
          refreshRequested = false
        }
        readClients(if (searchString.isEmpty() or searchString.isBlank()) null else searchString)
      }
      catch (e : ApiException)
      {
        // Can't reach Google servers
        fmarkHost.offlineError(R.string.fail_data_fetch)
      }
      fmarkHost.spinnerVisible = false
    }
  }

  private suspend fun readClients(searchString : String?)
  {
    val context = context ?: return
    val view = view ?: return

    val folder = FDrive.getFMarkFolder(driveClient, context)
    val query = Query.Builder().apply {
      if (null != searchString) addFilter(Filters.contains(SearchableField.TITLE, searchString))
      addFilter(Filters.eq(SearchableField.TRASHED, false))
      setSortOrder(SortOrder.Builder().addSortAscending(SortableField.TITLE).build())
    }.build()
    val result = driveClient.queryChildren(folder, query).await()
    val list = view.findViewById<RecyclerView>(R.id.client_list)
    if (null == list.adapter)
    {
      list.addItemDecoration(DividerItemDecoration(context, (list.layoutManager as LinearLayoutManager).orientation))
      list.adapter = ClientAdapter(result, this)
    }
    else (list.adapter as ClientAdapter).setSource(result)
  }

  fun showClientDetails(clientFolder : Metadata) = fmarkHost.showClientDetails(driveClient, refreshClient, clientFolder)
  fun startClientEditor(clientFolder : Metadata) = fmarkHost.startClientEditor(driveClient, refreshClient, clientFolder)
  fun startSessionEditor(sessionFolder : Metadata) = fmarkHost.startSessionEditor(driveClient, refreshClient, sessionFolder)
  fun notifyRenamed(clientFolder : Metadata) = (view?.findViewById<RecyclerView>(R.id.client_list)?.adapter as ClientAdapter?)?.notifyRenamed(clientFolder)
}
