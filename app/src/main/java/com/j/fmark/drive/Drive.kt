package com.j.fmark.drive

import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.drive.*
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.Query
import com.google.android.gms.drive.query.SearchableField
import com.j.fmark.R
import kotlinx.coroutines.experimental.tasks.await
import java.util.concurrent.ExecutionException

object FDrive
{

  suspend fun getAccount(c : Activity, resultCode : Int) : GoogleSignInAccount?
  {
    val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
     .requestScopes(Drive.SCOPE_FILE)
     .build()
    val client = GoogleSignIn.getClient(c, options)
    val signInTask = client.silentSignIn()
    try
    {
      if (signInTask.isSuccessful) return signInTask.result ?: throw SignInException(c.getString(R.string.sign_in_fail_instant))
      return signInTask.await() ?: throw SignInException(c.getString(R.string.sign_in_fail_eventual))
    }
    catch (e : Exception)
    {
      when (e)
      {
        is ExecutionException, is ApiException -> c.startActivityForResult(client.signInIntent, resultCode)
      }
    }
    return null
  }

  suspend fun getFMarkFolder(client : DriveResourceClient, context : Context) : DriveFolder
  {
    return getFolder(client, context.getString(R.string.fmark_root_directory))
  }

  suspend fun getFolder(client : DriveResourceClient, name : String) : DriveFolder
  {
    var currentFolder = client.rootFolder.await()
    name.split(Regex("/")).forEach {
      val buffer : MetadataBuffer = client.query(Query.Builder()
       .addFilter(Filters.eq(SearchableField.TITLE, it))
       .addFilter(Filters.`in`(SearchableField.PARENTS, currentFolder.driveId))
       .addFilter(Filters.eq(SearchableField.TRASHED, false))
       .build()).await()
      if (0 == buffer.count) currentFolder = client.createFolder(currentFolder, newFolderChangeset(it)).await()
      else if (1 != buffer.count) throw FolderNotUniqueException(name)
      else
      {
        val b = buffer[0]
        if (!b.isFolder) throw NotAFolderException(b.title)
        currentFolder = b.driveId.asDriveFolder()
      }
    }
    return currentFolder
  }

  private fun newFolderChangeset(name : String) : MetadataChangeSet = MetadataChangeSet.Builder().setTitle(name).setMimeType(DriveFolder.MIME_TYPE).build()
}
