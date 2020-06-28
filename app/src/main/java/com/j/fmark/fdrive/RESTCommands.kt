package com.j.fmark.fdrive

import com.google.api.services.drive.Drive
import com.j.fmark.fdrive.FDrive.encodeClientFolderName
import com.google.api.services.drive.model.File as DriveFile

sealed class RESTCommand {
  abstract suspend fun run(drive : Drive, rootFolder : DriveFile)
}

class CreateClientCommand(private val name : String, private val reading : String) : RESTCommand() {
  override suspend fun run(drive : Drive, rootFolder : DriveFile) {
    FDrive.createDriveFolder(drive, rootFolder, encodeClientFolderName(name, reading))
  }
}
