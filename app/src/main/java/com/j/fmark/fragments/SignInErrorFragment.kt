package com.j.fmark.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.j.fmark.R

class SignInErrorFragment(private val message : String?, private val handler : Function0<Unit>) : Fragment() {
  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View {
    val view = inflater.inflate(R.layout.fragment_sign_in_error, container, false)
    if (null != message) view.findViewById<TextView>(R.id.error_sign_in_message).text = message
    view.findViewById<Button>(R.id.error_sign_in_retry).setOnClickListener { handler() }
    return view
  }
}
