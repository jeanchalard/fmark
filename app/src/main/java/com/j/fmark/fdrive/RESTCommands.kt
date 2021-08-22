package com.j.fmark.fdrive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.common.api.ApiException
import com.google.api.client.http.InputStreamContent
import com.j.fmark.LOGEVERYTHING
import com.j.fmark.LiveCache
import com.j.fmark.fdrive.CommandStatus.CommandResult
import com.j.fmark.logAlways
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import com.google.api.services.drive.model.File as DriveFile

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("RESTCommands", s, e) }

class Worker(private val context : Context, params : WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork() = withContext(Dispatchers.IO) { CommandRunner(context).runCommands() }
}

class CommandRunner(private val context : Context) {
  // Returns null if this has to be retried later. Returns a CommandResult with a null DriveFile if it failed.
  private suspend fun createFolder(seq : Long, root : FDrive.Root, parentFolderId : String?, folderName : String?, tryCount : Int = 0) : CommandResult? {
    log("Create folder command : ${parentFolderId}/${folderName}")
    if (null == parentFolderId) throw IllegalArgumentException("Parent folder ID can't be null in createFolder")
    if (null == folderName) throw IllegalArgumentException("Folder name can't be null in createFolder")
    try {
      val parent = DriveFile().also { it.id = parentFolderId }
      return CommandResult(seq, FDrive.createDriveFolder(root.drive.await(), parent, folderName)).also { log("Create folder command succeeded") }
    } catch (e : ConnectException) {
      log("Connection exception while creating folder", e)
      delay(1000) // Just in case the system would rerun this immediately because it hasn't noticed yet
      return null
    } catch (e : Exception) {
      log("Couldn't create folder", e)
      if (tryCount > 2) return CommandResult(seq, null)
      delay(5000)
      return createFolder(seq, root, parentFolderId, folderName, tryCount + 1)
    }
  }

  private suspend fun renameFile(seq : Long, root : FDrive.Root, fileId : String?, newName : String?, tryCount : Int = 0) : CommandResult? {
    log("Rename file command, ${fileId} -> ${newName}")
    if (null == fileId) throw IllegalArgumentException("File ID can't be null in renameFile")
    if (null == newName) throw IllegalArgumentException("New name can't be null in renameFile")
    try {
      val file = DriveFile().also { it.id = fileId }
      val renamedFile = FDrive.renameFile(root.drive.await(), file, newName) ?: throw IllegalStateException("File with id ${fileId} doesn't exist")
      log("Rename file command succeeded")
      return CommandResult(seq, renamedFile)
    } catch (e : ConnectException) {
      log("Connection exception while renaming file", e)
      delay(1000) // Just in case the system would rerun this immediately because it hasn't noticed yet
      return null
    } catch (e : Exception) {
      log ("Couldn't rename file", e)
      if (tryCount > 2) return CommandResult(seq, null)
      delay(5000)
      return renameFile(seq, root, fileId, newName, tryCount + 1)
    }
  }

  private suspend fun putFile(seq : Long, root : FDrive.Root, fileId : String?, fileName : String?, data : ByteArray?, mimeType : String?, tryCount : Int = 0) : CommandResult? {
    log("Put file command, fileId = \"${fileId}\" or fileName = \"${fileName}\", data with length ${data?.size}, type = ${mimeType}, tryCount = ${tryCount}")
    if (null == data) throw IllegalArgumentException("Data can't be null in putFile")
    if (null == mimeType) throw IllegalArgumentException("mimeType can't be null in putFile")
    try {
      val id = when {
        null != fileId   -> fileId
        null != fileName -> FDrive.createDriveFile(root.drive.await(), root.root.await(), fileName).id
        else             -> throw IllegalArgumentException("Either fileId or fileName must be non-null in putFile")
      }
      val file = DriveFile().apply { this.mimeType = mimeType }
      root.drive.await().files().update(id, file, InputStreamContent(mimeType, data.inputStream())).execute()
      log("Put file command succeeded")
      return CommandResult(seq, file)
    } catch (e : ConnectException) {
      log("Connection exception while putting file", e)
      delay(1000) // Just in case the system would rerun this immediately because it hasn't noticed yet
      return null
    } catch (e : Exception) {
      log ("Couldn't put file", e)
      if (tryCount > 2) return CommandResult(seq, null)
      delay(5000)
      return putFile(seq, root, fileId, fileName, data, mimeType, tryCount + 1)
    }
  }

  public suspend fun runCommands() : Result {
    log("Running commands...")
    CommandStatus.working = true
    val drive = LiveCache.getRoot { FDrive.Root(context) }
    val saveQueue = SaveQueue.get(context)
    while (true) {
      log("Getting next command")
      val command = saveQueue.getNext() ?: break
      log("Command is ${command.type}")
      val result = try {
        when (command.type) {
          Type.NONE          -> CommandResult(0L, null)
          Type.CREATE_FOLDER -> createFolder(command.seq, drive, command.fileId, command.name)
          Type.RENAME_FILE   -> renameFile(command.seq, drive, command.fileId, command.name)
          Type.PUT_FILE      -> putFile(command.seq, drive, command.fileId, command.name, command.binData, command.metadata)
        }
      } catch (e : Exception) {
        if (e !is ExecutionException && e !is ApiException) throw e
        // In case of a login failure, there is no point in retrying until the user logs in again. The commands also should
        // not be marked done. Returning success() here will stop processing the chain and mark the Work complete, so it
        // won't be retried after the backoff. The next time the app is run, however, the user will have an opportunity to
        // log in and the manager will be tickled, retrying the command queue where it was left off.
        else return Result.success()
      }
      when {
        result == null -> return Result.retry()
        result.driveFile == null -> log("Command ${command} failed ; continuing")
        else -> log("Executed ${command} successfully")
      }
      saveQueue.markDone(command)
      // Trigger all the listeners and unblock code waiting on this command to be processed. See the setter for lastExecutedCommand.
      // Note that if result is null the control doesn't even come here (the compiler checks this)
      CommandStatus.lastExecutedCommand = result
    }
    log("Finished running commands")
    CommandStatus.working = false
    return Result.success()
  }
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
