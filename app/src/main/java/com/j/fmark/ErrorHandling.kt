package com.j.fmark

import java.io.IOException

object ErrorHandling {
  fun fileSystemIsNotWritable() {
    throw IOException("File system is not writable")
  }
  fun unableToSave() {}
}
