package com.j.fmark.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.j.fmark.LOGEVERYTHING
import com.j.fmark.R
import com.j.fmark.logAlways

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("SignInErrorFragment", s, e) }

class SignInErrorFragment(private val message : String?, private val retryHandler : () -> Unit, private val continueAnywayHandler : () -> Unit) : Fragment() {
  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View {
    log("Starting sign in error fragment")
    val view = inflater.inflate(R.layout.fragment_sign_in_error, container, false)
    if (null != message) view.findViewById<TextView>(R.id.error_sign_in_message).text = message
    view.findViewById<Button>(R.id.error_sign_in_retry).setOnClickListener { retryHandler() }
    view.findViewById<Button>(R.id.error_sign_in_continue_without_drive).setOnClickListener { continueAnywayHandler() }
    return view
  }
}
