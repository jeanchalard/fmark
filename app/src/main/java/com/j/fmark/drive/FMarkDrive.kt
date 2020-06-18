package com.j.fmark.drive

import com.google.android.gms.drive.DriveFile
import com.google.android.gms.drive.DriveFolder
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.MetadataBuffer
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.Query
import com.google.android.gms.drive.query.SearchableField
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.j.fmark.LocalSecond
import com.j.fmark.fdrive.FDrive.encodeClientFolderName
import com.j.fmark.parseLocalSecond
import kotlinx.coroutines.tasks.await
import java.io.IOException

suspend fun renameFolder(drive : Drive, clientFolder : File, newName : String, reading : String) : File? = try {
  drive.files().update(clientFolder.id, clientFolder.apply { this.name = encodeClientFolderName(newName, reading) }).execute()
} catch (e : IOException) { null }

suspend fun DriveResourceClient.findFile(clientFolder : DriveFolder, fileName : String) : DriveFile? {
  val query = Query.Builder()
   .addFilter(Filters.eq(SearchableField.TITLE, fileName))
   .addFilter(Filters.eq(SearchableField.TRASHED, false))
   .build()
  val result = queryChildren(clientFolder, query).await()
  return if (result.count > 0) result[0].driveId.asDriveFile() else null
}
