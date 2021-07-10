package com.j.fmark.fdrive

import com.j.fmark.CREATION_DATE_FILE_NAME
import com.j.fmark.LOGEVERYTHING
import com.j.fmark.LocalSecond
import com.j.fmark.PROBABLY_FRESH_DELAY_MS
import com.j.fmark.fdrive.FDrive.Root
import com.j.fmark.fdrive.FDrive.decodeCacheName
import com.j.fmark.fdrive.FDrive.decodeComment
import com.j.fmark.fdrive.FDrive.decodeName
import com.j.fmark.fdrive.FDrive.decodeReading
import com.j.fmark.fdrive.FDrive.encodeClientFolderName
import com.j.fmark.fdrive.FDrive.encodeSessionFolderName
import com.j.fmark.fdrive.FDrive.resolveCache
import com.j.fmark.fdrive.FDrive.resolveSiblingCache
import com.j.fmark.load
import com.j.fmark.logAlways
import com.j.fmark.mkdir_p
import com.j.fmark.now
import com.j.fmark.toBytes
import com.j.fmark.toLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.google.api.services.drive.model.File as DriveFile

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("ClientFolder", s, e) }

interface ClientFolder {
  val id : String
  val name : String
  val path : String
  val reading : String
  val comment : String
  val createdDate : Long
  val modifiedDate : Long
  suspend fun rename(name : String, reading : String, comment : String) : ClientFolder
  suspend fun newSession() : SessionFolder
  suspend fun getSessions() : Flow<SessionFolderList>
}

interface ClientFolderList {
  val count : Int
  operator fun get(i : Int) : ClientFolder
  fun indexOfFirst(folder : ClientFolder) : Int
}

class RESTClientFolder(private val root : Root, override val path : String,
                       override val name : String, override val reading : String, override val comment : String,
                       private val clientFolder : Deferred<DriveFile>, private val cacheDir : File) : ClientFolder {
  override val createdDate : Long
   get() = if (clientFolder.isCompleted)
     clientFolder.getCompleted().createdTime?.value ?: throw IllegalArgumentException("Created date not present in loaded folder")
   else
     cacheDir.resolveCache(CREATION_DATE_FILE_NAME).readBytes().toLong()
  override val modifiedDate : Long
   get() = if (clientFolder.isCompleted)
     clientFolder.getCompleted().modifiedTime?.value ?: throw IllegalArgumentException("Modified date not present in loaded folder")
   else
     cacheDir.lastModified()
  override val id : String
   get() = if (clientFolder.isCompleted) clientFolder.getCompleted().id else cacheDir.absolutePath

  override suspend fun getSessions() = RESTSessionFolderList(root, path, clientFolder, cacheDir)

  override suspend fun newSession() : RESTSessionFolder = withContext(Dispatchers.IO) {
    val sessionName = encodeSessionFolderName(LocalSecond(now()))
    log("New session${path}/${sessionName}")
    val sessionCacheDir = cacheDir.resolveCache(sessionName).mkdir_p()
    val newSession = GlobalScope.async { root.saveQueue.createFolder(clientFolder.await(), sessionName).await().driveFile!! }
    RESTSessionFolder(root, "${path}/${sessionName}", newSession, sessionCacheDir)
  }

  override suspend fun rename(name : String, reading : String, comment : String) : RESTClientFolder = withContext(Dispatchers.IO) {
    val newDirName = encodeClientFolderName(name, reading, comment)
    log("Rename session ${path}/${newDirName}")
    val newDir = cacheDir.resolveSiblingCache(newDirName)
    cacheDir.renameTo(newDir)
    val newPath = path.replaceAfterLast('/', newDirName)
    val newFolder = GlobalScope.async { root.saveQueue.renameFile(clientFolder.await(), newDirName).await().driveFile!! }
    RESTClientFolder(root, newPath, name, reading, comment, newFolder, newDir)
  }
}

