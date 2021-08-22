package com.j.fmark

import com.j.fmark.fdrive.FDrive.Root
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.coroutines.coroutineContext
import com.google.api.services.drive.model.File as DriveFile

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("LiveCache", s, e) }

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
  suspend fun getRoot(create : suspend () -> Root) : Root {
    return lock.withLock {
      val r = root
      if (r != null) {
        log("getRoot, already existed : ${r}")
        r
      } else {
        log("getRoot, creating it")
        GlobalScope.async { create() }.also { log("getRoot : created Root ${it}"); root = it }
      }
    }.await()
  }

  // Key : <parent id>/<name>, because the parent of course never knows its path
  private val files : HashMap<String, Deferred<DriveFile?>> = HashMap()
  private suspend fun getFile(name : String, create : suspend () -> DriveFile) : DriveFile {
    while (true) {
      log("getFile ${name}...")
      getFileOrNull(name, create)?.let { log("Got file : ${it}"); return it }
    }
  }
  private suspend fun getFileOrNull(name : String, create : suspend () -> DriveFile?) : DriveFile? {
    log("Get file from live cache, ${name} : ${files[name]}")
    val result = files.getOrCompute(name) { CoroutineScope(coroutineContext).async { create() } }
    return result.await()
  }

  suspend fun getFile(parentFolder : DriveFile, name : String, create : suspend () -> DriveFile) =
   getFile("${parentFolder.id}/${name}", create)
  suspend fun getFileOrNull(parentFolder : DriveFile, name : String, create : suspend () -> DriveFile?) = getFileOrNull("${parentFolder.id}/${name}", create)

  private val lists : HashMap<String, Deferred<List<DriveFile>>> = HashMap()
  suspend fun getFileList(folder : DriveFile, read : suspend () -> List<DriveFile>) = synchronized(lists) {
     log("getFileList, ${folder.id} : ${lists[folder.id]}")
     lists.getOrPut(folder.id) { GlobalScope.async { read() } }
   }.await()

  private val sessions : HashMap<String, Deferred<SessionData>> = HashMap()
  suspend fun getSession(path : String, read : suspend () -> SessionData) = synchronized(sessions) {
    log("getSession, ${path} : ${sessions[path]}")
    sessions.getOrPut(path) { GlobalScope.async { read() } }
  }.await()
  suspend fun overrideSession(path : String, data : SessionData) = synchronized(sessions) {
    log("overrideSession, ${path}")
    sessions.put(path, CompletableDeferred(data))
  }
}
