package com.j.fmark.fdrive

import android.graphics.Bitmap
import com.j.fmark.BACK_IMAGE_NAME
import com.j.fmark.COMMENT_FILE_NAME
import com.j.fmark.DATA_FILE_NAME
import com.j.fmark.FACE_IMAGE_NAME
import com.j.fmark.FRONT_IMAGE_NAME
import com.j.fmark.LOGEVERYTHING
import com.j.fmark.LiveCache
import com.j.fmark.LocalSecond
import com.j.fmark.PROBABLY_FRESH_DELAY_MS
import com.j.fmark.SessionData
import com.j.fmark.WAIT_FOR_NETWORK
import com.j.fmark.fdrive.FDrive.Root
import com.j.fmark.fdrive.FDrive.decodeSessionFolderName
import com.j.fmark.fdrive.FDrive.fetchDriveFile
import com.j.fmark.getNetworking
import com.j.fmark.logAlways
import com.j.fmark.mkdir_p
import com.j.fmark.save
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.measureTime
import com.google.api.services.drive.model.File as DriveFile

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("SessionFolder", s, e) }

interface SessionFolder {
  val date : LocalSecond
  val lastUpdateDate : LocalSecond
  val path : String
  suspend fun openData() : SessionData
  suspend fun saveData(data : SessionData)
  suspend fun saveComment(comment : String)
  suspend fun saveImage(image : Bitmap, fileName : String)
}

interface SessionFolderList : Iterable<SessionFolder> {
  val count : Int
  fun get(i : Int) : SessionFolder
}

const val BINDATA_MIME_TYPE = "application/octet-stream" // This is stupid >.> why do I have to specify this
const val PNG_MIME_TYPE = "image/png"
const val TEXT_MIME_TYPE = "text/plain"

class RESTSessionFolder(private val root : Root, override val path : String,
                        private val sessionFolder : Deferred<DriveFile>, private val cacheDir : File) : SessionFolder {
  val networking = getNetworking(root.context)
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

  init { cacheDir.mkdir_p() }

  suspend fun openDataFromDrive() : SessionData = withContext(Dispatchers.IO) {
    log("openDataFromDrive")
    val f = FDrive.createDriveFile(root.drive, sessionFolder.await(), DATA_FILE_NAME)
    log("openDataFromDrive : created Drive file ${f.id} ${path}/${f.name}, reading data...")
    val data = LiveCache.getSession(f) { SessionData(root.drive.files().get(f.id).executeMediaAsInputStream()) }
    log("openDataFromDrive : read data, comment = ${data.comment}, face has ${data.face.data.size} data points, starting priming tasks...")
    listOf(FACE_IMAGE_NAME, FRONT_IMAGE_NAME, BACK_IMAGE_NAME).forEach { async { fetchDriveFile(root.drive, it, sessionFolder.await()) } }
    log("openDataFromDrive : done")
    if (!cacheDir.resolve(DATA_FILE_NAME).exists()) {
      log("openDataFromDrive : opened data from network, cache absent, writing to cache")
      saveToCache(data)
    }
    data
  }

  override suspend fun openData() : SessionData = withContext(Dispatchers.IO) {
    val session = this@RESTSessionFolder
    log("openData : start")
    val dataFile = cacheDir.resolve(DATA_FILE_NAME)
    if (!dataFile.exists()) {
      log("Cache absent for ${session}, waiting for network")
      val start = System.currentTimeMillis()
      // TODO : timeout
      networking.waitForNetwork()
      openDataFromDrive().also { log("Read from network in ${System.currentTimeMillis() - start}ms") }
    } else {
      if (null == networking.network) {
        // TODO : Register to listen for network presence
        log("Network absent, reading ${session} from cache")
        SessionData(dataFile.inputStream())
      } else {
        if (dataFile.lastModified() > System.currentTimeMillis() - PROBABLY_FRESH_DELAY_MS) {
          // TODO : load from network and register to listen
          log("Cache fresh for ${session}, reading from cache")
          SessionData(dataFile.inputStream())
        } else {
          log("Data old for ${session} : trying to fetch from network with ${WAIT_FOR_NETWORK}ms grace")
          val dataFromDrive = async { openDataFromDrive() }
          val start = System.currentTimeMillis()
          val obtained = withTimeoutOrNull(WAIT_FOR_NETWORK) { dataFromDrive.await() }
          if (null != obtained) {
            log("Read data for ${session} from Drive in ${System.currentTimeMillis() - start}ms")
            // TODO : Register to listen on dataFromDrive
            obtained
          } else
            log("Network timeout for ${session}, reading from cache")
            SessionData(dataFile.inputStream())
        }
      }
    }
  }

  private suspend fun saveToCache(data : SessionData) {
    cacheDir.resolve(DATA_FILE_NAME).outputStream().buffered().use { data.save(it) }
  }

  private suspend fun saveToDriveFile(fileName : String, data : ByteArray, dataType : String) = withContext(Dispatchers.IO) {
    log("saveToDriveFile : enqueuing putFile command")
    root.saveQueue.putFile(null, fileName, data, dataType)
  }

  override suspend fun saveData(data : SessionData) {
    log("saveData : start with ${data.face.data.size} data points for face, saving cache...")
    saveToCache(data)
    log("saveData : cache saved, enqueuing save to Drive...")
    val s = ByteArrayOutputStream()
    data.save(s)
    saveToDriveFile("${path}/${DATA_FILE_NAME}", s.toByteArray(), BINDATA_MIME_TYPE)
    log("saveData : done")
  }

  override suspend fun saveComment(comment : String) {
    log("saveComment : start with comment = ${comment}, saving cache...")
    cacheDir.resolve(DATA_FILE_NAME).bufferedWriter().use { it.write(comment) }
    log("saveComment : cache saved, enqueuing to Drive...")
    saveToDriveFile("${path}/${COMMENT_FILE_NAME}", comment.toByteArray(), TEXT_MIME_TYPE)
    log("saveComment : done")
  }

  override suspend fun saveImage(image : Bitmap, fileName : String) {
    log("saveImage : start with ${fileName}, enqueuing to Drive (since images are not saved to cache)...")
    ByteArrayOutputStream().use { s ->
      image.savePng(s)
      saveToDriveFile("${path}/${fileName}", s.toByteArray(), PNG_MIME_TYPE)
    }
    log("saveImage : done")
  }
}

suspend fun RESTSessionFolderList(root : Root, path : String, clientFolder : Deferred<DriveFile>, cacheDir : File) =
 RESTSessionFolderList(root, CopyOnWriteArrayList(FDrive.getFolderList(root.drive, clientFolder.await()).map {
   RESTSessionFolder(root, "${path}/${it.name}", CompletableDeferred(it), cacheDir.resolve(it.name))
 }))
class RESTSessionFolderList internal constructor(private val root : Root, private val sessions : List<RESTSessionFolder>) : SessionFolderList {
  override val count = sessions.size
  override fun get(i : Int) : RESTSessionFolder = sessions[i]
  override fun iterator() = sessions.iterator()
}

fun Bitmap.savePng(s : OutputStream) = compress(Bitmap.CompressFormat.PNG, 85, s)