private suspend fun readClientsFromDrive(root : Root, name : String? = null, exactMatch : Boolean = false) : List<RESTClientFolder> {
  val rootFile = root.root.await()
  return FDrive.getFolderList(root.drive, rootFile, name, exactMatch).map {
    val cacheDir = root.cache.resolveCache(it.name).mkdir_p()
    val date = it.createdTime.value
    cacheDir.resolve(CREATION_DATE_FILE_NAME).writeBytes(date.toBytes())
    RESTClientFolder(root, it.name, decodeName(it.name), decodeReading(it.name), decodeComment(it.name), CompletableDeferred(it), cacheDir)
  }
}

private suspend fun readClientsFromCache(root : Root, cacheDir : File, name : String? = null, exactMatch : Boolean = false) : List<RESTClientFolder> {
  val scope = CoroutineScope(Dispatchers.IO)
  val fileList = cacheDir.listFiles()?.toList() ?: emptyList()
  val filteredList = when {
    null == name -> fileList
    exactMatch   -> fileList.filter { decodeCacheName(it.name) == name }
    else         -> fileList.filter { decodeCacheName(it.name).contains(name) }
  }
  return filteredList.map {
    val name = decodeCacheName(it.name)
    val driveFile = scope.async(start = CoroutineStart.LAZY) { FDrive.createDriveFolder(root.drive, root.root.await(), name) }
    RESTClientFolder(root, name, decodeName(name), decodeReading(name), decodeComment(name), driveFile, it)
  }
}

class RESTClientFolderListHolder(root : Root, fromDrive : (suspend () -> RESTClientFolderList)?, fromCache : suspend () -> RESTClientFolderList, isCacheFresh : suspend () -> Boolean?) {
  private var continuation : Continuation<Unit>? = null
    get() = synchronized(this) { field }
    set(value) = synchronized(this) { field = value }

  val flow = flow {
    do {
      load(root.context, fromDrive, fromCache, isCacheFresh).collect {
        log("Loaded ${it}")
        emit(it)
      }
      suspendCoroutine<Unit> { cont -> continuation = cont }
    } while (false)
  }
  fun refresh() = continuation?.resume(Unit)
}

suspend fun RESTClientFolderList(root : Root, name : String? = null, exactMatch : Boolean = false) : RESTClientFolderListHolder {
  log("RESTClientFolderList : getting client list for ${name} (exact match = ${exactMatch}) root = ${root}")
  val cachedClients = readClientsFromCache(root, root.cache, name, exactMatch)
  suspend fun fromDrive() = RESTClientFolderList(root, readClientsFromDrive(root, name, exactMatch))
  suspend fun fromCache() = RESTClientFolderList(root, cachedClients)
  suspend fun isCacheFresh() = if (cachedClients.isEmpty()) null else root.cache.lastModified() > now() - PROBABLY_FRESH_DELAY_MS
  return RESTClientFolderListHolder(root, if (root.drive is NullDrive) null else ::fromDrive, ::fromCache, ::isCacheFresh)
}
class RESTClientFolderList internal constructor(private val root : Root, private val folders : List<RESTClientFolder>) : ClientFolderList {
  override val count = folders.size
  override fun get(i : Int) = folders[i]
  override fun indexOfFirst(folder : ClientFolder) = folders.indexOfFirst { it.id == folder.id }
  suspend fun createClient(name : String, reading : String, comment : String) : ClientFolder {
    val folderName = encodeClientFolderName(name, reading, comment)
    log("Create client ${root.path}/${folderName}")
    val cacheDir = root.cache.resolveCache(folderName).mkdir_p()
    cacheDir.resolveCache(CREATION_DATE_FILE_NAME).writeBytes(System.currentTimeMillis().toBytes())
    val createdFolder = GlobalScope.async { root.saveQueue.createFolder(root.root.await(), folderName).await().driveFile!! }
    return RESTClientFolder(root, folderName, name, reading, comment, createdFolder, cacheDir)
  }
}
