package com.j.fmark.fragments

import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import com.google.android.gms.drive.DriveFolder
import com.j.fmark.FMark
import com.j.fmark.R

class ClientDetails(private val fmarkHost : FMark, private val clientFolder : DriveFolder?) : DialogFragment()
{
  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View?
  {
    val view = inflater.inflate(R.layout.fragment_client_details, container, false)
    view.findViewById<Button>(R.id.client_details_ok).setOnClickListener { v -> onFinish(v, true) }
    view.findViewById<Button>(R.id.client_details_cancel).setOnClickListener { v -> onFinish(v, false) }
    dialog.window.setBackgroundDrawableResource(R.drawable.rounded_square)
    return view
  }

  fun onFinish(v : View, ok : Boolean)
  {
    fmarkHost.supportFragmentManager.popBackStack()
  }

  override fun onDetach()
  {
    super.onDetach()
    // This is atrocious. Why do I need to say "post" for this to work when a button is pressed (as opposed to dismissing by clicking outside the dialog ?)
    // I've spent too much valuable time on this. This solution sucks but it works.
    fmarkHost.window.decorView.post { fmarkHost.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(fmarkHost.window.decorView.windowToken, 0) }
  }
}
