package com.j.fmark

import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.View
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.drive.Drive
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.j.fmark.drive.FDrive
import com.j.fmark.drive.SignInException
import com.j.fmark.drive.renameFolder
import com.j.fmark.fragments.ClientDetails
import com.j.fmark.fragments.ClientList
import com.j.fmark.fragments.FEditor
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.launch
import java.util.Locale

class FMark : AppCompatActivity()
{
  override fun onCreate(icicle : Bundle?)
  {
    super.onCreate(null)
    setContentView(R.layout.activity_fmark)
    GlobalScope.launch(Dispatchers.Main) {
      val account = FDrive.getAccount(this@FMark, GOOGLE_SIGN_IN_CODE)
      if (null != account)
      {
        val driveResourceClient = Drive.getDriveResourceClient(this@FMark, account)
        findViewById<View>(R.id.main_loading).visibility = View.GONE
        supportFragmentManager.beginTransaction().replace(R.id.main_fragment, ClientList(this@FMark, driveResourceClient)).commit()
      } // Otherwise, wait for sign in activity → onActivityResult
    }
    supportFragmentManager.addOnBackStackChangedListener {
      val fragment = supportFragmentManager.fragments.last()
      when (fragment) {
        is ClientList -> supportActionBar?.setTitle(R.string.titlebar_main)
        is ClientDetails -> supportActionBar?.setTitle(R.string.titlebar_client_details)
        is FEditor -> supportActionBar?.setTitle(String.format(Locale.getDefault(), getString(R.string.titlebar_editor), fragment.name))
      }
    }
  }

  override fun onActivityResult(requestCode : Int, resultCode : Int, data : Intent?)
  {
    super.onActivityResult(requestCode, resultCode, data)
    if (GOOGLE_SIGN_IN_CODE != requestCode) return // How did the control get here ?
    onSignIn(data)
  }

  private fun onSignIn(data : Intent?)
  {
    val signInResult = Auth.GoogleSignInApi.getSignInResultFromIntent(data)
    val account = signInResult.signInAccount
    if (!signInResult.isSuccess || null == account) throw SignInException(getString(R.string.sign_in_fail_eventual))
    val driveResourceClient = Drive.getDriveResourceClient(this@FMark, account)
    findViewById<View>(R.id.main_loading).visibility = View.GONE
    supportFragmentManager.beginTransaction().replace(R.id.main_fragment, ClientList(this@FMark, driveResourceClient)).commit()
  }

  fun showClientDetails(driveResourceClient : DriveResourceClient, client : Metadata?)
  {
    val f = ClientDetails(this, driveResourceClient, client)
    val transaction = supportFragmentManager.beginTransaction()
     .addToBackStack(null)
    f.show(transaction, "details")
  }

  fun startEditor(driveResourceClient : DriveResourceClient, clientFolder : Metadata) =
   supportFragmentManager.beginTransaction().addToBackStack("editor").replace(R.id.main_fragment,FEditor(this, driveResourceClient, clientFolder), "editor").commit()

  suspend fun renameClient(driveResourceClient : DriveResourceClient, clientFolder : Metadata, name : String, reading : String)
  {
    val renamedFolder = renameFolder(driveResourceClient, clientFolder.driveId.asDriveFolder(), name, reading) ?: return
    supportFragmentManager.fragments.forEach {
      if (it is ClientList) it.notifyRenamed(renamedFolder)
    }
  }
}
