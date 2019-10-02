package com.j.fmark.fdrive

import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile

sealed class RESTCommand {
  abstract suspend fun run(drive : Drive, rootFolder : DriveFile)
}

class CreateClientCommand(private val folderName : String) : RESTCommand() {
  override suspend fun run(drive : Drive, rootFolder : DriveFile) {
    FDrive.createFolder(drive, rootFolder, folderName)
  }
}
