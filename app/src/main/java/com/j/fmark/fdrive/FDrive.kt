package com.j.fmark.fdrive

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.j.fmark.CACHE_DIR
import com.j.fmark.ErrorHandling
import com.j.fmark.GOOGLE_SIGN_IN_CODE
import com.j.fmark.LiveCache
import com.j.fmark.LocalSecond
import com.j.fmark.R
import com.j.fmark.SAVE_QUEUE_DIR
import com.j.fmark.parseLocalSecond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutionException
import com.google.api.services.drive.model.File as DriveFile

const val NECESSARY_FIELDS = "id,name,parents,createdTime,modifiedTime"
const val NECESSARY_FIELDS_EXPRESSION = "files(${NECESSARY_FIELDS})"

object FDrive {
  data class Root(val context : Context, val account : Account, val drive : Drive, val root : DriveFile, val cache : File, val saveQueue : File, val rest : RESTManager)
  private fun String.escape() = replace("'", "\\'")

  fun encodeClientFolderName(name : String, reading : String, comment : String) = "${name} -- ${reading} -- ${comment}"
  fun encodeSessionFolderName(date : LocalSecond) = date.toString()
  fun encodeIndexableText(name : String, reading : String, comment : String) = "${name} ${reading} ${comment}"

  fun decodeName(folderName : String) = if (folderName.indexOf("--") != -1) folderName.split(" -- ")[0] else folderName
  fun decodeReading(folderName : String) = if (folderName.indexOf("--") != -1) folderName.split(" -- ")[1] else folderName
  fun decodeComment(folderName : String) = if (folderName.indexOf("--") != -1) folderName.split(" -- ")[2] else folderName
  fun decodeSessionFolderName(name : String) = parseLocalSecond(name)

  suspend fun Root(context : Context) : Root = Root(context, fetchAccount(context, GOOGLE_SIGN_IN_CODE)?.account ?: throw SignInException("Can't get account"))

  suspend fun Root(context : Context, account : Account) : Root {
    val credential = GoogleAccountCredential.usingOAuth2(context, arrayListOf(DriveScopes.DRIVE_FILE))
    credential.selectedAccount = account
    val drive = Drive.Builder(NetHttpTransport(), GsonFactory(), credential)
     .setApplicationName(context.getString(R.string.gservices_app_name))
     .build()
    val folder = createDriveFolder(drive, name = context.getString(R.string.fmark_root_directory), parentFolder = DriveFile().also { it.id = "root" })
    val cache = context.cacheDir.resolve(CACHE_DIR).also { if (!it.mkdirs()) ErrorHandling.fileSystemIsNotWritable() }
    val saveQueue = context.filesDir.resolve(SAVE_QUEUE_DIR).also { if (!it.mkdirs()) ErrorHandling.fileSystemIsNotWritable() }
    return Root(context, account, drive, folder, cache, saveQueue, RESTManager(context))
  }

  private suspend fun fetchAccount(context : Context, resultCode : Int) : GoogleSignInAccount? {
    val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
     .requestScopes(com.google.android.gms.drive.Drive.SCOPE_FILE)
     .requestEmail()
     .build()
    val client = GoogleSignIn.getClient(context, options)
    val signInTask = client.silentSignIn()
    try {
      if (signInTask.isSuccessful) return signInTask.result ?: throw SignInException(context.getString(R.string.sign_in_fail_instant))
      return signInTask.await() ?: throw SignInException(context.getString(R.string.sign_in_fail_eventual))
    } catch (e : Exception) {
      when (e) {
        is ExecutionException, is ApiException ->
          if (context is Activity) context.startActivityForResult(client.signInIntent, resultCode)
          else Log.e("Clients", "Cannot UI sign in without an activity")
      }
    }
    return null
  }

  private suspend fun fetchFolderList(drive : Drive, parentFolder : DriveFile, name : String? = null, exactMatch : Boolean = false) : List<DriveFile> {
    val constraints = mutableListOf("trashed = false", "mimeType = '$FOLDER_MIME_TYPE'", "'${parentFolder.id}' in parents")
    if (null != name) constraints.add("name ${if (exactMatch) "=" else "contains"} '${name.escape()}'")
    return drive.files().list().apply {
      q = constraints.joinToString(" and ")
      fields = NECESSARY_FIELDS_EXPRESSION
      orderBy = "name"
      spaces = "drive"
    }.executeFully()
  }
  suspend fun getFolderList(drive : Drive, parentFolder : DriveFile, name : String? = null, exactMatch : Boolean = false) : List<DriveFile> =
   if (null == name)
     LiveCache.getFileList(parentFolder) { fetchFolderList(drive, parentFolder) }
   else
     fetchFolderList(drive, parentFolder, name, exactMatch)

