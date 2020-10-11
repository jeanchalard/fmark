package com.j.fmark

import android.util.SparseArray
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.io.StreamCorruptedException

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("SessionData", s, e) }

typealias FEditorDataType = Double

data class Drawing(val code : Int, val data : List<FEditorDataType>)
data class SessionData(var comment : String, val face : Drawing, val front : Drawing, val back : Drawing, val modifiedTime : Long) {
  operator fun get(code : Int) = when (code) {
    FACE_CODE  -> face
    FRONT_CODE -> front
    BACK_CODE  -> back
    else       -> throw IllegalArgumentException("Unknown image code ${code}")
  }

  fun forEach(f : (Drawing) -> Unit) {
    f(face)
    f(front)
    f(back)
  }

  // modifiedDate is different between the cache and the server and it's fairly annoying to fix (not too much on the down path where it's
  // easy to set the local modified date to what the server said, but more on the up path where the app doesn't have the timestamp of
  // the server), so have equals() ignore it. It does make sense in usage and it's used in Networking as == to tell if the data is the
  // same when the server load finishes after the data has been opened from cache to tell whether to re-show, so the alternative would be
  // that all users of Networking.load() need to pass yet an additional lambda to compare.
  override fun equals(other : Any?) : Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as SessionData
    return (comment == other.comment && face == other.face && front == other.front && back == other.back)
  }

  override fun hashCode() : Int {
    var result = comment.hashCode()
    result = 31 * result + face.hashCode()
    result = 31 * result + front.hashCode()
    result = 31 * result + back.hashCode()
    return result
  }
}

fun SessionData(modifiedTime : Long, inputStream : InputStream) : SessionData {
  log("Creating SessionData from input stream ${inputStream}")
  fun SparseArray<Drawing>.getOrEmpty(code : Int) = this[code] ?: Drawing(code, ArrayList())
  var comment : String? = null
  val drawings = SparseArray<Drawing>()
  try {
    ObjectInputStream(BufferedInputStream(inputStream)).use {
      comment = it.readObject() as String
      log("...read comment = ${comment}")
      while (true) {
        val code = it.readInt()
        @Suppress("UNCHECKED_CAST") // Contained type can't be checked at runtime because of erasure, no free lunch
        drawings.put(code, Drawing(code, it.readObject() as List<FEditorDataType>))
        log("...read drawing with code ${code} and ${drawings[code].data.size} data points")
      }
    }
  } catch (e : EOFException) { /* done */
    log("Finished reading session data.")
  } catch (e : StreamCorruptedException) {
    log("Stream corrupted :/")
  }
  return SessionData(comment = comment ?: "", face = drawings.getOrEmpty(FACE_CODE), front = drawings.getOrEmpty(FRONT_CODE), back = drawings.getOrEmpty(BACK_CODE),
   modifiedTime = modifiedTime)
}

fun SessionData.save(outputStream : OutputStream) {
  log("Saving session data to output stream \"${outputStream}\"")
  ObjectOutputStream(outputStream).use { os ->
    log("Writing comment ${comment}")
    os.writeObject(comment)
    forEach {
      log("Writing code ${it.code}")
      os.writeInt(it.code)
      log("Writing data with ${it.data.size} data points")
      // ArrayList is serializable
      os.writeObject(ArrayList(it.data))
    }
  }
}
