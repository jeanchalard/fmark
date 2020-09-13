package com.j.fmark.fdrive

import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.j.fmark.LOGEVERYTHING
import com.j.fmark.logAlways
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.Exception

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("DriveUtils", s, e) }

const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

suspend fun Drive.Files.List.executeFully() : List<File> {
  val list = this
  return withContext(Dispatchers.IO) {
    log("Execute fully ${this@executeFully}")
    val result = ArrayList<File>()
    var pageToken : String? = null
    do {
      list.pageToken = pageToken
      val page = list.execute()
      result.addAll(page.files)
      pageToken = page.nextPageToken
      log("Loaded page, ${page.files.size} files, next token ${pageToken}")
    } while (null != pageToken)
    result
  }
}
