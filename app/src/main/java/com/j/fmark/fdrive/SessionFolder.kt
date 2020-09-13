package com.j.fmark.fdrive

import android.graphics.Bitmap
import android.util.Log
import com.google.api.client.http.InputStreamContent
import com.j.fmark.BACK_IMAGE_NAME
import com.j.fmark.COMMENT_FILE_NAME
import com.j.fmark.DATA_FILE_NAME
import com.j.fmark.FACE_IMAGE_NAME
import com.j.fmark.FRONT_IMAGE_NAME
import com.j.fmark.LOGEVERYTHING
import com.j.fmark.LiveCache
import com.j.fmark.LocalSecond
import com.j.fmark.SessionData
import com.j.fmark.fdrive.FDrive.Root
import com.j.fmark.fdrive.FDrive.decodeSessionFolderName
import com.j.fmark.fdrive.FDrive.fetchDriveFile
import com.j.fmark.logAlways
import com.j.fmark.save
import com.j.fmark.unit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import com.google.api.services.drive.model.File as DriveFile

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("SessionFolder", s, e) }

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
const val PNG_MIME_TYPE = "image/png"
const val TEXT_MIME_TYPE = "text/plain"

class RESTSessionFolder(private val root : Root, private val sessionFolder : Deferred<DriveFile>, private val cacheDir : File) : SessionFolder {
  override val date
    get() = if (sessionFolder.isCompleted)
     LocalSecond(sessionFolder.getCompleted().createdTime)
   else
     decodeSessionFolderName(cacheDir.name)
  override val lastUpdateDate
    get() = if (sessionFolder.isCompleted)
      LocalSecond(sessionFolder.getCompleted().modifiedTime)
    else
      LocalSecond(cacheDir.lastModified())

  override suspend fun openData() : SessionData = withContext(Dispatchers.IO) {
    log("openData : start, creating Drive file...")
    val f = FDrive.createDriveFile(root.drive, sessionFolder.await(), DATA_FILE_NAME)
    log("openData : created Drive file ${f.id} ${f.name}, reading data...")
    val data = LiveCache.getSession(f) { SessionData(root.drive.files().get(f.id).executeMediaAsInputStream()) }
    log("openData : read data, comment = ${data.comment}, face has ${data.face.data.size} data points, starting priming tasks...")
    listOf(FACE_IMAGE_NAME, FRONT_IMAGE_NAME, BACK_IMAGE_NAME).forEach { async { fetchDriveFile(root.drive, it, sessionFolder.await()) } }
    log("openData : done")
    data
  }

  private suspend fun saveToDriveFile(fileName : String, inputStream : InputStream, dataType : String) = withContext(Dispatchers.IO) {
    log("saveToDriveFile : start, creating Drive file...")
    FDrive.createDriveFile(root.drive, sessionFolder.await(), fileName).let { file ->
      log("saveToDriveFile : created Drive file : ${file.id} ${file.name}, updating...")
      val f = DriveFile().apply { mimeType = dataType }
      root.drive.files().update(file.id, f, InputStreamContent(dataType, inputStream)).execute()
    }.also { log("saveToDriveFile : done") }
  }

  override suspend fun saveData(data : SessionData) {
    log("saveData : start with ${data.face.data.size} data points for face, saving cache...")
    cacheDir.resolve(DATA_FILE_NAME).outputStream().buffered().use { data.save(it) }
    log("saveData : cache saved, saving to Drive...")
    val s = ByteArrayOutputStream()
    data.save(s)
    saveToDriveFile(DATA_FILE_NAME, s.toByteArray().inputStream(), BINDATA_MIME_TYPE)
    log("saveData : done")
  }

  override suspend fun saveComment(comment : String) {
    log("saveComment : start with comment = ${comment}, saving cache...")
    cacheDir.resolve(DATA_FILE_NAME).bufferedWriter().use { it.write(comment) }
    log("saveComment : cache saved, saving to Drive...")
    saveToDriveFile(COMMENT_FILE_NAME, comment.toByteArray().inputStream(), TEXT_MIME_TYPE)
    log("saveComment : done")
  }

  override suspend fun saveImage(image : Bitmap, fileName : String) {
    log("saveImage : start with ${fileName}, saving to Drive (since images are not saved to cache)...")
    ByteArrayOutputStream().use { s ->
      image.savePng(s)
      saveToDriveFile(fileName, s.toByteArray().inputStream(), PNG_MIME_TYPE)
    }
    log("saveImage : done")
  }
}

suspend fun RESTSessionFolderList(root : Root, clientFolder : Deferred<DriveFile>, cacheDir : File) =
 RESTSessionFolderList(root, CopyOnWriteArrayList(FDrive.getFolderList(root.drive, clientFolder.await()).map {
   RESTSessionFolder(root, CompletableDeferred(it), cacheDir.resolve(it.name))
 }))
class RESTSessionFolderList internal constructor(private val root : Root, private val sessions : List<RESTSessionFolder>) : SessionFolderList {
  override val count = sessions.size
  override fun get(i : Int) : RESTSessionFolder = sessions[i]
  override fun iterator() = sessions.iterator()
}

fun Bitmap.savePng(s : OutputStream) = compress(Bitmap.CompressFormat.PNG, 85, s)
