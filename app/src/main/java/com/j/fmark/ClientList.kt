package com.j.fmark

import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.drive.Drive
import com.j.fmark.drive.FDrive
import com.j.fmark.drive.SignInException
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.tasks.await

class ClientList : AppCompatActivity()
{
  companion object
  {
    const val GOOGLE_SIGN_IN_CODE = 1
  }

  override fun onCreate(icicle : Bundle?)
  {
    super.onCreate(icicle)
    setContentView(R.layout.activity_client_list)
    GlobalScope.launch(Dispatchers.Main)
    {
      val account = FDrive.getAccount(this@ClientList, GOOGLE_SIGN_IN_CODE)
      if (null != account) populateClientList(account)
    }
  }

  override fun onActivityResult(requestCode : Int, resultCode : Int, data : Intent?)
  {
    super.onActivityResult(requestCode, resultCode, data)
    if (GOOGLE_SIGN_IN_CODE != requestCode) return // How the hell did we get here ?
    val signInResult = Auth.GoogleSignInApi.getSignInResultFromIntent(data)
    if (signInResult.isSuccess)
    {
      val account = signInResult.signInAccount
      if (null != account) populateClientList(account)
      return
    }
    throw SignInException(getString(R.string.sign_in_fail_eventual))
  }

  fun populateClientList(account : GoogleSignInAccount)
  {
    GlobalScope.launch(Dispatchers.Main)
    {
      val client = Drive.getDriveResourceClient(this@ClientList, account)
      FDrive.getFMarkFolder(client, this@ClientList)
    }
  }
}
