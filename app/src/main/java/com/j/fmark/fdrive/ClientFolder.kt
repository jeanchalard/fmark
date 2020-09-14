package com.j.fmark.fdrive

import com.j.fmark.LOGEVERYTHING
import com.j.fmark.CREATION_DATE_FILE_NAME
import com.j.fmark.ErrorHandling
import com.j.fmark.LocalSecond
import com.j.fmark.fdrive.FDrive.Root
import com.j.fmark.fdrive.FDrive.decodeComment
import com.j.fmark.fdrive.FDrive.decodeName
import com.j.fmark.fdrive.FDrive.decodeReading
import com.j.fmark.fdrive.FDrive.encodeClientFolderName
import com.j.fmark.fdrive.FDrive.encodeSessionFolderName
import com.j.fmark.logAlways
import com.j.fmark.mkdir_p
import com.j.fmark.toBytes
import com.j.fmark.toLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.Exception
import java.util.concurrent.CopyOnWriteArrayList
import com.google.api.services.drive.model.File as DriveFile

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("ClientFolder", s, e) }

interface ClientFolder {
  val id : String
  val name : String
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

class LocalDiskClientFolder(private val file : File) : ClientFolder {
  init {
    if (!file.exists()) {
      file.mkdir()
      file.resolve(CREATION_DATE_FILE_NAME).writeBytes(System.currentTimeMillis().toBytes())
    }
  }

  override val id : String = file.absolutePath
  override val name = decodeName(file.name.toString())
  override val reading = decodeReading(file.name.toString())
  override val comment = decodeComment(file.name.toString())
  override val createdDate : Long = file.resolve(CREATION_DATE_FILE_NAME).readBytes().toLong()
  override val modifiedDate : Long = file.lastModified()

  override suspend fun newSession() : SessionFolder =
   LocalDiskSessionFolder(file.resolve(encodeSessionFolderName(LocalSecond(System.currentTimeMillis()))))

  override suspend fun getSessions() : LocalDiskSessionFolderList = LocalDiskSessionFolderList(file.listFiles().filter { it.isDirectory })

  override suspend fun rename(name : String, reading : String, comment : String) : LocalDiskClientFolder = File(encodeClientFolderName(name, reading, comment)).let { newFile ->
    file.renameTo(newFile)
    LocalDiskClientFolder(newFile)
  }
}

class LocalDiskClientFolderList(private val folders : List<File>) : ClientFolderList {
  override val count : Int get() = folders.size
  override fun get(i : Int) : LocalDiskClientFolder = LocalDiskClientFolder(folders[i])
  override fun indexOfFirst(folder : ClientFolder) = folders.indexOfFirst { it.absolutePath == folder.id }
}

class RESTClientFolder(private val root : Root,
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
    RESTSessionFolderList(root, clientFolder, cacheDir)
  }

  override suspend fun newSession() : RESTSessionFolder = withContext(Dispatchers.IO) {
    val sessionName = encodeSessionFolderName(LocalSecond(System.currentTimeMillis()))
    log("New session ${sessionName}")
    val sessionCacheDir = cacheDir.resolve(sessionName).mkdir_p()
    val newSession = root.saveQueue.createFolder(clientFolder.await(), sessionName).await().driveFile!!
    RESTSessionFolder(root, CompletableDeferred(newSession), sessionCacheDir)
  }

  override suspend fun rename(name : String, reading : String, comment : String) : RESTClientFolder = withContext(Dispatchers.IO) {
    val newDirName = encodeClientFolderName(name, reading, comment)
    log("Rename session ${newDirName}")
    val newDir = cacheDir.resolveSibling(newDirName)
    cacheDir.renameTo(newDir)
    val newFolder = root.saveQueue.renameFile(clientFolder.await(), newDirName).await().driveFile!!
    RESTClientFolder(root, name, reading, comment, CompletableDeferred(newFolder), newDir)
  }
}

suspend fun RESTClientFolderList(root : Root, name : String? = null, exactMatch : Boolean = false) =
  RESTClientFolderList(root, CopyOnWriteArrayList(FDrive.getFolderList(root.drive, root.root, name, exactMatch).map {
    RESTClientFolder(root, decodeName(it.name), decodeReading(it.name), decodeComment(it.name), CompletableDeferred(it), root.cache.resolve(it.name))
  }))
class RESTClientFolderList internal constructor(private val root : Root, private val folders : CopyOnWriteArrayList<RESTClientFolder>) : ClientFolderList {
  override val count = folders.size
  override fun get(i : Int) = folders[i]
  override fun indexOfFirst(folder : ClientFolder) = folders.indexOfFirst { it.id == folder.id }
  suspend fun createClient(name : String, reading : String, comment : String) : ClientFolder {
    val folderName = encodeClientFolderName(name, reading, comment)
    log("Create client ${folderName}")
    val cacheDir = root.cache.resolve(folderName).mkdir_p()
    val createdFolder = root.saveQueue.createFolder(root.root, folderName).await().driveFile!!
    return RESTClientFolder(root, name, reading, comment, CompletableDeferred(createdFolder), cacheDir)
  }
}
