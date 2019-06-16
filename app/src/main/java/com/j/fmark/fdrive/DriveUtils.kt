package com.j.fmark.fdrive

import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

suspend fun Drive.Files.List.executeFully() : List<File>
{
  val list = this
  return withContext(Dispatchers.Default) {
    val result = ArrayList<File>()
    var pageToken : String? = null
    do {
      list.pageToken = pageToken
      val page = list.execute()
      result.addAll(page.files)
      pageToken = page.nextPageToken
    } while (null != pageToken)
    result
  }
}
