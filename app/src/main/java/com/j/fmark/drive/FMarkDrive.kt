package com.j.fmark.drive

import com.google.android.gms.drive.DriveFile
import com.google.android.gms.drive.DriveFolder
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.MetadataBuffer
import com.google.android.gms.drive.MetadataChangeSet
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.Query
import com.google.android.gms.drive.query.SearchableField
import com.j.fmark.LocalSecond
import com.j.fmark.parseLocalSecond
import kotlinx.coroutines.experimental.tasks.await

suspend fun getFoldersForClientName(driveResourceClient : DriveResourceClient, fmarkFolder : DriveFolder, name : String, reading : String) : MetadataBuffer
{
  val query = Query.Builder()
   .addFilter(Filters.eq(SearchableField.TITLE, name))
   .addFilter(Filters.eq(SearchableField.TRASHED, false))
   .build()
  return driveResourceClient.queryChildren(fmarkFolder, query).await()
}

private fun metadataForClient(name : String, reading : String) = MetadataChangeSet.Builder()
   .setTitle(encodeClientFolderName(name, reading))
   .setDescription(reading)
   .setIndexableText(encodeIndexableText(name, reading))
   .setMimeType(DriveFolder.MIME_TYPE)
   .build()

suspend fun createFolderForClientName(driveResourceClient : DriveResourceClient, fmarkFolder : DriveFolder, name : String, reading : String) : Metadata
{
  val folder = driveResourceClient.createFolder(fmarkFolder, metadataForClient(name, reading)).await()
  return driveResourceClient.getMetadata(folder).await()
}

private fun metadataForSession(date : LocalSecond) = MetadataChangeSet.Builder()
   .setTitle(encodeSessionFolderName(date))
   .setDescription(date.toString())
   .setMimeType(DriveFolder.MIME_TYPE)
   .build()

suspend fun createSessionForClient(driveResourceClient : DriveResourceClient, clientFolder : DriveFolder, date : LocalSecond) : Metadata
{
  val folder = driveResourceClient.createFolder(clientFolder, metadataForSession(date)).await()
  return driveResourceClient.getMetadata(folder).await()
}

suspend fun renameFolder(driveResourceClient : DriveResourceClient, clientFolder : DriveFolder, name : String, reading : String) : Metadata?
{
  return driveResourceClient.updateMetadata(clientFolder, metadataForClient(name, reading)).await()
}

suspend fun DriveResourceClient.findFile(clientFolder : DriveFolder, fileName : String) : DriveFile?
{
  val query = Query.Builder()
   .addFilter(Filters.eq(SearchableField.TITLE, fileName))
   .addFilter(Filters.eq(SearchableField.TRASHED, false))
   .build()
  val result = queryChildren(clientFolder, query).await()
  return if (result.count > 0) result[0].driveId.asDriveFile() else null
}

fun encodeClientFolderName(name : String, reading : String) = "${name} -- ${reading}"
fun encodeSessionFolderName(date : LocalSecond) = date.toString()
fun encodeIndexableText(name : String, reading : String) = "${name} ${reading}"
fun decodeName(folder : Metadata) = folder.title.let { if (it.indexOf("--") != -1) it.split(" -- ")[0] else it }
fun decodeReading(folder : Metadata) = folder.title.let { if (it.indexOf("--") != -1) it.split(" -- ")[1] else it }
fun decodeSessionDate(folder : Metadata) = parseLocalSecond(folder.title)
