package com.j.fmark.fdrive

import com.j.fmark.LOGEVERYTHING
import com.j.fmark.logAlways
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("FMarkRoot", s, e) }

interface FMarkRoot {
  suspend fun clearCache()
  suspend fun clientList(searchString : String? = null, exactMatch : Boolean = true) : StateFlow<ClientFolderList>
  suspend fun createClient(name : String, reading : String, comment : String) : ClientFolder
}

suspend fun RESTFMarkRoot(root : FDrive.Root) = RESTFMarkRoot(root, RESTClientFolderList(root).flow.stateIn(CoroutineScope(Dispatchers.IO)))

class RESTFMarkRoot internal constructor(private val root : FDrive.Root, private val clientList : StateFlow<RESTClientFolderList>) : FMarkRoot {
  override suspend fun createClient(name : String, reading : String, comment : String) : ClientFolder = withContext(Dispatchers.IO) {
    log("Create client ${name} - ${reading} (${comment})")
    clientList.value.createClient(name, reading, comment)
  }

  override suspend fun clientList(searchString : String?, exactMatch : Boolean) : StateFlow<ClientFolderList> = withContext(Dispatchers.IO) {
    log("Client list, search ${searchString}")
    if (searchString != null) {
      RESTClientFolderList(root, searchString, exactMatch).flow.stateIn(this)
    } else
      clientList
  }

  override suspend fun clearCache() {
    root.cache.deleteRecursively()
  }
}
