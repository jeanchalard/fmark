package com.j.fmark.drive

import com.google.android.gms.drive.DriveFolder
import com.google.android.gms.drive.DriveResourceClient
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
   .build()
  return driveResourceClient.queryChildren(fmarkFolder, query).await()
}

suspend fun createFolderForClientName(driveResourceClient : DriveResourceClient, fmarkFolder : DriveFolder, name : String, reading : String) : DriveFolder
{
  val cs = MetadataChangeSet.Builder()
   .setTitle(name)
   .setDescription(reading)
   .setIndexableText("${name} - ${reading}")
   .setMimeType(DriveFolder.MIME_TYPE)
   .build()
  return driveResourceClient.createFolder(fmarkFolder, cs).await()
}
