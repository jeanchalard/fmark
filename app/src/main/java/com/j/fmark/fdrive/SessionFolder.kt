package com.j.fmark.fdrive

import android.graphics.Bitmap
import com.google.api.client.http.InputStreamContent
import com.google.api.services.drive.Drive
import com.j.fmark.COMMENT_FILE_NAME
import com.j.fmark.DATA_FILE_NAME
import com.j.fmark.LocalSecond
import com.j.fmark.SessionData
import com.j.fmark.fdrive.FDrive.decodeSessionFolderName
import com.j.fmark.save
import com.j.fmark.unit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import com.google.api.services.drive.model.File as DriveFile

interface SessionFolder {
  val date : LocalSecond
  val lastUpdateDate : LocalSecond
  suspend fun openData() : SessionData
  suspend fun saveData(data : SessionData)
  suspend fun saveComment(comment : String)
  suspend fun saveImage(image : Bitmap, fileName : String)
}

interface SessionFolderList : Iterable<SessionFolder> {
  val count : Int
  fun get(i : Int) : SessionFolder
}

class LocalDiskSessionFolder(private val file : File) : SessionFolder {
  companion object {
    private val NULL_INPUT_STREAM = object : InputStream() {
      override fun skip(n : Long) : Long = 0L
      override fun available() : Int = 0
      override fun reset() = Unit
      override fun close() = Unit
      override fun mark(readlimit : Int) = Unit
      override fun markSupported() : Boolean = false
      override fun read() : Int = -1
      override fun read(b : ByteArray?) = -1
      override fun read(b : ByteArray?, off : Int, len : Int) = -1
    }
  }

  init {
    if (!file.exists()) file.mkdir()
  }

  override val date : LocalSecond = decodeSessionFolderName(file.name)
  override val lastUpdateDate : LocalSecond = LocalSecond(file.lastModified())

  override suspend fun openData() : SessionData = SessionData(file.resolve(DATA_FILE_NAME).let { f -> if (f.exists()) f.inputStream() else NULL_INPUT_STREAM })
  override suspend fun saveData(data : SessionData) = data.save(file.resolve(DATA_FILE_NAME).outputStream())
  override suspend fun saveComment(comment : String) = file.resolve(COMMENT_FILE_NAME).writeBytes(comment.toByteArray()).unit
  override suspend fun saveImage(image : Bitmap, fileName : String) = image.compress(Bitmap.CompressFormat.PNG, 85, file.resolve(fileName).outputStream()).unit
}

class LocalDiskSessionFolderList(private val sessions : List<File>) : SessionFolderList {
  override val count = sessions.size
  override fun get(i : Int) : LocalDiskSessionFolder = LocalDiskSessionFolder(sessions[i])
  override fun iterator() : Iterator<LocalDiskSessionFolder> = SessionIterator(sessions.iterator())

  inner class SessionIterator(private val fileIterator : Iterator<File>) : Iterator<LocalDiskSessionFolder> {
    override fun hasNext() : Boolean = fileIterator.hasNext()
    override fun next() : LocalDiskSessionFolder = LocalDiskSessionFolder(fileIterator.next())
  }
}

const val BINDATA_MIME_TYPE = "application/octet-stream" // This is stupid >.> why do I have to specify this

class RESTSessionFolder(private val drive : Drive, private val sessionFolder : DriveFile) : SessionFolder {
  override val date = LocalSecond(sessionFolder.createdTime)
  override val lastUpdateDate = LocalSecond(sessionFolder.modifiedTime)

  override suspend fun openData() : SessionData = withContext(Dispatchers.IO) {
    val f = FDrive.fetchDriveFile(drive, DATA_FILE_NAME, sessionFolder)
    SessionData(drive.files().get(f.id).executeMediaAsInputStream())
  }

  private suspend fun saveToDriveFile(fileName : String, inputStream : InputStream) = withContext(Dispatchers.IO) {
    FDrive.fetchDriveFile(drive, fileName, sessionFolder).let { file ->
      drive.files().update(file.id, null /* no metadata updates */, InputStreamContent(BINDATA_MIME_TYPE, inputStream)).execute()
    }
  }

  override suspend fun saveData(data : SessionData) {
    val s = ByteArrayOutputStream()
    data.save(s)
    saveToDriveFile(DATA_FILE_NAME, s.toByteArray().inputStream())
  }

  override suspend fun saveComment(comment : String) = saveToDriveFile(COMMENT_FILE_NAME, comment.toByteArray().inputStream()).unit

  override suspend fun saveImage(image : Bitmap, fileName : String) {
    val s = ByteArrayOutputStream()
    image.compress(Bitmap.CompressFormat.PNG, 85, s)
    saveToDriveFile(fileName, s.toByteArray().inputStream())
  }
}

class RESTSessionFolderList(private val drive : Drive, private val sessions : CopyOnWriteArrayList<RESTSessionFolder>) : SessionFolderList {
  override val count = sessions.size
  override fun get(i : Int) : RESTSessionFolder = sessions[i]
  override fun iterator() = sessions.iterator()
}
