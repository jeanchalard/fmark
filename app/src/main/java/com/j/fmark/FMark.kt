package com.j.fmark

import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.app.AppCompatActivity
import android.view.View
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.drive.Drive
import com.google.android.gms.drive.DriveFolder
import com.google.android.gms.drive.MetadataChangeSet
import com.j.fmark.drive.FDrive
import com.j.fmark.drive.SignInException
import com.j.fmark.fragments.ClientDetails
import com.j.fmark.fragments.ClientList
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
        val client = Drive.getDriveResourceClient(this@FMark, account)
        switchToFragment(ClientList(this@FMark, client))
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
    val client = Drive.getDriveResourceClient(this@FMark, account)
    switchToFragment(ClientList(this@FMark, client))
  }

  fun showClientDetails(client : DriveFolder?)
  {
    val f = ClientDetails(this, client)
    val transaction = supportFragmentManager.beginTransaction()
     .addToBackStack(null)
    f.show(transaction, "details")
  }

  private fun getFolderChangeset(name : String?, reading : String?) : MetadataChangeSet?
  {
    if (null == name || null == reading) return null
    return MetadataChangeSet.Builder()
     .setTitle(name)
     .setDescription(reading)
     .setIndexableText("${name} - ${reading}")
     .setMimeType(DriveFolder.MIME_TYPE)
     .build()
  }

  private fun onCreateClient(data : Intent)
  {
//    val client = client ?: throw NoDriveClientException("No DriveClient trying to create client record")
//    GlobalScope.launch(Dispatchers.Main) {
//      val spinner = findViewById<Spinner>(R.id.client_list_spinner)
//      spinner.visibility = VISIBLE
//      val fmarkFolder = FDrive.getFMarkFolder(client, this@FMark)
//      val changeSet = getFolderChangeset(data.getStringExtra(EXTRA_KEY_NAME), data.getStringExtra(EXTRA_KEY_READING)) ?: throw IllegalArgumentException("Name or reading incorrect in trying to create client record")
//      val folder = client.createFolder(fmarkFolder, changeSet).await()
//      Lancer le fragment d'édition
//      spinner.visibility = GONE
//    }
  }
}
