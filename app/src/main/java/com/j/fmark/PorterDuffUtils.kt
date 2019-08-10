package com.j.fmark

import android.graphics.PorterDuff
import android.graphics.PorterDuff.Mode

// Utils that are hidden in PorterDuff so I need to rewrite them >.>
fun PorterDuff.Mode.toInt() : Int {
  return when (this) {
    Mode.CLEAR    -> 0
    Mode.SRC      -> 1
    Mode.DST      -> 2
    Mode.SRC_OVER -> 3
    Mode.DST_OVER -> 4
    Mode.SRC_IN   -> 5
    Mode.DST_IN   -> 6
    Mode.SRC_OUT  -> 7
    Mode.DST_OUT  -> 8
    Mode.SRC_ATOP -> 9
    Mode.DST_ATOP -> 10
    Mode.XOR      -> 11
    Mode.ADD      -> 12
    Mode.MULTIPLY -> 13
    Mode.SCREEN   -> 14
    Mode.OVERLAY  -> 15
    Mode.DARKEN   -> 16
    Mode.LIGHTEN  -> 17
    else          -> 0
  }
}

fun Int.toPorterDuffMode() : Mode {
  return when (this) {
    0    -> Mode.CLEAR
    1    -> Mode.SRC
    2    -> Mode.DST
    3    -> Mode.SRC_OVER
    4    -> Mode.DST_OVER
    5    -> Mode.SRC_IN
    6    -> Mode.DST_IN
    7    -> Mode.SRC_OUT
    8    -> Mode.DST_OUT
    9    -> Mode.SRC_ATOP
    10   -> Mode.DST_ATOP
    11   -> Mode.XOR
    12   -> Mode.ADD
    13   -> Mode.MULTIPLY
    14   -> Mode.SCREEN
    15   -> Mode.OVERLAY
    16   -> Mode.DARKEN
    17   -> Mode.LIGHTEN
    else -> Mode.CLEAR
  }
}
