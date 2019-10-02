package com.j.fmark.fdrive

import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.MetadataBuffer
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.Query
import com.google.android.gms.drive.query.SearchableField
import com.google.android.gms.drive.query.SortOrder
import com.google.android.gms.drive.query.SortableField
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
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

class RESTClientFolder(override val name : String, override val reading : String, override val createdDate : Long, override val modifiedDate : Long) : ClientFolder {
  override val driveId : String = clientFolder.id

  override suspend fun getSessions() : RESTSessionFolderList = withContext(Dispatchers.IO) {
    RESTSessionFolderList(drive, FDrive.getFolders(drive, clientFolder))
  }

  override suspend fun newSession() : RESTSessionFolder = withContext(Dispatchers.IO) {
    RESTSessionFolder(drive, FDrive.createFolder(drive, clientFolder, encodeSessionFolderName(LocalSecond(System.currentTimeMillis()))))
  }

  override suspend fun rename(name : String, reading : String) : RESTClientFolder = withContext(Dispatchers.IO) {
    RESTClientFolder(drive, drive.files().update(clientFolder.id, DriveFile().setName(encodeClientFolderName(name, reading))).setFields(NECESSARY_FIELDS).execute())
  }
}

class RESTClientFolderList(private val root : File, private val drive : Drive) : ClientFolderList {
  private val folders = arrayListOf<RESTClientFolder>()
  override val count = folders.size
  override fun get(i : Int) = folders[i]
  override fun indexOfFirst(folder : ClientFolder) = folders.indexOfFirst { it.driveId == folder.driveId }
  fun createClient(name : String, reading : String, createdDate : Long, modifiedDate : Long) : RESTClientFolder =
   RESTClientFolder(name, reading, createdDate, modifiedDate).also { folders.add(it) }
}

class LegacyClientFolder(private val metadata : Metadata, private val resourceClient : DriveResourceClient) : ClientFolder {
  override val driveId : String get() = metadata.driveId.encodeToString()
  override val name get() = decodeName(metadata.title)
  override val reading get() = decodeReading(metadata.title)
  override val createdDate : Long get() = metadata.createdDate.time
  override val modifiedDate : Long get() = metadata.modifiedDate.time

  override suspend fun rename(name : String, reading : String) =
   LegacyClientFolder(resourceClient.updateMetadata(metadata.driveId.asDriveFolder(), FDrive.metadataForClient(name, reading)).await(), resourceClient)

  override suspend fun newSession() : SessionFolder {
    val folder = resourceClient.createFolder(metadata.driveId.asDriveFolder(), FDrive.metadataForSession(LocalSecond(System.currentTimeMillis()))).await()
    return LegacySessionFolder(resourceClient.getMetadata(folder).await(), resourceClient)
  }

  override suspend fun getSessions() : SessionFolderList {
    val clientFolder = metadata.driveId.asDriveFolder()
    val query = Query.Builder().apply {
      addFilter(Filters.eq(SearchableField.TRASHED, false))
      setSortOrder(SortOrder.Builder().addSortDescending(SortableField.TITLE).build())
    }.build()
    val result = resourceClient.queryChildren(clientFolder, query).await()
    return LegacySessionFolderList(result, resourceClient)
  }
}

class LegacyClientFolderList(private val buffer : MetadataBuffer, private val resourceClient : DriveResourceClient) : ClientFolderList {
  override val count = buffer.count
  override fun get(i : Int) : ClientFolder = LegacyClientFolder(buffer[i], resourceClient)
  override fun indexOfFirst(folder : ClientFolder) : Int {
    buffer.forEachIndexed { index, it -> if (folder.driveId == it.driveId.encodeToString()) return index }
    return -1
  }
}