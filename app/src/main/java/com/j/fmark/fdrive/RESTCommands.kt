package com.j.fmark.fdrive

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.api.client.json.GenericJson
import com.google.api.client.json.gson.GsonFactory
import com.j.fmark.LiveCache
import com.j.fmark.log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.google.api.services.drive.model.File as DriveFile

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

fun CoroutineWorker.finish(res : DriveFile?) = when (res) {
  null -> Result.retry()
  else -> Result.success(workDataOf(JSON_OUT to res.toJson()))
}

class CreateFolderCommand(parentFolderId : String, folderName : String) : RESTCommand() {
  override val workerClass = W::class.java
  override val inputData = workDataOf("parentFolderId" to parentFolderId, "folderName" to folderName)
  class W(private val context : Context, private val params : WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() : Result = withContext(Dispatchers.IO) {
      try {
        val drive = LiveCache.getRoot { FDrive.Root(context) }
        val parentFolderId = params.inputData.getString("parentFolderId") ?: throw IllegalArgumentException("No parent folder")
        val folderName = params.inputData.getString("folderName") ?: throw IllegalArgumentException("No folder name")
        log("Creating folder ${parentFolderId}/${folderName}")
        val parent = DriveFile().also { it.id = parentFolderId }
        finish(FDrive.createDriveFolder(drive.drive, parent, folderName))
      } catch (e : Exception) {
        log("Couldn't create folder", e)
        Result.retry()
      }
    }
  }
}

class RenameFolderCommand(oldFolderName : String, newFolderName : String) : RESTCommand() {
  override val workerClass = W::class.java
  override val inputData = workDataOf("oldFolderName" to oldFolderName, "newFolderName" to newFolderName)
  class W(private val context : Context, private val params : WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() : Result = withContext(Dispatchers.IO) {
      log("Running ${this::class} with ${params.inputData}")
      try {
        val root = LiveCache.getRoot { FDrive.Root(context) }
        val oldFolderName = params.inputData.getString("oldFolderName") ?: throw IllegalArgumentException("No old folder name")
        val newFolderName = params.inputData.getString("newFolderName") ?: throw IllegalArgumentException("No new folder name")
        val existingFolder = FDrive.fetchDriveFolder(root.drive, oldFolderName, root.root)
        if (null != existingFolder) {
          finish(FDrive.renameFolder(root.drive, existingFolder, newFolderName))
        } else {
          log("Folder doesn't exist ${oldFolderName}")
          Result.success() // Don't retry and don't cancel subsequent work
        }
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

  fun exec(command : RESTCommand) : Deferred<DriveFile> {
     val request = OneTimeWorkRequest.Builder(command.workerClass)
      .setConstraints(constraints)
      .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
      .setInputData(command.inputData)
      .build()
    workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND, request)
    val liveData = workManager.getWorkInfosForUniqueWorkLiveData(WORK_NAME)
    // This is terrible. I want to write liveData.observeForever { workInfos -> ... }, but that won't work because Kotlin doesn't like
    // to provide a `this` pointer in a lambda. https://discuss.kotlinlang.org/t/is-this-accessible-in-sams/1477
    // Further, I can't just call it here because "you can't call liveData.observeForever on a background thread" because reasons.
    // I wonder if I'm not better off just polling the frigging workInfo.
    return GlobalScope.async(Dispatchers.Main) {
      val result = CompletableDeferred<DriveFile>()
      liveData.observeForever(object : Observer<List<WorkInfo>> {
        override fun onChanged(workInfos : List<WorkInfo>) {
          val workInfo = workInfos.last()
          log("" + workInfo.state)
          if (workInfo.state != WorkInfo.State.SUCCEEDED) return
          // This is awful, but that's because the Drive REST API is awful to work with
          when (val output = workInfo.outputData.getString(JSON_OUT)?.parseJson<DriveFile>()) {
            null -> result.completeExceptionally(RuntimeException("Yeah well ${output} ; ${command}"))
            else -> result.complete(output)
          }
          liveData.removeObserver(this)
        }
      })
      result.await()
    }
  }
}
