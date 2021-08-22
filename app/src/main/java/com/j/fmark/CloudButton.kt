package com.j.fmark

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.net.Network
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatImageButton
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.j.fmark.fdrive.CommandStatus

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("CloudButton", s, e) }

class CloudButton @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0) : AppCompatImageButton(context, attrs, defStyleAttr), View.OnClickListener {
  // Icon levels
  private val CLEAN = 0
  private val NO_NETWORK = 1
  private val UPLOADING = 2
  private val DIRTY = 3

  private val animation = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f).apply {
    duration = 750
    interpolator = FastOutSlowInInterpolator()
    repeatCount = ValueAnimator.INFINITE
    repeatMode = ValueAnimator.REVERSE
  }

  private var clean = true
  private var hasPendingWork = false
  private var network : Network? = null
  var host : FMark? = null

  override fun onClick(v : View?) {
    log("onClick")
    host?.onOptionsItemSelected(R.id.action_button_save)
  }

  private val pendingListener : (Boolean) -> Unit = { pending ->
    log("Has pending work : ${pending}")
    hasPendingWork = pending
    clean = !pending
    updateIconLevel()
  }
  private val networkListener : (Network?) -> Unit = {
    network = it
    log("Network is ${network}")
    updateIconLevel()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    CommandStatus.workPendingListeners.add(pendingListener)
    getNetworking(context).addListener(networkListener)
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    CommandStatus.workPendingListeners.remove(pendingListener)
    getNetworking(context).removeListener(networkListener)
  }

  init {
    log("Creating cloud button")
    setImageResource(R.drawable.cloud)
    isSaveEnabled = true
    setPadding(drawable.intrinsicWidth / 2, drawable.intrinsicHeight / 2, drawable.intrinsicWidth / 2, drawable.intrinsicHeight / 2)
    setOnClickListener(this)
  }

  fun signalDirty() {
    log("signalDirty")
    clean = false
    updateIconLevel()
  }

  private fun updateIconLevel() = post {
    val level = when {
      clean           -> CLEAN // Nothing to save, clean icon : a checkmark on a cloud
      null == network -> NO_NETWORK // No network, impossible to save, no networking icon : a stroked-out cloud
      hasPendingWork  -> UPLOADING // Currently uploading, uploading icon : a blinking cloud with an up arrow
      else            -> DIRTY // Data to save, dirty icon : a cloud outline
    }
    log("Update icon level to ${level} (clean = ${clean} ; network = ${network} ; uploading = ${hasPendingWork})")
    setImageLevel(level)
    if (UPLOADING == level) animation.start() else {
      animation.cancel()
      alpha = 1.0f
    }
    isClickable = (DIRTY == level)
  }
}
