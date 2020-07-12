package com.j.fmark

import com.j.fmark.fdrive.FDrive.Root
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import com.google.api.services.drive.model.File as DriveFile

@OptIn(ExperimentalContracts::class)
private inline fun <T> Deferred<T?>?.isValid() : Boolean {
  contract { returns(true) implies (this@isValid != null) }
  return this != null && (!isCompleted || getCompleted() != null)
}
// Don't call this getOrPut to avoid overlapping the standard lib function name
private inline fun <T> MutableMap<String, Deferred<T?>>.getOrCompute(key: String, defaultValue: () -> Deferred<T?>) : Deferred<T?> = synchronized(this) {
  val value = get(key)
  return if (value.isValid())
    value
  else {
    val answer = defaultValue()
    put(key, answer)
    answer
  }
}

/**
 * A cache that's useful to store objects that should be remembered across the app as long as it's
 * up, but can be refetched at any time from the network.
 */
object LiveCache {
  private val lock = ReentrantLock()

  private var root : Deferred<Root>? = null
  suspend fun getRoot(create : suspend () -> Root) = lock.withLock { root ?: GlobalScope.async { create() }.also { root = it } }.await()

  // Key : <parent id>/<name>, because the parent of course never knows its path
  private val files : HashMap<String, Deferred<DriveFile?>> = HashMap()
  suspend fun getFile(name : String, create : suspend () -> DriveFile) : DriveFile {
    while (true) {
      getFileOrNull(name, create)?.let { return it }
    }
  }
  suspend fun getFileOrNull(name : String, create : suspend () -> DriveFile?) : DriveFile? {
    val result = files.getOrCompute(name) { GlobalScope.async { create() } }
    return result.await()
  }

  suspend fun getFile(parentFolder : DriveFile, name : String, create : suspend () -> DriveFile) =
   getFile("${parentFolder.id}/${name}", create)
  suspend fun getFileOrNull(parentFolder : DriveFile, name : String, create : suspend () -> DriveFile?) =
   getFileOrNull("${parentFolder.id}/${name}", create)

  private val lists : HashMap<String, Deferred<List<DriveFile>>> = HashMap()
  suspend fun getFileList(folder : DriveFile, read : suspend () -> List<DriveFile>) =
   synchronized(lists) { lists.getOrPut(folder.id) { GlobalScope.async { read() } } }.await()

  private val sessions : HashMap<String, Deferred<SessionData>> = HashMap()
  suspend fun getSession(file : DriveFile, read : suspend () -> SessionData) =
   synchronized(sessions) { sessions.getOrPut(file.id) { GlobalScope.async { read() } } }.await()
}
