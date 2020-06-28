package com.j.fmark.fdrive

import com.google.api.services.drive.Drive
import com.j.fmark.CREATION_DATE_FILE_NAME
import com.j.fmark.LocalSecond
import com.j.fmark.fdrive.FDrive.decodeName
import com.j.fmark.fdrive.FDrive.decodeReading
import com.j.fmark.fdrive.FDrive.encodeClientFolderName
import com.j.fmark.fdrive.FDrive.encodeSessionFolderName
import com.j.fmark.toBytes
import com.j.fmark.toLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.IllegalArgumentException
import java.util.concurrent.CopyOnWriteArrayList
import com.google.api.services.drive.model.File as DriveFile

interface ClientFolder {
  val driveId : String
  val name : String
  val reading : String
  val createdDate : Long
  val modifiedDate : Long
  suspend fun rename(name : String, reading : String) : ClientFolder
  suspend fun newSession() : SessionFolder
  suspend fun getSessions() : SessionFolderList
}

interface ClientFolderList {
  val count : Int
  operator fun get(i : Int) : ClientFolder
  fun indexOfFirst(folder : ClientFolder) : Int
}

/*
abstract class BaseCachedClientFolder(protected val underlying : Deferred<RESTClientFolder>) : ClientFolder
class CachedClientFolder(underlying : Deferred<RESTClientFolder>,
                         override val driveId : String, override val name : String, override val reading : String,
                         override val createdDate : Long, override val modifiedDate : Long) : BaseCachedClientFolder(underlying) {
  private val folderList : Deferred<RESTSessionFolderList> by lazy { GlobalScope.async(Dispatchers.IO, CoroutineStart.LAZY) {
    underlying.getCompleted().getSessions()
  } }
  override suspend fun getSessions() = folderList.getCompleted()
  override suspend fun rename(name : String, reading : String) : CachedClientFolder =
   CachedClientFolder(GlobalScope.async(Dispatchers.IO) { underlying.getCompleted().rename(name, reading) },
    driveId, name, reading, createdDate, System.currentTimeMillis())
  override suspend fun newSession() : SessionFolder {
  }
}
*/

class LocalDiskClientFolder(private val file : File) : ClientFolder {
  init {
    if (!file.exists()) {
      file.mkdir()
      file.resolve(CREATION_DATE_FILE_NAME).writeBytes(System.currentTimeMillis().toBytes())
    }
  }

  override val driveId : String = file.absolutePath
  override val name = decodeName(file.name.toString())
  override val reading = decodeReading(file.name.toString())
  override val createdDate : Long = file.resolve(CREATION_DATE_FILE_NAME).readBytes().toLong()
  override val modifiedDate : Long = file.lastModified()

  override suspend fun newSession() : SessionFolder =
   LocalDiskSessionFolder(file.resolve(encodeSessionFolderName(LocalSecond(System.currentTimeMillis()))))

  override suspend fun getSessions() : LocalDiskSessionFolderList = LocalDiskSessionFolderList(file.listFiles().filter { it.isDirectory })

  override suspend fun rename(name : String, reading : String) : LocalDiskClientFolder = File(encodeClientFolderName(name, reading)).let { newFile ->
    file.renameTo(newFile)
    LocalDiskClientFolder(newFile)
  }
}

class LocalDiskClientFolderList(private val folders : List<File>) : ClientFolderList {
  override val count : Int get() = folders.size
  override fun get(i : Int) : LocalDiskClientFolder = LocalDiskClientFolder(folders[i])
  override fun indexOfFirst(folder : ClientFolder) = folders.indexOfFirst { it.absolutePath == folder.driveId }
}

class RESTClientFolder(private val drive : Drive, private val clientFolder : DriveFile) : ClientFolder {
  override val name = FDrive.decodeName(clientFolder.name)
  override val reading = FDrive.decodeReading(clientFolder.name)
  override val createdDate = clientFolder.createdTime?.value ?: throw IllegalArgumentException("Created date not present in loaded folder")
  override val modifiedDate = clientFolder.modifiedTime?.value ?: throw IllegalArgumentException("Modified date not present in loaded folder")
  override val driveId : String = clientFolder.id

  override suspend fun getSessions() : RESTSessionFolderList = withContext(Dispatchers.IO) {
    RESTSessionFolderList(drive, CopyOnWriteArrayList(FDrive.fetchFolderList(drive, clientFolder).map { RESTSessionFolder(drive, it) }))
  }

  override suspend fun newSession() : RESTSessionFolder = withContext(Dispatchers.IO) {
    RESTSessionFolder(drive, FDrive.createDriveFolder(drive, clientFolder, encodeSessionFolderName(LocalSecond(System.currentTimeMillis()))))
  }

  override suspend fun rename(name : String, reading : String) : RESTClientFolder = withContext(Dispatchers.IO) {
    RESTClientFolder(drive, drive.files().update(clientFolder.id, DriveFile().setName(encodeClientFolderName(name, reading))).setFields(NECESSARY_FIELDS).execute())
  }
}

suspend fun RESTClientFolderList(drive : Drive, root : DriveFile, name : String? = null, exactMatch : Boolean = false) =
  RESTClientFolderList(drive, root, CopyOnWriteArrayList(FDrive.fetchFolderList(drive, root, name, exactMatch).map { RESTClientFolder(drive, it) }))
class RESTClientFolderList internal constructor(private val drive : Drive, private val root : DriveFile, private val folders : CopyOnWriteArrayList<RESTClientFolder>) : ClientFolderList {
  override val count = folders.size
  override fun get(i : Int) = folders[i]
  override fun indexOfFirst(folder : ClientFolder) = folders.indexOfFirst { it.driveId == folder.driveId }
  suspend fun createClient(name : String, reading : String) : RESTClientFolder {
    val f = FDrive.createDriveFolder(drive, root, encodeClientFolderName(name, reading))
    return RESTClientFolder(drive, f).also { folders.add(it) }
  }
}
