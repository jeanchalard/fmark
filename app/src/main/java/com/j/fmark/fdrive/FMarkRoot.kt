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
import com.google.android.gms.dynamic.DeferredLifecycleHelper
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.j.fmark.CACHE_DIR
import com.j.fmark.ErrorHandling
import com.j.fmark.R
import com.j.fmark.SAVE_QUEUE_DIR
import com.j.fmark.fdrive.FDrive.encodeClientFolderName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.stream.Stream.iterate
import com.google.api.services.drive.model.File as DriveFile

interface FMarkRoot {
  suspend fun clearCache()
  suspend fun clientList(searchString : String? = null, exactMatch : Boolean = true) : ClientFolderList
  suspend fun createClient(name : String, reading : String, comment : String) : ClientFolder
}

suspend fun RESTFMarkRoot(context : Context) : RESTFMarkRoot {
  val root = FDrive.Root(context)
  return RESTFMarkRoot(root)
}
class RESTFMarkRoot internal constructor(private val root : FDrive.Root) : FMarkRoot {
  private val clientList = CoroutineScope(Dispatchers.IO).async(start = CoroutineStart.LAZY) { RESTClientFolderList(root) }

  override suspend fun createClient(name : String, reading : String, comment : String) : ClientFolder = withContext(Dispatchers.IO) {
    clientList.await().createClient(name, reading, comment)
  }

  override suspend fun clientList(searchString : String?, exactMatch : Boolean) : ClientFolderList = withContext(Dispatchers.IO) {
    if (searchString != null)
      RESTClientFolderList(root, searchString, exactMatch)
    else
      clientList.await()
  }

  override suspend fun clearCache() {
    root.cache.deleteRecursively()
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
  override suspend fun createClient(name : String, reading : String, comment : String) : LocalDiskClientFolder =
   LocalDiskClientFolder(root.resolve(encodeClientFolderName(name, reading, comment)))
}
