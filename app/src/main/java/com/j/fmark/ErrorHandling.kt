package com.j.fmark

import java.io.IOException

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("ErrorHandling", s, e) }

object ErrorHandling {
  fun fileSystemIsNotWritable() {
    log("Error : file system is not writable")
    throw IOException("File system is not writable")
  }
  fun unableToSave() {
    log("Error : unable to save")
  }
  fun renameCommandFailed() {

  }
}
