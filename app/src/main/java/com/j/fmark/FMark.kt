package com.j.fmark

import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.app.AppCompatActivity
import android.view.View
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.drive.Drive
import com.google.android.gms.drive.DriveFolder
import com.google.android.gms.drive.DriveResourceClient
import com.j.fmark.drive.FDrive
import com.j.fmark.drive.FDrive.getFolder
import com.j.fmark.drive.SignInException
import com.j.fmark.drive.createFolderForClientName
import com.j.fmark.fragments.ClientDetails
import com.j.fmark.fragments.ClientList
import com.j.fmark.fragments.FEditor
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.launch

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
        switchToFragment(ClientList(this@FMark, driveResourceClient))
      } // Otherwise, wait for sign in activity → onActivityResult
    }
  }

  private fun switchToFragment(f : Fragment)
  {
    findViewById<View>(R.id.main_loading).visibility = View.GONE
    supportFragmentManager.beginTransaction()
     .replace(R.id.main_fragment, f)
     .commit()
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
    switchToFragment(ClientList(this@FMark, driveResourceClient))
  }

  fun showClientDetails(driveResourceClient : DriveResourceClient, client : DriveFolder?)
  {
    val f = ClientDetails(this, driveResourceClient, client)
    val transaction = supportFragmentManager.beginTransaction()
     .addToBackStack(null)
    f.show(transaction, "details")
  }

  suspend fun startEditor(driveResourceClient : DriveResourceClient, fmarkFolder : DriveFolder, name : String, reading : String) =
   startEditor(driveResourceClient, createFolderForClientName(driveResourceClient, fmarkFolder, name, reading))

  fun startEditor(driveResourceClient : DriveResourceClient, clientFolder : DriveFolder) =
   switchToFragment(FEditor(driveResourceClient, clientFolder))
}
