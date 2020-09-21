package com.j.fmark.fdrive

import com.j.fmark.CREATION_DATE_FILE_NAME
import com.j.fmark.LOGEVERYTHING
import com.j.fmark.LocalSecond
import com.j.fmark.PROBABLY_FRESH_DELAY_MS
import com.j.fmark.fdrive.FDrive.Root
import com.j.fmark.fdrive.FDrive.decodeComment
import com.j.fmark.fdrive.FDrive.decodeName
import com.j.fmark.fdrive.FDrive.decodeReading
import com.j.fmark.fdrive.FDrive.encodeClientFolderName
import com.j.fmark.fdrive.FDrive.encodeSessionFolderName
import com.j.fmark.fromCacheOrNetwork
import com.j.fmark.logAlways
import com.j.fmark.mkdir_p
import com.j.fmark.now
import com.j.fmark.toLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
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
  suspend fun getSessions() : SessionFolderList
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
     cacheDir.resolve(CREATION_DATE_FILE_NAME).readBytes().toLong()
  override val modifiedDate : Long
   get() = if (clientFolder.isCompleted)
     clientFolder.getCompleted().modifiedTime?.value ?: throw IllegalArgumentException("Modified date not present in loaded folder")
   else
     cacheDir.lastModified()
  override val id : String
   get() = if (clientFolder.isCompleted) clientFolder.getCompleted().id else cacheDir.absolutePath

  override suspend fun getSessions() : RESTSessionFolderList = withContext(Dispatchers.IO) {
    RESTSessionFolderList(root, path, clientFolder, cacheDir)
  }

  override suspend fun newSession() : RESTSessionFolder = withContext(Dispatchers.IO) {
    val sessionName = encodeSessionFolderName(LocalSecond(now()))
    log("New session${path}/${sessionName}")
    val sessionCacheDir = cacheDir.resolve(sessionName).mkdir_p()
    val newSession = root.saveQueue.createFolder(clientFolder.await(), sessionName).await().driveFile!!
    RESTSessionFolder(root, "${path}/${sessionName}", CompletableDeferred(newSession), sessionCacheDir)
  }

  override suspend fun rename(name : String, reading : String, comment : String) : RESTClientFolder = withContext(Dispatchers.IO) {
    val newDirName = encodeClientFolderName(name, reading, comment)
    log("Rename session ${path}/${newDirName}")
    val newDir = cacheDir.resolveSibling(newDirName)
    cacheDir.renameTo(newDir)
    val newPath = path.replaceAfterLast('/', newDirName)
    val newFolder = root.saveQueue.renameFile(clientFolder.await(), newDirName).await().driveFile!!
    RESTClientFolder(root, newPath, name, reading, comment, CompletableDeferred(newFolder), newDir)
  }
}

private suspend fun readClientsFromDrive(root : Root, name : String? = null, exactMatch : Boolean = false) : List<RESTClientFolder> {
  return FDrive.getFolderList(root.drive, root.root, name, exactMatch).map {
    val cacheDir = root.cache.resolve(it.name).mkdir_p()
    RESTClientFolder(root, it.name, decodeName(it.name), decodeReading(it.name), decodeComment(it.name), CompletableDeferred(it), cacheDir)
  }
}

private suspend fun readClientsFromCache(root : Root, cacheDir : File, name : String? = null, exactMatch : Boolean = false) : List<RESTClientFolder> {
  val scope = CoroutineScope(Dispatchers.IO)
  val fileList = cacheDir.listFiles()?.toList() ?: emptyList()
  val filteredList = when {
    null == name -> fileList
    exactMatch   -> fileList.filter { it.name == name }
    else         -> fileList.filter { it.name.contains(name) }
  }
  return filteredList.map {
    val driveFile = scope.async(start = CoroutineStart.LAZY) { FDrive.createDriveFolder(root.drive, root.root, it.name) }
    RESTClientFolder(root, it.name, decodeName(it.name), decodeReading(it.name), decodeComment(it.name), driveFile, it)
  }
}

suspend fun RESTClientFolderList(root : Root, name : String? = null, exactMatch : Boolean = false) : RESTClientFolderList {
  log("RESTClientFolderList : getting client list for ${name} (exact match = ${exactMatch})")
  val cachedClients = readClientsFromCache(root, root.cache)
  suspend fun fromDrive() = RESTClientFolderList(root, readClientsFromDrive(root))
  suspend fun fromCache() = RESTClientFolderList(root, cachedClients)
  suspend fun isCacheFresh() = if (cachedClients.isEmpty()) null else root.cache.lastModified() > now() - PROBABLY_FRESH_DELAY_MS
  return fromCacheOrNetwork(root.context, ::fromDrive, ::fromCache, ::isCacheFresh)
}
class RESTClientFolderList internal constructor(private val root : Root, private val folders : List<RESTClientFolder>) : ClientFolderList {
  override val count = folders.size
  override fun get(i : Int) = folders[i]
  override fun indexOfFirst(folder : ClientFolder) = folders.indexOfFirst { it.id == folder.id }
  suspend fun createClient(name : String, reading : String, comment : String) : ClientFolder {
    val folderName = encodeClientFolderName(name, reading, comment)
    log("Create client ${root.path}/${folderName}")
    val cacheDir = root.cache.resolve(folderName).mkdir_p()
    val createdFolder = root.saveQueue.createFolder(root.root, folderName).await().driveFile!!
    return RESTClientFolder(root, "${folderName}", name, reading, comment, CompletableDeferred(createdFolder), cacheDir)
  }
}
