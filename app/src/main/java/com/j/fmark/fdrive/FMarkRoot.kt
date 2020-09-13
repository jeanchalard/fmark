package com.j.fmark.fdrive

import android.content.Context
import com.j.fmark.LOGEVERYTHING
import com.j.fmark.R
import com.j.fmark.fdrive.FDrive.encodeClientFolderName
import com.j.fmark.logAlways
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.Exception

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("FMarkRoot", s, e) }

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
    log("Create client ${name} -- ${reading} (${comment})")
    clientList.await().createClient(name, reading, comment)
  }

  override suspend fun clientList(searchString : String?, exactMatch : Boolean) : ClientFolderList = withContext(Dispatchers.IO) {
    log("Client list, search ${searchString}")
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
