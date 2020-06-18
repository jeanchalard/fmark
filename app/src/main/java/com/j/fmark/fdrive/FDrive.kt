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
import com.google.api.services.drive.model.File as DriveFile
import com.j.fmark.LocalSecond
import com.j.fmark.R
import com.j.fmark.parseLocalSecond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException

const val NECESSARY_FIELDS = "id,name,parents,createdTime,modifiedTime"
const val NECESSARY_FIELDS_EXPRESSION = "files(${NECESSARY_FIELDS})"

object FDrive {
  fun encodeClientFolderName(name : String, reading : String) = "${name} -- ${reading}"
  fun encodeSessionFolderName(date : LocalSecond) = date.toString()
  fun encodeIndexableText(name : String, reading : String) = "${name} ${reading}"

  fun decodeName(folderName : String) = if (folderName.indexOf("--") != -1) folderName.split(" -- ")[0] else folderName
  fun decodeReading(folderName : String) = if (folderName.indexOf("--") != -1) folderName.split(" -- ")[1] else folderName
  fun decodeSessionFolderName(name : String) = parseLocalSecond(name)

  suspend fun getAccount(c : Activity, resultCode : Int) : GoogleSignInAccount? {
    val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
     .requestScopes(com.google.android.gms.drive.Drive.SCOPE_FILE)
     .requestEmail()
     .build()
    val client = GoogleSignIn.getClient(c, options)
    val signInTask = client.silentSignIn()
    try {
      if (signInTask.isSuccessful) return signInTask.result ?: throw SignInException(c.getString(R.string.sign_in_fail_instant))
      return signInTask.await() ?: throw SignInException(c.getString(R.string.sign_in_fail_eventual))
    } catch (e : Exception) {
      when (e) {
        is ExecutionException, is ApiException -> c.startActivityForResult(client.signInIntent, resultCode)
      }
    }
    return null
  }

  suspend fun getFMarkFolder(drive : Drive, context : Context) : DriveFile = withContext(Dispatchers.Default) {
    getFolder(drive, context.getString(R.string.fmark_root_directory))
  }

  suspend fun getFolders(drive : Drive, parentFolder : DriveFile, name : String? = null, exactMatch : Boolean = false) : List<DriveFile> {
    val constraints = mutableListOf("trashed = false", "mimeType = '$FOLDER_MIME_TYPE'", "'${parentFolder.id}' in parents")
    if (null != name) constraints.add("name ${if (exactMatch) "=" else "contains"} '${name}'")
    return drive.files().list().apply {
      q = constraints.joinToString(" and ")
      fields = NECESSARY_FIELDS_EXPRESSION
      orderBy = "name"
      spaces = "drive"
    }.executeFully()
  }

  suspend fun getFolder(drive : Drive, name : String) : DriveFile {
    var folder = DriveFile().setId("root")
    MainScope().launch(Dispatchers.IO) {
      name.split(Regex("/")).forEach { component ->
        val filelist = drive.files().list()
         .setQ("name = '${component}' and '${folder.id}' in parents and trashed = false and mimeType = '$FOLDER_MIME_TYPE'")
         .setFields(NECESSARY_FIELDS_EXPRESSION)
         .execute()?.files
        folder = when {
          null == filelist || filelist.size == 0 -> createFolder(drive, folder, component)
          filelist.size == 1                     -> filelist[0]
          else                                   -> throw NotUniqueException(name)
        }
      }
    }.join()
    return folder
  }

  // TODO : factor together with the above
  suspend fun getDriveFile(drive : Drive, name : String, parentFolder : DriveFile) : DriveFile {
    val filelist = drive.files().list()
     .setQ("name = '${name}' and '${parentFolder.id}' in parents and trashed = false")
     .setFields(NECESSARY_FIELDS_EXPRESSION)
     .execute()?.files
    return when {
      null == filelist || filelist.size == 0 -> createFile(drive, parentFolder, name)
      filelist.size == 1                     -> filelist[0]
      else                                   -> throw NotUniqueException(name)
    }
  }

  suspend fun createFolder(drive : Drive, parentFolder : DriveFile, name : String) : DriveFile = createFile(drive, parentFolder, name, FOLDER_MIME_TYPE)
  suspend fun createFile(drive : Drive, parentFolder : DriveFile, name : String, mimeType : String = BINDATA_MIME_TYPE) : DriveFile {
    val now = com.google.api.client.util.DateTime(System.currentTimeMillis())
    val newFile = DriveFile().apply {
      this.name = name
      this.mimeType = mimeType
      this.parents = listOf(parentFolder.id)
      this.modifiedTime = now
      this.createdTime = now
    }
    return drive.files().create(newFile).setFields(NECESSARY_FIELDS).execute()
  }
}
