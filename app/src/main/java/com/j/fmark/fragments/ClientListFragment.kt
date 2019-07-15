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
import com.j.fmark.ClientAdapter
import com.j.fmark.FMark
import com.j.fmark.R
import com.j.fmark.fdrive.ClientFolder
import com.j.fmark.fdrive.FMarkRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A fragment implementing the client list.
 */
class ClientListFragment(private val fmarkHost : FMark, private val root : FMarkRoot) : Fragment(), TextWatcher
{
  private var searchField : EditText? = null
  private val currentSearchLock = Object()
  private var currentSearch : Job? = null
    set(newSearch) = synchronized(currentSearchLock) { field?.cancel(); field = newSearch }
  private var refreshRequested = false

  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View
  {
    val view = inflater.inflate(R.layout.fragment_client_list, container, false)
    val field = view.findViewById<EditText>(R.id.client_name_search).also { it.addTextChangedListener(this) }
    searchField = field
    view.findViewById<FloatingActionButton>(R.id.client_add).setOnClickListener {
      fmarkHost.showClientDetails(null, root)
    }
    return view
  }

  override fun onResume()
  {
    super.onResume()
    fmarkHost.insertSpinnerVisible = true
    populateClientList(0L)
  }

  fun refresh()
  {
    fmarkHost.insertSpinnerVisible = true
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
    val searchString = searchField?.text?.toString() ?: return
    startSearch {
      if (delayMs > 0) delay(delayMs)
      try
      {
        if (refreshRequested)
        {
          root.clearCache()
          refreshRequested = false
        }
        readClients(if (searchString.isEmpty() or searchString.isBlank()) null else searchString)
      }
      catch (e : ApiException) // TODO : is this still useful ?
      {
        // Can't reach Google servers
        fmarkHost.offlineError(R.string.fail_data_fetch)
      }
      fmarkHost.insertSpinnerVisible = false
    }
  }

  private suspend fun readClients(searchString : String?)
  {
    val context = context ?: return
    val view = view ?: return

    val clientList = root.clientList(searchString, exactMatch = false)

    val list = view.findViewById<RecyclerView>(R.id.client_list)
    if (null == list.adapter)
    {
      list.addItemDecoration(DividerItemDecoration(context, (list.layoutManager as LinearLayoutManager).orientation))
      list.adapter = ClientAdapter(clientList, this)
    }
    else
      (list.adapter as ClientAdapter).setSource(clientList)
  }

  fun showClientDetails(clientFolder : ClientFolder) = fmarkHost.showClientDetails(clientFolder, root)
  fun startClientEditor(clientFolder : ClientFolder) = fmarkHost.startClientEditor(clientFolder)
  fun notifyRenamed(clientFolder : ClientFolder) = (view?.findViewById<RecyclerView>(R.id.client_list)?.adapter as ClientAdapter?)?.notifyRenamed(clientFolder)
}
