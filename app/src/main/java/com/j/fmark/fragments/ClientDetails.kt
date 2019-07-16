package com.j.fmark.fragments

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.appcompat.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.j.fmark.FMark
import com.j.fmark.R
import com.j.fmark.fdrive.ClientFolder
import com.j.fmark.fdrive.FMarkRoot
import com.j.fmark.formatDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClientDetails(private val fmarkHost : FMark, private val clientFolder : ClientFolder?, private val root : FMarkRoot) : DialogFragment()
{
  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View?
  {
    val view = inflater.inflate(R.layout.fragment_client_details, container, false)
    view.findViewById<Button>(R.id.client_details_ok).setOnClickListener { onFinish(true) }
    view.findViewById<Button>(R.id.client_details_cancel).setOnClickListener { onFinish(false) }
    if (null != clientFolder)
    {
      view.findViewById<EditText>(R.id.client_details_name)?.setText(clientFolder.name)
      view.findViewById<EditText>(R.id.client_details_reading)?.setText(clientFolder.reading)
      view.findViewById<TextView>(R.id.client_details_creation_date_value)?.text = formatDate(clientFolder.modifiedDate)
      view.findViewById<TextView>(R.id.client_details_last_update_date_value)?.text = formatDate(clientFolder.createdDate)
    }
    else
    {
      val now = System.currentTimeMillis()
      view.findViewById<TextView>(R.id.client_details_creation_date_value)?.text = formatDate(now)
      view.findViewById<TextView>(R.id.client_details_last_update_date_value)?.text = formatDate(now)
    }
    dialog?.window?.setBackgroundDrawableResource(R.drawable.rounded_square)
    return view
  }

  private fun onFinish(ok : Boolean)
  {
    if (!ok) { fmarkHost.supportFragmentManager.popBackStack(); return }
    val name = view?.findViewById<EditText>(R.id.client_details_name)?.text?.toString()
    val reading = view?.findViewById<EditText>(R.id.client_details_reading)?.text?.toString()
    if (null == name || null == reading) throw NullPointerException("Neither name or reading can be null when validating the dialog")
    GlobalScope.launch {
      root.clientList(name).count.let { count ->
        if (count == 0)
          validateDetails(clientFolder, name, reading)
        else
        {
          val message = resources.getQuantityString(R.plurals.client_already_exists, count, count)
          withContext(Dispatchers.Main) {
            AlertDialog.Builder(fmarkHost)
             .setMessage(message)
             .setPositiveButton(android.R.string.ok) { _, _ -> GlobalScope.launch(Dispatchers.Main) { validateDetails(clientFolder, name, reading) } }
             .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
             .show()
          }
        }
      }
    }
  }

  private suspend fun validateDetails(folder : ClientFolder?, name : String, reading : String)
  {
    fmarkHost.supportFragmentManager.popBackStack()
    if (null == folder)
      withContext(Dispatchers.Main) { fmarkHost.startSessionEditor(root.createClient(name, reading).newSession()) } // It's a new client.
    else
      fmarkHost.renameClient(folder, name, reading)
  }

  override fun onDetach()
  {
    super.onDetach()
    // This is atrocious. Why do I need to say "post" for this to work when a button is pressed (as opposed to dismissing by clicking outside the dialog ?)
    // I've spent too much valuable time on this. This solution sucks but it works.
    fmarkHost.window.decorView.post { (fmarkHost.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(fmarkHost.window.decorView.windowToken, 0) }
  }
}
