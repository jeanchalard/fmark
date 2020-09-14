package com.j.fmark.fdrive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.api.client.json.GenericJson
import com.google.api.client.json.gson.GsonFactory
import com.j.fmark.LOGEVERYTHING
import com.j.fmark.LiveCache
import com.j.fmark.fdrive.CommandStatus.CommandResult
import com.j.fmark.logAlways
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import com.google.api.services.drive.model.File as DriveFile

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("RESTCommands", s, e) }

const val JSON_OUT = "json"

sealed class RESTCommand {
  abstract val workerClass : Class<out ListenableWorker>
  abstract val inputData : Data
}

// This is terrible, awful, abject, but it's by a mile the simplest way to deal with serialization of drive files
// and at least it's fairly likely to be stable and give me the exact same object without having to list myself
// all the fields I care about + whatever is internally needed and their associated type and some custom parsing thereof.
inline fun <reified T> String.parseJson() : T = GsonFactory().createJsonParser(this).parse(T::class.java)
inline fun GenericJson.toJson() = GsonFactory().toString(this)

class Worker(private val context : Context, params : WorkerParameters) : CoroutineWorker(context, params) {
  // Returns null if this has to be retried later. Returns a CommandResult with a null DriveFile if it failed.
  suspend fun createFolder(seq : Long, root : FDrive.Root, parentFolderId : String?, folderName : String?, tryCount : Int = 0) : CommandResult? {
    log("Create folder command : ${parentFolderId}/${folderName}")
    if (null == parentFolderId) throw IllegalArgumentException("Parent folder ID can't be null in createFolder")
    if (null == folderName) throw IllegalArgumentException("Folder name can't be null in createFolder")
    try {
      val parent = DriveFile().also { it.id = parentFolderId }
      return CommandResult(seq, FDrive.createDriveFolder(root.drive, parent, folderName))
    } catch (_ : ConnectException) {
      delay(1000) // Just in case the system would rerun this immediately because it hasn't noticed yet
      return null
    } catch (e : Exception) {
      log("Couldn't create folder", e)
      if (tryCount > 2) return CommandResult(seq, null)
      delay(5000)
      return createFolder(seq, root, parentFolderId, folderName, tryCount + 1)
    }
  }

  suspend fun renameFile(seq : Long, root : FDrive.Root, fileId : String?, newName : String?, tryCount : Int = 0) : CommandResult? {
    log("Rename folder command, ${fileId} -> ${newName}")
    if (null == fileId) throw IllegalArgumentException("File ID can't be null in renameFile")
    if (null == newName) throw IllegalArgumentException("New name can't be null in renameFile")
    try {
      val file = DriveFile().also { it.id = fileId }
      val renamedFile = FDrive.renameFile(root.drive, file, newName) ?: throw IllegalStateException("File with id ${fileId} doesn't exist")
      return CommandResult(seq, renamedFile)
    } catch (_ : ConnectException) {
      delay(1000) // Just in case the system would rerun this immediately because it hasn't noticed yet
      return null
    } catch (e : IllegalStateException) {
      log ("Couldn't rename file", e)
      if (tryCount > 2) return CommandResult(seq, null)
      delay(5000)
      return renameFile(seq, root, fileId, newName, tryCount + 1)
    }
  }

  private suspend fun runCommands() : Result {
    val drive = LiveCache.getRoot { FDrive.Root(context) }
    while (true) {
      val command = SaveQueue.get(context).getNext() ?: break
      val result = when (command.type) {
        Type.CREATE_FOLDER -> createFolder(command.seq, drive, command.fileId, command.name)
        Type.RENAME_FILE   -> renameFile(command.seq, drive, command.fileId, command.name)
        Type.PUT_FILE      -> TODO()
      }
      when {
        result == null -> return Result.retry()
        result.driveFile == null -> log("Command ${command} failed ; continuing")
        else -> log("Executed ${command} successfully")
      }
      // Trigger all the listeners and unblock code waiting on this command to be processed. See the setter for lastExecutedCommand.
      // Note that if result is null the control doesn't even come here (the compiler checks this)
      CommandStatus.lastExecutedCommand = result
    }
    return Result.success();
  }

  override suspend fun doWork() = withContext(Dispatchers.IO) { runCommands() }
}

class RESTManager(context : Context) {
  companion object {
    const val WORK_NAME = "Clients work queue"
  }
  private val workManager = WorkManager.getInstance(context)
  private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

  fun tickle() {
    val request = OneTimeWorkRequest.Builder(Worker::class.java)
     .setConstraints(constraints)
     .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
     .build()
    workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
  }
}
