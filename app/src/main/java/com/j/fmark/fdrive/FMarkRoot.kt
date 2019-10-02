package com.j.fmark.fdrive

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.drive.DriveClient
import com.google.android.gms.drive.DriveFolder
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.Query
import com.google.android.gms.drive.query.SearchableField
import com.google.android.gms.drive.query.SortOrder
import com.google.android.gms.drive.query.SortableField
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.j.fmark.CREATION_DATE_FILE_NAME
import com.google.api.services.drive.model.File as DriveFile
import com.j.fmark.R
import com.j.fmark.fdrive.FDrive.encodeClientFolderName
import com.j.fmark.toBytes
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

interface FMarkRoot {
  suspend fun clearCache()
  suspend fun clientList(searchString : String? = null, exactMatch : Boolean = true) : ClientFolderList
  suspend fun createClient(name : String, reading : String) : ClientFolder
}

fun RESTFMarkRoot(context : Context, account : GoogleSignInAccount) : RESTFMarkRoot = RESTFMarkRoot.make(context, account)
class RESTFMarkRoot private constructor(private val root : File, private val drive : Drive, private val rootFolder : Deferred<DriveFile>) : FMarkRoot {
  companion object {
    fun make(context : Context, account : GoogleSignInAccount) : RESTFMarkRoot {
      val credential = GoogleAccountCredential.usingOAuth2(context, arrayListOf(DriveScopes.DRIVE_FILE))
      credential.selectedAccount = account.account
      val drive = Drive.Builder(NetHttpTransport(), GsonFactory(), credential)
       .setApplicationName(context.getString(R.string.gservices_app_name))
       .build()
      val folder = GlobalScope.async(Dispatchers.IO) { FDrive.getFolder(drive, context.getString(R.string.fmark_root_directory)) }
      return RESTFMarkRoot(context.cacheDir.resolve(context.getString(R.string.fmark_root_directory)), drive, folder)
    }
  }

  val cachedClientList = RESTClientFolderList(root, drive)

  override suspend fun createClient(name : String, reading : String) : ClientFolder = withContext(Dispatchers.IO) {
    val command = CreateClientCommand(encodeClientFolderName(name, reading))
    addCommand(command)
    val now = System.currentTimeMillis()
    cachedClientList.createClient(name, reading, createdDate = now, modifiedDate = now)
  }

  override suspend fun clientList(searchString : String?, exactMatch : Boolean) : ClientFolderList = withContext(Dispatchers.IO) {
    RESTClientFolderList(drive, FDrive.getFolders(drive, rootFolder, searchString, exactMatch))
  }

  override suspend fun clearCache() {
    throw RuntimeException("Cache not implemented yet, implement it")
  }

  fun addCommand(command : RESTCommand) {
  }
}

suspend fun LocalDiskFMarkRoot(context : Context) : LocalDiskFMarkRoot = LocalDiskFMarkRoot.make(context)
class LocalDiskFMarkRoot private constructor (private val context : Context, private val root : File) : FMarkRoot {
  companion object {
    suspend fun make(context : Context) : LocalDiskFMarkRoot = LocalDiskFMarkRoot(context, context.cacheDir.resolve(context.getString(R.string.fmark_root_directory)))
  }

  init {
    android.util.Log.e("ROOT", root.toString())
    if (!root.exists()) root.mkdir()
  }

  override suspend fun clearCache() = Unit // No cache for local files
  override suspend fun clientList(searchString : String?, exactMatch : Boolean) : LocalDiskClientFolderList =
   LocalDiskClientFolderList(if (searchString == null)
     root.listFiles().toList()
   else
     root.listFiles().filter { if (exactMatch) it.name == searchString else it.name.contains(searchString) })
  override suspend fun createClient(name : String, reading : String) : LocalDiskClientFolder = LocalDiskClientFolder(root.resolve(encodeClientFolderName(name, reading)))
}

suspend fun LegacyFMarkRoot(context : Context, account : GoogleSignInAccount) : LegacyFMarkRoot = LegacyFMarkRoot.make(context, account)
class LegacyFMarkRoot private constructor(private val driveClient : DriveClient, private val resourceClient : DriveResourceClient, private val rootFolder : DriveFolder) : FMarkRoot {
  companion object {
    suspend fun make(context : Context, account : GoogleSignInAccount) : LegacyFMarkRoot {
      val resourceClient = com.google.android.gms.drive.Drive.getDriveResourceClient(context, account)
      val driveClient = com.google.android.gms.drive.Drive.getDriveClient(context, account)
      val rootFolder = FDrive.getFolder(resourceClient, context.getString(R.string.fmark_root_directory))
      return LegacyFMarkRoot(driveClient, resourceClient, rootFolder)
    }
  }

  override suspend fun clearCache() {
    driveClient.requestSync()?.await()
  }

  override suspend fun clientList(searchString : String?, exactMatch : Boolean) : ClientFolderList {
    val query = Query.Builder().apply {
      if (null != searchString)
        addFilter(if (exactMatch) Filters.eq(SearchableField.TITLE, searchString) else Filters.contains(SearchableField.TITLE, searchString))
      addFilter(Filters.eq(SearchableField.TRASHED, false))
      setSortOrder(SortOrder.Builder().addSortAscending(SortableField.TITLE).build())
    }.build()
    return LegacyClientFolderList(resourceClient.queryChildren(rootFolder, query).await(), resourceClient)
  }

  override suspend fun createClient(name : String, reading : String) : ClientFolder {
    val folder = resourceClient.createFolder(rootFolder, FDrive.metadataForClient(name, reading)).await()
    return LegacyClientFolder(resourceClient.getMetadata(folder).await(), resourceClient)
  }
}
