package com.j.fmark

import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.io.StreamCorruptedException
import java.lang.Exception

const val FACE_CODE = 0
const val FRONT_CODE = 1
const val BACK_CODE = 2
private fun codeToImageName(code : Int) = when (code) {
  FACE_CODE  -> FACE_IMAGE_NAME
  FRONT_CODE -> FRONT_IMAGE_NAME
  BACK_CODE  -> BACK_IMAGE_NAME
  else                   -> throw IllegalArgumentException("Unknown image code ${code}")
}
private fun codeToResourceId(code : Int) = when (code) {
  FACE_CODE  -> R.drawable.face
  FRONT_CODE -> R.drawable.front
  BACK_CODE  -> R.drawable.back
  else                   -> throw IllegalArgumentException("Unknown image code ${code}")
}

typealias FEditorDataType = Double
data class Drawing(val code : Int, val guideId : Int, val fileName : String, val data : ArrayList<FEditorDataType>)
data class SessionData(var comment : String, val face : Drawing, val front : Drawing, val back : Drawing) {
  class Builder
  {
    var comment : String? = null
    private var face : Drawing? = null
    private var front : Drawing? = null
    private var back : Drawing? = null
    operator fun set(code : Int, value : Drawing)
    {
      when (code)
      {
        FACE_CODE  -> face = value
        FRONT_CODE -> front = value
        BACK_CODE  -> back = value
      }
    }
    fun build() : SessionData = SessionData(
     comment ?: "",
     face ?: Drawing(FACE_CODE, R.drawable.face, FACE_IMAGE_NAME, ArrayList()),
     front ?: Drawing(FRONT_CODE, R.drawable.front, FRONT_IMAGE_NAME, ArrayList()),
     back ?: Drawing(BACK_CODE, R.drawable.back, BACK_IMAGE_NAME, ArrayList()))
  }
  operator fun get(code : Int) = when (code)
  {
    FACE_CODE  -> face
    FRONT_CODE -> front
    BACK_CODE  -> back
    else                   -> throw IllegalArgumentException("Unknown image code ${code}")
  }
  fun forEach(f : (Drawing) -> Unit)
  {
    f(face)
    f(front)
    f(back)
  }
}

fun SessionData() = SessionData.Builder().build()
fun SessionData(inputStream : InputStream) : SessionData
{
  val contents = SessionData.Builder()
  try
  {
//    val a = ByteArray(1024 * 200)
//    val read = inputStream.read(a)
//    log("data ${read} : ${a.joinTo(StringBuffer())}")
//    val read2 = inputStream.read(a)
//    log("data2 ${read2} : ${a.joinTo(StringBuffer())}")
    ObjectInputStream(BufferedInputStream(inputStream)).use {
      log(">>> 1")
      try { contents.comment = it.readObject() as String } catch (e : Exception) { log("Oui mais non ${e}") }
      log(">>> 2 ${contents.comment}")
      while (true)
      {
        val code = it.readInt()
        log("Got ${code}")
        @Suppress("UNCHECKED_CAST") // Contained type can't be checked at runtime because of erasure, no free lunch
        val data = it.readObject() as ArrayList<FEditorDataType>
        contents[code] = Drawing(code, codeToResourceId(code), codeToImageName(code), data)
      }
    }
  }
  catch (e : EOFException) { /* done */ }
  catch (e : StreamCorruptedException) {
    Log.e("corrupted ?", "hm", e)
  }
  return contents.build()
}

fun SessionData.save(outputStream : OutputStream)
{
  ObjectOutputStream(outputStream).use { os ->
    os.writeObject(comment)
    forEach {
      os.writeInt(it.code)
      os.writeObject(it.data)
    }
  }
}
