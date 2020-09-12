package com.j.fmark

import android.util.Log
import android.util.SparseArray
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.io.StreamCorruptedException

typealias FEditorDataType = Double

data class Drawing(val code : Int, val data : List<FEditorDataType>)
data class SessionData(var comment : String, val face : Drawing, val front : Drawing, val back : Drawing) {
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
}

fun SessionData() = SessionData("", face = Drawing(FACE_CODE, ArrayList()), front = Drawing(FRONT_CODE, ArrayList()), back = Drawing(BACK_CODE, ArrayList()))
fun SessionData(inputStream : InputStream) : SessionData {
  fun SparseArray<Drawing>.getOrEmpty(code : Int) = this[code] ?: Drawing(code, ArrayList())
  var comment : String? = null
  val drawings = SparseArray<Drawing>()
  try {
    ObjectInputStream(BufferedInputStream(inputStream)).use {
      comment = it.readObject() as String
      while (true) {
        val code = it.readInt()
        @Suppress("UNCHECKED_CAST") // Contained type can't be checked at runtime because of erasure, no free lunch
        drawings.put(code, Drawing(code, it.readObject() as List<FEditorDataType>))
      }
    }
  } catch (e : EOFException) { /* done */
  } catch (e : StreamCorruptedException) {
    Log.e("corrupted ?", "hm", e)
  }
  return SessionData(comment = comment ?: "", face = drawings.getOrEmpty(FACE_CODE), front = drawings.getOrEmpty(FRONT_CODE), back = drawings.getOrEmpty(BACK_CODE))
}

fun SessionData.save(outputStream : OutputStream) {
  ObjectOutputStream(outputStream).use { os ->
    os.writeObject(comment)
    forEach {
      os.writeInt(it.code)
      // ArrayList is serializable
      os.writeObject(ArrayList(it.data))
    }
  }
}
