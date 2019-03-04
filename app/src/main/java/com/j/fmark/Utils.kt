package com.j.fmark

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale

fun color(l : Long) : Int = (l and -1L).toInt()
fun formatDate(date : Date?) : String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date ?: Date())

// Simple and stupid utility that's not very useful for real stuff but is vastly easier to use than the classes that are
data class LocalSecond(val year : Int, val month : Int, val day : Int, val hour : Int, val minute : Int, val second : Int)
{
  constructor(gc : GregorianCalendar) : this(gc.get(Calendar.YEAR), 1 + gc.get(Calendar.MONTH), gc.get(Calendar.DATE),
   gc.get(Calendar.HOUR_OF_DAY), gc.get(Calendar.MINUTE), gc.get(Calendar.SECOND))
  constructor(t : Long) : this(GregorianCalendar().apply { timeInMillis = t })
  constructor(d : Date) : this(d.time)
  constructor() : this(0, 0, 0, 0, 0, 0)

  override fun toString() = String.format("%04d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, minute, second)
  fun toShortString() = String.format("%04d-%02d-%02d %02d:%02d", year, month, day, hour, minute)
}

class IllegalDateException(s : String) : Exception(s)
fun parseLocalSecond(s : String) : LocalSecond
{
  val r = Regex("(\\d\\d\\d\\d)-(\\d\\d)-(\\d\\d) (\\d\\d):(\\d\\d):(\\d\\d)").matchEntire(s)?.groupValues ?: throw IllegalDateException(s)
  return LocalSecond(r[1].toInt(), r[2].toInt(), r[3].toInt(), r[4].toInt(), r[5].toInt(), r[6].toInt())
}
