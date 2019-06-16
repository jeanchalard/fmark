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
import com.j.fmark.R
import kotlinx.coroutines.tasks.await

interface FMarkRoot
{
  suspend fun clearCache()
  suspend fun clientList(searchString : String? = null, exactMatch : Boolean = true) : ClientFolderList
  suspend fun createClient(name : String, reading : String) : ClientFolder
}

/*
class RESTFMarkRoot(context : Context, account : GoogleSignInAccount) : FMarkRoot
{
  companion object
  {
    suspend fun make(context : Context, account : GoogleSignInAccount) = RESTFMarkRoot(context, account)
  }

  private val drive : Drive = run {
    val credential = GoogleAccountCredential.usingOAuth2(context, arrayListOf(DriveScopes.DRIVE_FILE))
    credential.selectedAccount = account.account
    Drive.Builder(NetHttpTransport(), GsonFactory(), credential)
     .setApplicationName(context.getString(R.string.gservices_app_name))
     .build()
  }
  override suspend fun clearCache() {}
}
*/

suspend fun LegacyFMarkRoot(context : Context, account : GoogleSignInAccount) : LegacyFMarkRoot = LegacyFMarkRoot.make(context, account)
class LegacyFMarkRoot private constructor(private val driveClient : DriveClient, private val resourceClient : DriveResourceClient, private val rootFolder : DriveFolder) : FMarkRoot
{
  companion object
  {
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

  override suspend fun clientList(searchString : String?, exactMatch : Boolean) : ClientFolderList
  {
    val query = Query.Builder().apply {
      if (null != searchString)
        addFilter(if (exactMatch) Filters.eq(SearchableField.TITLE, searchString) else Filters.contains(SearchableField.TITLE, searchString))
      addFilter(Filters.eq(SearchableField.TRASHED, false))
      setSortOrder(SortOrder.Builder().addSortAscending(SortableField.TITLE).build())
    }.build()
    return LegacyClientFolderList(resourceClient.queryChildren(rootFolder, query).await(), resourceClient)
  }

  override suspend fun createClient(name : String, reading : String) : ClientFolder
  {
    val folder = resourceClient.createFolder(rootFolder, FDrive.metadataForClient(name, reading)).await()
    return LegacyClientFolder(resourceClient.getMetadata(folder).await(), resourceClient)
  }
}