  private suspend fun createDriveLeaf(drive : Drive, parentFolder : DriveFile, name : String, mimeType : String) : DriveFile {
    val now = com.google.api.client.util.DateTime(System.currentTimeMillis())
    val newFile = DriveFile().apply {
      this.name = name
      this.mimeType = mimeType
      this.parents = listOf(parentFolder.id)
      this.modifiedTime = now
      this.createdTime = now
    }
    return withContext(Dispatchers.IO) { drive.files().create(newFile).setFields(NECESSARY_FIELDS).execute() }
  }

  private suspend fun fetchLeaf(drive : Drive, parentFolder : DriveFile, name : String, folder : Boolean) : DriveFile? = LiveCache.getFileOrNull(parentFolder, name) {
    val q = "name = '${name.escape()}' and '${parentFolder.id}' in parents and trashed = false" + if (folder) " and mimeType = '$FOLDER_MIME_TYPE'" else ""
    val filelist = drive.files().list()
       .setQ(q)
       .setFields(NECESSARY_FIELDS_EXPRESSION)
       .execute()?.files
    when {
        null == filelist || filelist.size == 0 -> null
        filelist.size == 1                     -> filelist[0]
        else                                   -> throw NotUniqueException(name)
      }
  }
  private suspend fun fetchOrCreateLeaf(drive : Drive, parentFolder : DriveFile, name : String, folder : Boolean) : DriveFile =
    fetchLeaf(drive, parentFolder, name, folder) ?: createDriveLeaf(drive, parentFolder, name, if (folder) FOLDER_MIME_TYPE else BINDATA_MIME_TYPE)
  private suspend fun fetchLeaf(drive : Drive, parentFolder : DriveFile, name : String, folder : Boolean, create : Boolean) : DriveFile? =
   if (create) fetchOrCreateLeaf(drive, parentFolder, name, folder) else fetchLeaf(drive, parentFolder, name, folder)

  private tailrec suspend fun List<String>.fetchChildren(index : Int, drive : Drive, parentFolder : DriveFile, isFolder : Boolean, create : Boolean) : DriveFile? {
    val name = this[index]
    return if (index == size - 1) fetchLeaf(drive, parentFolder, name, isFolder, create)
    else fetchChildren(index + 1, drive, fetchLeaf(drive, parentFolder, name, true, create) ?: return null, isFolder, create)
  }

  private suspend fun fetchDriveItem(drive : Drive, parentFolder : DriveFile, name : String, isFolder : Boolean, create : Boolean) = withContext(Dispatchers.IO) {
    name.split("/").fetchChildren(0, drive, parentFolder, isFolder, create)
  }
  suspend fun fetchDriveFolder(drive : Drive, name : String, parentFolder : DriveFile) =
   fetchDriveItem(drive, parentFolder, name, isFolder = true, create = false)
  suspend fun fetchDriveFile(drive : Drive, name : String, parentFolder : DriveFile) =
   fetchDriveItem(drive, parentFolder, name, isFolder = false, create = false)
  suspend fun createDriveFolder(drive : Drive, parentFolder : DriveFile, name : String) : DriveFile =
   fetchDriveItem(drive, parentFolder, name, isFolder = true, create = true)!! // If create, fetchDriveItem never returns null, but contracts{} are experimental and stupidly limited to top-level functions
  suspend fun createDriveFile(drive : Drive, parentFolder : DriveFile, name : String) : DriveFile =
   fetchDriveItem(drive, parentFolder, name, isFolder = false, create = true)!! // If create, fetchDriveItem never returns null, but contracts{} are experimental and stupidly limited to top-level functions

  suspend fun renameFolder(drive : Drive, clientFolder : DriveFile, newName : String) : DriveFile? = withContext(Dispatchers.IO) {
    drive.files().update(clientFolder.id, clientFolder.apply { this.name = newName }).execute()
  }
}
