package com.j.fmark

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Interpolator
import android.net.Network
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatImageButton
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.j.fmark.fdrive.CommandStatus
import java.util.UUID

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("CloudButton", s, e) }

class CloudButton @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0) : AppCompatImageButton(context, attrs, defStyleAttr), View.OnClickListener {
  // Icon levels
  private val CLEAN = 0
  private val NO_NETWORK = 1
  private val UPLOADING = 2
  private val DIRTY = 3

  val animation = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f).apply {
    duration = 750
    interpolator = FastOutSlowInInterpolator()
    repeatCount = ValueAnimator.INFINITE
    repeatMode = ValueAnimator.REVERSE
  }

  var network : Network? = null
  var clean = true
  var uploading = false

  init {
    setImageResource(R.drawable.cloud)
    isSaveEnabled = true
    setPadding(drawable.intrinsicWidth / 2, drawable.intrinsicHeight / 2, drawable.intrinsicWidth / 2, drawable.intrinsicHeight / 2)
    getNetworking(context).addListener {
      network = it
      log("Network is ${network}")
      updateIconLevel()
    }
    CommandStatus.addWorkingListener { working ->
      log("Is uploading : ${working}")
      uploading = working
      if (!working) clean = true
      updateIconLevel()
    }
    setOnClickListener(this)
  }

  // Kind of crappy honestly. Whenever the fragment changes, Android will recreate the menu from scratch.
  override fun onRestoreInstanceState(state : Parcelable) {
    super.onRestoreInstanceState(state)
    if (state !is SaveState) return
    clean = state.clean
    uploading = state.uploading
  }

  private class SaveState : BaseSavedState {
    val clean : Boolean
    val uploading : Boolean
    constructor(p : Parcelable?, clean : Boolean, uploading : Boolean) : super(p) {
      this.clean = clean
      this.uploading = uploading
    }
    constructor(p : Parcel) : super(p) {
      val v = p.readInt() // readBoolean is API 29
      clean = 0 != v and 1
      uploading = 0 != v and 2
    }

    override fun writeToParcel(out : Parcel?, flags : Int) {
      super.writeToParcel(out, flags)
      if (null == out) return
      out.writeInt(if (clean) 1 else 0 + if (uploading) 2 else 0)
    }

    companion object {
      @JvmStatic val CREATOR = object : Parcelable.Creator<SaveState> {
        override fun createFromParcel(p : Parcel) : SaveState = SaveState(p)
        override fun newArray(size : Int) : Array<SaveState> = newArray(size)
      }
    }
  }

  override fun onSaveInstanceState() : Parcelable? {
    return SaveState(super.onSaveInstanceState(), clean, uploading)
  }

  fun signalDirty() {
    log("signalDirty")
    clean = false
    updateIconLevel()
  }

  private fun updateIconLevel() = post {
    val level = when {
      clean -> CLEAN // Nothing to save, clean icon : a checkmark on a cloud
      null == network -> NO_NETWORK // No network, impossible to save, no networking icon : a stroked-out cloud
      uploading -> UPLOADING // Currently uploading, uploading icon : a blinking cloud with an up arrow
      else -> DIRTY // Data to save, dirty icon : a cloud outline
    }
    log("Update icon level to ${level} (clean = ${clean} ; network = ${network} ; uploading = ${uploading})")
    setImageLevel(level)
    if (UPLOADING == level) animation.start() else {
      animation.cancel()
      alpha = 1.0f
    }
    isClickable = (DIRTY == level)
  }

  override fun onClick(v : View?) {
    log("Click cloud")
  }
}
