package com.j.fmark.drive

import com.google.android.gms.drive.DriveFolder
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.MetadataBuffer
import com.google.android.gms.drive.MetadataChangeSet
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.Query
import com.google.android.gms.drive.query.SearchableField
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
   .setTitle(encodeFolderName(name, reading))
   .setDescription(reading)
   .setIndexableText(encodeIndexableText(name, reading))
   .setMimeType(DriveFolder.MIME_TYPE)
   .build()

suspend fun createFolderForClientName(driveResourceClient : DriveResourceClient, fmarkFolder : DriveFolder, name : String, reading : String) : Metadata
{
  val folder = driveResourceClient.createFolder(fmarkFolder, metadataForClient(name, reading)).await()
  return driveResourceClient.getMetadata(folder).await()
}

suspend fun renameFolder(driveResourceClient : DriveResourceClient, clientFolder : DriveFolder, name : String, reading : String) : Metadata?
{
  return driveResourceClient.updateMetadata(clientFolder, metadataForClient(name, reading)).await()
}

fun encodeFolderName(name : String, reading : String) = "${name} -- ${reading}"
fun encodeIndexableText(name : String, reading : String) = "${name} ${reading}"
fun decodeName(folder : Metadata) = folder.title.let { if (it.indexOf("--") != -1) it.split(" -- ")[0] else it }
fun decodeReading(folder : Metadata) = folder.title.let { if (it.indexOf("--") != -1) it.split(" -- ")[1] else it }
