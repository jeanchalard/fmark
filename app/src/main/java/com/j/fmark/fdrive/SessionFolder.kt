package com.j.fmark.fdrive

import android.graphics.Bitmap
import com.google.android.gms.drive.DriveFile
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.MetadataBuffer
import com.google.android.gms.drive.MetadataChangeSet
import com.google.api.client.http.InputStreamContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.j.fmark.COMMENT_FILE_NAME
import com.j.fmark.DATA_FILE_NAME
import com.j.fmark.ErrorHandling
import com.j.fmark.LocalSecond
import com.j.fmark.SessionData
import com.j.fmark.drive.findFile
import com.j.fmark.log
import com.j.fmark.logStackTrace
import com.j.fmark.parseLocalSecond
import com.j.fmark.save
import com.j.fmark.unit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStreamWriter

interface SessionFolder
{
  val date : LocalSecond
  val lastUpdateDate : LocalSecond
  suspend fun openData() : SessionData
  suspend fun saveData(data : SessionData)
  suspend fun saveComment(comment : String)
  suspend fun saveImage(image : Bitmap, fileName : String)
}

interface SessionFolderList : Iterable<SessionFolder>
{
  val count : Int
  fun get(i : Int) : SessionFolder
}

const val BINDATA_MIME_TYPE = "application/octet-stream" // This is stupid >.> why do I have to specify this
class RESTSessionFolder(private val drive : Drive, private val sessionFolder : File) : SessionFolder
{
  override val date = LocalSecond(sessionFolder.createdTime)
  override val lastUpdateDate = LocalSecond(sessionFolder.modifiedTime)

  override suspend fun openData() : SessionData = withContext(Dispatchers.IO) {
    log("OpenData ${sessionFolder} (${sessionFolder.name}) (${sessionFolder.id})")
    val f = FDrive.getDriveFile(drive, DATA_FILE_NAME, sessionFolder)
    log(">> ${f} (${f.name}) (${f.id})")
    SessionData(drive.files().get(f.id).executeMediaAsInputStream())
  }

  private suspend fun saveToDriveFile(fileName : String, inputStream : InputStream) = withContext(Dispatchers.IO) {
    try
    {
      FDrive.getDriveFile(drive, fileName, sessionFolder).let { file ->
        log("Up ${fileName} ${file.id}")
        drive.files().update(file.id, null /* no metadata updates */, InputStreamContent(BINDATA_MIME_TYPE, inputStream)).execute()
      }
    }
    catch (e : Exception)
    {
      log("Crash ${fileName}")
      throw e
    }
  }

  override suspend fun saveData(data : SessionData)
  {
    val s = ByteArrayOutputStream()
    data.save(s)
    saveToDriveFile(DATA_FILE_NAME, s.toByteArray().inputStream())
  }

  override suspend fun saveComment(comment : String) = saveToDriveFile(COMMENT_FILE_NAME, comment.toByteArray().inputStream()).unit

  override suspend fun saveImage(image : Bitmap, fileName : String)
  {
    val s = ByteArrayOutputStream()
    image.compress(Bitmap.CompressFormat.PNG, 85, s)
    saveToDriveFile(fileName, s.toByteArray().inputStream())
  }
}

class RESTSessionFolderList(private val drive : Drive, private val sessions : List<File>) : SessionFolderList
{
  override val count = sessions.size
  override fun get(i : Int) = RESTSessionFolder(drive, sessions[i])
  override fun iterator() = SessionIterator(sessions.iterator())

  inner class SessionIterator(private val fileIterator : Iterator<File>) : Iterator<SessionFolder>
  {
    override fun hasNext() = fileIterator.hasNext()
    override fun next() = RESTSessionFolder(drive, fileIterator.next())
  }
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
    data.save(dataContents.outputStream)
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
