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
import androidx.work.workDataOf
import com.j.fmark.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

sealed class RESTCommand {
  abstract val workerClass : Class<out ListenableWorker>
  abstract val inputData : Data
}

class CreateFolderCommand(folderName : String) : RESTCommand() {
  override val workerClass = W::class.java
  override val inputData = workDataOf("folderName" to folderName)
  class W(private val context : Context, private val params : WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() : Result = withContext(Dispatchers.IO) {
      try {
        var drive = FDrive.Root(context)
        val folderName = params.inputData.getString("folderName") ?: throw IllegalArgumentException("No folder name")
        log("Creating client ${folderName}")
        FDrive.createDriveFolder(drive.drive, drive.root, folderName)
        Result.success()
      } catch (e : Exception) {
        log("Couldn't create folder", e)
        Result.retry()
      }
    }
  }
}

class RenameFolderCommand(oldFolderName : String, newFolderName : String) : RESTCommand() {
  override val workerClass = W::class.java
  override val inputData = workDataOf("oldFolderName" to oldFolderName, "newFoldenName" to newFolderName)
  class W(private val context : Context, private val params : WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() : Result = withContext(Dispatchers.IO) {
      try {
        var root = FDrive.Root(context)
        val oldFolderName = params.inputData.getString("oldFolderName") ?: throw IllegalArgumentException("No old folder name")
        val newFolderName = params.inputData.getString("newFolderName") ?: throw IllegalArgumentException("No new folder name")
        FDrive.renameFolder(root.drive, FDrive.fetchDriveFolder(root.drive, oldFolderName), newFolderName)
        Result.success()
      } catch (e : Exception) {
        log("Couldn't rename folder", e)
        Result.retry()
      }
    }
  }
}

class RESTManager(context : Context) {
  companion object {
    const val WORK_NAME = "Clients work queue"
  }
  private val workManager = WorkManager.getInstance(context)
  private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

  fun exec(command : RESTCommand) {
    workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND,
     OneTimeWorkRequest.Builder(command.workerClass)
     .setConstraints(constraints)
     .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
     .setInputData(command.inputData)
     .build())
  }
}
