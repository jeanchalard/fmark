package com.j.fmark.fdrive

import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.MetadataBuffer
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.Query
import com.google.android.gms.drive.query.SearchableField
import com.google.android.gms.drive.query.SortOrder
import com.google.android.gms.drive.query.SortableField
import com.j.fmark.LocalSecond
import com.j.fmark.fdrive.FDrive.decodeName
import com.j.fmark.fdrive.FDrive.decodeReading
import kotlinx.coroutines.tasks.await

interface ClientFolder
{
  val driveId : String
  val name : String
  val reading : String
  val createdDate : Long
  val modifiedDate : Long
  suspend fun rename(name : String, reading : String) : ClientFolder
  suspend fun newSession() : SessionFolder
  suspend fun getSessions() : SessionFolderList
}

interface ClientFolderList
{
  val count: Int
  operator fun get(i : Int): ClientFolder
  fun findIndex(folder : ClientFolder) : Int
}

/*
class RESTClientFolder(private val drive : Drive) : ClientFolder
{

}
*/

class LegacyClientFolder(private val metadata : Metadata, private val resourceClient : DriveResourceClient) : ClientFolder {
  override val driveId : String get() = metadata.driveId.encodeToString()
  override val name get() = decodeName(metadata.title)
  override val reading get() = decodeReading(metadata.title)
  override val createdDate : Long get() = metadata.createdDate.time
  override val modifiedDate : Long get() = metadata.modifiedDate.time
  override suspend fun rename(name : String, reading : String) =
   LegacyClientFolder(resourceClient.updateMetadata(metadata.driveId.asDriveFolder(), FDrive.metadataForClient(name, reading)).await(), resourceClient)
  override suspend fun newSession() : SessionFolder
  {
    val folder = resourceClient.createFolder(metadata.driveId.asDriveFolder(), FDrive.metadataForSession(LocalSecond(System.currentTimeMillis()))).await()
    return LegacySessionFolder(resourceClient.getMetadata(folder).await(), resourceClient)
  }
  override suspend fun getSessions() : SessionFolderList
  {
    val clientFolder = metadata.driveId.asDriveFolder()
    val query = Query.Builder().apply {
      addFilter(Filters.eq(SearchableField.TRASHED, false))
      setSortOrder(SortOrder.Builder().addSortDescending(SortableField.TITLE).build())
    }.build()
    val result = resourceClient.queryChildren(clientFolder, query).await()
    return LegacySessionFolderList(result, resourceClient)
  }
}

class LegacyClientFolderList(private val buffer : MetadataBuffer, private val resourceClient : DriveResourceClient) : ClientFolderList
{
  override val count = buffer.count
  override fun get(i : Int) : ClientFolder = LegacyClientFolder(buffer[i], resourceClient)
  override fun findIndex(folder : ClientFolder) : Int
  {
    buffer.forEachIndexed { index, it -> if (folder.driveId == it.driveId.encodeToString()) return index }
    return -1
  }
}
