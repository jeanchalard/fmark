package com.j.fmark.fragments

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.j.fmark.FMark
import com.j.fmark.LOGEVERYTHING
import com.j.fmark.R
import com.j.fmark.SessionData
import com.j.fmark.fdrive.SessionFolder
import com.j.fmark.formatTime
import com.j.fmark.logAlways

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("ConflictingDataDialog", s, e) }

class ConflictingDataDialog(private val fEditor : FEditor.Control, private val sessionFolder : SessionFolder, private val modifiedTime : Long, private val hasChanges : Boolean, private val newSession : SessionData) : DialogFragment() {
  private var shouldReload = true

  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View {
    log("onCreateView dialog = ${dialog} ; icicle = ${savedInstanceState}")
    val view = inflater.inflate(R.layout.fragment_conflicting_data_dialog, container, false)
    view.findViewById<TextView>(R.id.conflicting_data_dialog_message).text = getString(R.string.conflicting_data_dialog_message, formatTime(modifiedTime))
    view.findViewById<Button>(R.id.conflicting_data_dialog_reload).apply {
      if (hasChanges) visibility = View.GONE
      setOnClickListener { reload() }
    }
    view.findViewById<Button>(R.id.conflicting_data_dialog_reload_with_changes).apply {
      if (!hasChanges) visibility = View.GONE
      setOnClickListener { reload() }
    }
    view.findViewById<Button>(R.id.conflicting_data_dialog_ignore).apply {
      if (!hasChanges) visibility = View.GONE
      setOnClickListener { ignore() }
    }
    dialog!!.setCanceledOnTouchOutside(false)
    return view
  }

  private fun reload() = dismiss()
  private fun ignore() {
    shouldReload = false
    dismiss()
  }

  override fun onDismiss(dialog : DialogInterface) {
    if (shouldReload)
      fEditor.fmarkHost.startSessionEditor(sessionFolder, newSession)
    else
      fEditor.setNotOutdated()
  }
}
