package com.j.fmark.fdrive

import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.drive.DriveFolder
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.MetadataBuffer
import com.google.android.gms.drive.MetadataChangeSet
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.Query
import com.google.android.gms.drive.query.SearchableField
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.j.fmark.LocalSecond
import com.j.fmark.R
import com.j.fmark.drive.encodeClientFolderName
import com.j.fmark.drive.encodeIndexableText
import com.j.fmark.drive.encodeSessionFolderName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException

object FDrive
{
  suspend fun getAccount(c : Activity, resultCode : Int) : GoogleSignInAccount?
  {
    val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
     .requestScopes(com.google.android.gms.drive.Drive.SCOPE_FILE)
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

  suspend fun getFMarkFolder(drive : Drive, context : Context) : File = withContext(Dispatchers.Default) {
    FDrive.getFolder(drive, context.getString(R.string.fmark_root_directory))
  }

  suspend fun getFolders(drive : Drive, parentFolder : File, name : String? = null, exactMatch : Boolean = true) : List<File> {
    val constraints = mutableListOf("trashed = false", "mimeType = '$FOLDER_MIME_TYPE'", "'${parentFolder.id}' in parents")
    if (null != name) constraints.add("name ${if (exactMatch) "is" else "contains"} '${name}'")
    return drive.files().list().apply {
      q = constraints.joinToString(" and ")
      orderBy = "name"
      spaces = "drive"
    }.executeFully()
  }
  private suspend fun getFolder(drive : Drive, name : String) : File
  {
    var folder = File().setId("root")
    name.split(Regex("/")).forEach { component ->
      val filelist = drive.files().list()
       .setQ("name = '${component}' and '${folder.id}' in parents and trashed = false and mimeType = '$FOLDER_MIME_TYPE'")
       .execute()?.files
      folder = when
      {
        null == filelist || filelist.size == 0 -> createFolder(drive, folder, component)
        filelist.size == 1                     -> filelist[0]
        else                                   -> throw FolderNotUniqueException(name)
      }
    }
    return folder
  }
  private suspend fun createFolder(drive : Drive, parentFolder : File, name : String) : File
  {
    val newFile = File().setName(name).setMimeType(FOLDER_MIME_TYPE).setParents(listOf(parentFolder.id))
    drive.files().create(newFile).setFields("id, parents").execute()
    return newFile
  }


  // Legacy API stuff
  private fun newFolderChangeset(name : String) : MetadataChangeSet = MetadataChangeSet.Builder().setTitle(name).setMimeType(DriveFolder.MIME_TYPE).build()

  suspend fun getFolder(resourceClient : DriveResourceClient, name : String) : DriveFolder
  {
    var currentFolder = resourceClient.rootFolder.await()
    name.split(Regex("/")).forEach {
      val buffer : MetadataBuffer = resourceClient.query(Query.Builder()
       .addFilter(Filters.eq(SearchableField.TITLE, it))
       .addFilter(Filters.`in`(SearchableField.PARENTS, currentFolder.driveId))
       .addFilter(Filters.eq(SearchableField.TRASHED, false))
       .build()).await()
      currentFolder = when
      {
        1 != buffer.count -> throw FolderNotUniqueException(name)
        0 == buffer.count -> resourceClient.createFolder(currentFolder, newFolderChangeset(it)).await()
        else              -> buffer[0].let { metadata -> if (!metadata.isFolder) throw NotAFolderException(metadata.title) else metadata.driveId.asDriveFolder() }
      }
    }
    return currentFolder
  }

  fun metadataForClient(name : String, reading : String) : MetadataChangeSet = MetadataChangeSet.Builder()
   .setTitle(encodeClientFolderName(name, reading))
   .setDescription(reading)
   .setIndexableText(encodeIndexableText(name, reading))
   .setMimeType(DriveFolder.MIME_TYPE)
   .build()

  fun metadataForSession(date : LocalSecond) : MetadataChangeSet = MetadataChangeSet.Builder()
   .setTitle(encodeSessionFolderName(date))
   .setDescription(date.toString())
   .setMimeType(DriveFolder.MIME_TYPE)
   .build()
}
