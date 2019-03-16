package com.j.fmark.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.support.v7.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.google.android.gms.drive.DriveClient
import com.google.android.gms.drive.DriveFolder
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.j.fmark.FMark
import com.j.fmark.R
import com.j.fmark.drive.FDrive.getFMarkFolder
import com.j.fmark.drive.createFolderForClientName
import com.j.fmark.drive.decodeName
import com.j.fmark.drive.decodeReading
import com.j.fmark.drive.getFoldersForClientName
import com.j.fmark.formatDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class ClientDetails(private val fmarkHost : FMark, private val resourceClient : DriveResourceClient, private val refreshClient : DriveClient, private val clientFolder : Metadata?) : DialogFragment()
{
  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View?
  {
    val view = inflater.inflate(R.layout.fragment_client_details, container, false)
    view.findViewById<Button>(R.id.client_details_ok).setOnClickListener { onFinish(true) }
    view.findViewById<Button>(R.id.client_details_cancel).setOnClickListener { onFinish(false) }
    if (null != clientFolder)
    {
      view.findViewById<EditText>(R.id.client_details_name)?.setText(decodeName(clientFolder))
      view.findViewById<EditText>(R.id.client_details_reading)?.setText(decodeReading(clientFolder))
    }
    view.findViewById<TextView>(R.id.client_details_creation_date_value)?.text = formatDate(clientFolder?.modifiedDate)
    view.findViewById<TextView>(R.id.client_details_last_update_date_value)?.text = formatDate(clientFolder?.createdDate)
    dialog.window.setBackgroundDrawableResource(R.drawable.rounded_square)
    return view
  }

  private fun onFinish(ok : Boolean)
  {
    if (!ok) { fmarkHost.supportFragmentManager.popBackStack(); return }
    val name = view?.findViewById<EditText>(R.id.client_details_name)?.text?.toString()
    val reading = view?.findViewById<EditText>(R.id.client_details_reading)?.text?.toString()
    if (null == name || null == reading) throw NullPointerException("Neither name or reading can be null when validating the dialog")
    GlobalScope.launch(Dispatchers.Main) {
      val fmarkFolder = getFMarkFolder(resourceClient, fmarkHost)
      val existingFolders = getFoldersForClientName(resourceClient, fmarkFolder, name)
      if (existingFolders.count == 0)
        validateDetails(fmarkFolder, name, reading)
      else
      {
        val count = existingFolders.count
        val message = resources.getQuantityString(R.plurals.client_already_exists, count, count)
        AlertDialog.Builder(fmarkHost)
         .setMessage(message)
         .setPositiveButton(android.R.string.ok) { _, _ -> GlobalScope.launch(Dispatchers.Main) { validateDetails(fmarkFolder, name, reading) } }
         .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
         .show()
      }
      existingFolders.release()
    }
  }

  private suspend fun validateDetails(fmarkFolder : DriveFolder, name : String, reading : String)
  {
    fmarkHost.supportFragmentManager.popBackStack()
    if (null == clientFolder)
      fmarkHost.startSessionEditor(resourceClient, refreshClient, createFolderForClientName(resourceClient, fmarkFolder, name, reading)) // It's a new client.
    else
      fmarkHost.renameClient(resourceClient, clientFolder, name, reading)
  }

  override fun onDetach()
  {
    super.onDetach()
    // This is atrocious. Why do I need to say "post" for this to work when a button is pressed (as opposed to dismissing by clicking outside the dialog ?)
    // I've spent too much valuable time on this. This solution sucks but it works.
    fmarkHost.window.decorView.post { (fmarkHost.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(fmarkHost.window.decorView.windowToken, 0) }
  }
}
