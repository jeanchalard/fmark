package com.j.fmark

import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.drive.Drive
import com.google.android.gms.drive.query.*
import com.j.fmark.drive.FDrive
import com.j.fmark.drive.SignInException
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.tasks.await

class ClientList : AppCompatActivity(), TextWatcher
{
  companion object
  {
    const val GOOGLE_SIGN_IN_CODE = 1
  }

  private var account : GoogleSignInAccount? = null
  private lateinit var searchField : EditText

  override fun onCreate(icicle : Bundle?)
  {
    super.onCreate(icicle)
    setContentView(R.layout.activity_client_list)
    searchField = findViewById(R.id.client_name_search)
    searchField.addTextChangedListener(this)
    GlobalScope.launch(Dispatchers.Main)
    {
      account = FDrive.getAccount(this@ClientList, GOOGLE_SIGN_IN_CODE)
      populateClientList(0)
    }
  }

  override fun onActivityResult(requestCode : Int, resultCode : Int, data : Intent?)
  {
    super.onActivityResult(requestCode, resultCode, data)
    if (GOOGLE_SIGN_IN_CODE != requestCode) return // How the hell did we get here ?
    val signInResult = Auth.GoogleSignInApi.getSignInResultFromIntent(data)
    if (signInResult.isSuccess)
    {
      account = signInResult.signInAccount
      populateClientList(0)
      return
    }
    throw SignInException(getString(R.string.sign_in_fail_eventual))
  }

  private fun populateClientList(delayMs : Long)
  {
    val currentAccount = account ?: return
    val searchString = searchField.text?.toString() ?: return
    GlobalScope.launch(Dispatchers.Main)
    {
      if (delayMs > 0) delay(delayMs)
      readClients(currentAccount, if (searchString.isEmpty() or searchString.isBlank()) null else searchString)
    }
  }

  private suspend fun readClients(account : GoogleSignInAccount, searchString : String?)
  {
    val client = Drive.getDriveResourceClient(this@ClientList, account)
    val folder = FDrive.getFMarkFolder(client, this@ClientList)
    val query = Query.Builder().apply {
      if (null != searchString) addFilter(Filters.contains(SearchableField.TITLE, searchString))
      addFilter(Filters.eq(SearchableField.TRASHED, false))
      setSortOrder(SortOrder.Builder().addSortAscending(SortableField.TITLE).build())
    }.build()
    val result = client.queryChildren(folder, query).await()
    val list = findViewById<RecyclerView>(R.id.client_list)
    if (null == list.adapter)
    {
      list.addItemDecoration(DividerItemDecoration(this@ClientList, (list.layoutManager as LinearLayoutManager).orientation))
      list.adapter = ClientAdapter(result)
    }
    else (list.adapter as ClientAdapter).setSource(result)
  }

  override fun afterTextChanged(s : Editable?) = Unit
  override fun beforeTextChanged(s : CharSequence?, start : Int, count : Int, after : Int) = Unit
  override fun onTextChanged(s : CharSequence?, start : Int, before : Int, count : Int) = populateClientList(1500)
}
