package com.j.fmark

import android.util.Log
import com.google.api.client.util.DateTime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale

fun log(msg : String, e : Exception? = null) = Log.e("Clients", msg, e)
fun logStackTrace(msg : String) = stackTrace().drop(2).forEach { Log.e(msg, "{$it}") }
val <T> T.unit get() = Unit

fun stackTrace() = try { throw RuntimeException() } catch (e : RuntimeException) { e.stackTrace.slice(1 until e.stackTrace.size) }

fun color(l : Long) : Int = (l and -1L).toInt()
fun formatDate(date : Date?) : String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date ?: Date())
fun formatDate(date : DateTime) : String = LocalSecond(date).toDateString()
fun formatDate(date : Long) : String = LocalSecond(date).toDateString()

private fun roundTo(v : Long, granularity : Long) = (v % granularity).let { mod -> if (mod * 2 > granularity) v + granularity - mod else v - mod }

// Simple and stupid utility that's not very useful for real stuff but is vastly easier to use than the classes that are
data class LocalSecond(val year : Int, val month : Int, val day : Int, val hour : Int, val minute : Int, val second : Int) {
  private constructor(gc : GregorianCalendar) : this(gc.get(Calendar.YEAR), 1 + gc.get(Calendar.MONTH), gc.get(Calendar.DATE),
   gc.get(Calendar.HOUR_OF_DAY), gc.get(Calendar.MINUTE), gc.get(Calendar.SECOND))
  constructor(t : Long) : this(GregorianCalendar().apply { timeInMillis = roundTo(t, 30 * 60 * 1000) })
  constructor(d : Date) : this(d.time)
  constructor(d : DateTime) : this(d.value)

  override fun toString() = String.format("%04d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, minute, second)
  fun toShortString() = String.format("%04d-%02d-%02d %02d:%02d", year, month, day, hour, minute)
  fun toDateString() = String.format("%04d-%02d-%02d", year, month, day)
}

class IllegalDateException(s : String) : Exception(s)
fun parseLocalSecond(s : String) : LocalSecond {
  val r = Regex("(\\d\\d\\d\\d)-(\\d\\d)-(\\d\\d) (\\d\\d):(\\d\\d):(\\d\\d)").matchEntire(s)?.groupValues ?: throw IllegalDateException(s)
  return LocalSecond(r[1].toInt(), r[2].toInt(), r[3].toInt(), r[4].toInt(), r[5].toInt(), r[6].toInt())
}

fun ByteArray.toLong() = if (size != Long.SIZE_BYTES) throw NumberFormatException() else fold(0L) { acc, b -> acc shl 8 + b }
fun Long.toBytes() = ByteArray(Long.SIZE_BYTES) { index -> ((this shr (index * 8)) and 0xFF).toByte() }

fun codeToResource(code : Int) = when (code) {
  FACE_CODE  -> R.drawable.face
  FRONT_CODE -> R.drawable.front
  BACK_CODE  -> R.drawable.back
  else                                      -> throw IllegalArgumentException("Unknown image code ${code}")
}
