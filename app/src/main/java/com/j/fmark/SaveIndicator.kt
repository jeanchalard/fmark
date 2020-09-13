package com.j.fmark

import android.content.Context
import android.graphics.drawable.Animatable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("SaveIndicator", s, e) }

class SaveIndicator @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = 0, defStyleRes : Int = 0) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {
  private lateinit var inProgress : ImageView
  private lateinit var error : ImageView
  private lateinit var ok : ImageView

  override fun onFinishInflate() {
    super.onFinishInflate()
    log("onFinishInflate")
    inProgress = findViewById(R.id.save_inprogress)
    error = findViewById(R.id.save_error)
    ok = findViewById(R.id.save_ok)
  }

  fun showInProgress() {
    log("Show InProgress")
    error.visibility = View.INVISIBLE
    ok.visibility = View.INVISIBLE
    inProgress.visibility = View.VISIBLE
    (inProgress.drawable as Animatable).start()
  }

  fun showError() {
    log("Show Error")
    error.visibility = View.VISIBLE
    ok.visibility = View.INVISIBLE
    inProgress.visibility = View.INVISIBLE
  }

  fun showOk() {
    log("Show Ok")
    error.visibility = View.INVISIBLE
    ok.visibility = View.VISIBLE
    inProgress.visibility = View.INVISIBLE
  }

  fun hideOk() {
    log("Hide Ok")
    ok.visibility = View.INVISIBLE
  }
}
