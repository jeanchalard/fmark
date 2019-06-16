package com.j.fmark.fdrive

import android.graphics.Bitmap
import com.google.android.gms.drive.DriveFile
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.MetadataBuffer
import com.google.android.gms.drive.MetadataChangeSet
import com.j.fmark.COMMENT_FILE_NAME
import com.j.fmark.DATA_FILE_NAME
import com.j.fmark.ErrorHandling
import com.j.fmark.LocalSecond
import com.j.fmark.SessionData
import com.j.fmark.drive.findFile
import com.j.fmark.parseLocalSecond
import com.j.fmark.save
import kotlinx.coroutines.tasks.await
import java.io.FileInputStream
import java.io.ObjectOutputStream
import java.io.OutputStreamWriter

interface SessionFolder
{
  val date : LocalSecond
  val lastUpdateDate : LocalSecond
  suspend fun openData() : SessionData
  suspend fun saveData(data : SessionData)
  suspend fun saveComment(comment : String)
  suspend fun saveImage(image : Bitmap, filename : String)
}

interface SessionFolderList : Iterable<SessionFolder>
{
  val count : Int
  fun get(i : Int) : SessionFolder
}

class RESTSessionFolder
{

}

class LegacySessionFolder(private val metadata : Metadata, private val resourceClient : DriveResourceClient) : SessionFolder
{
  override val date = parseLocalSecond(metadata.title)
  override val lastUpdateDate = LocalSecond(metadata.modifiedDate.time)

  override suspend fun openData() : SessionData
  {
    val asFolder = metadata.driveId.asDriveFolder()
    val file = resourceClient.findFile(asFolder, DATA_FILE_NAME) ?: resourceClient.createFile(asFolder, MetadataChangeSet.Builder().setTitle(DATA_FILE_NAME).build(), null).await()
    val dataContents = resourceClient.openFile(file, DriveFile.MODE_READ_WRITE).await()
    return SessionData(FileInputStream(dataContents.parcelFileDescriptor.fileDescriptor))
  }

  override suspend fun saveData(data : SessionData)
  {
    val dataFile = resourceClient.findFile(metadata.driveId.asDriveFolder(), DATA_FILE_NAME) ?: return ErrorHandling.unableToSave()
    val dataContents = resourceClient.openFile(dataFile, DriveFile.MODE_WRITE_ONLY).await()
    data.save(ObjectOutputStream(dataContents.outputStream))
    resourceClient.commitContents(dataContents, null)
  }

  override suspend fun saveComment(comment : String)
  {
    val file = resourceClient.findFile(metadata.driveId.asDriveFolder(), COMMENT_FILE_NAME)
    val contents = (if (file != null) resourceClient.openFile(file, DriveFile.MODE_WRITE_ONLY) else resourceClient.createContents()).await()
    OutputStreamWriter(contents.outputStream).use { it.write(comment) }
    val cs = MetadataChangeSet.Builder().setTitle(COMMENT_FILE_NAME).build()
    if (file != null)
      resourceClient.commitContents(contents, cs).await()
    else
      resourceClient.createFile(metadata.driveId.asDriveFolder(), cs, contents).await()
  }

  override suspend fun saveImage(image : Bitmap, fileName : String)
  {
    // Get the file on Drive
    val file = resourceClient.findFile(metadata.driveId.asDriveFolder(), fileName)
    val contents = (if (file != null) resourceClient.openFile(file, DriveFile.MODE_WRITE_ONLY) else resourceClient.createContents()).await()

    // Compress the image and save it to Drive.
    image.compress(Bitmap.CompressFormat.PNG, 85, contents.outputStream)
    val cs = MetadataChangeSet.Builder()
     .setTitle(fileName)
     .build()
    if (file != null)
      resourceClient.commitContents(contents, cs).await()
    else
      resourceClient.createFile(metadata.driveId.asDriveFolder(), cs, contents).await()
  }
}

class LegacySessionFolderList(private val buffer : MetadataBuffer, private val resourceClient : DriveResourceClient) : SessionFolderList
{
  override val count : Int = buffer.count
  override fun get(i : Int) : SessionFolder = LegacySessionFolder(buffer[i], resourceClient)
  override fun iterator() = SessionIterator(buffer.iterator())

  inner class SessionIterator(private val metadataIterator : Iterator<Metadata>) : Iterator<SessionFolder>
  {
    override fun hasNext() = metadataIterator.hasNext()
    override fun next() = LegacySessionFolder(metadataIterator.next(), resourceClient)
  }
}
