package com.j.fmark.fragments

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
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClientDetails(private val fmarkHost : FMark, private val driveResourceClient : DriveResourceClient, private val clientFolder : Metadata?) : DialogFragment()
{
  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View?
  {
    val view = inflater.inflate(R.layout.fragment_client_details, container, false)
    view.findViewById<Button>(R.id.client_details_ok).setOnClickListener { _ -> onFinish(true) }
    view.findViewById<Button>(R.id.client_details_cancel).setOnClickListener { _ -> onFinish(false) }
    if (null != clientFolder)
    {
      view.findViewById<EditText>(R.id.client_details_name)?.setText(decodeName(clientFolder))
      view.findViewById<EditText>(R.id.client_details_reading)?.setText(decodeReading(clientFolder))
    }
    view.findViewById<TextView>(R.id.client_details_creation_date_value)?.setText(formatDate(clientFolder?.modifiedDate))
    view.findViewById<TextView>(R.id.client_details_last_update_date_value)?.setText(formatDate(clientFolder?.createdDate))
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
      val fmarkFolder = getFMarkFolder(driveResourceClient, fmarkHost)
      val existingFolders = getFoldersForClientName(driveResourceClient, fmarkFolder, name, reading)
      if (existingFolders.count == 0)
        validateDetails(fmarkFolder, name, reading)
      else
      {
        val message = String.format(Locale.getDefault(), fmarkHost.getString(R.string.client_already_exists), existingFolders.count)
        AlertDialog.Builder(fmarkHost)
         .setMessage(message)
         .setPositiveButton(R.string.ok) { _, _ -> GlobalScope.launch(Dispatchers.Main) { validateDetails(fmarkFolder, name, reading) } }
         .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
         .show()
      }
    }
  }

  private suspend fun validateDetails(fmarkFolder : DriveFolder, name : String, reading : String)
  {
    fmarkHost.supportFragmentManager.popBackStack()
    if (null == clientFolder)
      fmarkHost.startEditor(driveResourceClient, createFolderForClientName(driveResourceClient, fmarkFolder, name, reading)) // It's a new client.
    else
      fmarkHost.renameClient(driveResourceClient, clientFolder, name, reading)
  }

  override fun onDetach()
  {
    super.onDetach()
    // This is atrocious. Why do I need to say "post" for this to work when a button is pressed (as opposed to dismissing by clicking outside the dialog ?)
    // I've spent too much valuable time on this. This solution sucks but it works.
    fmarkHost.window.decorView.post { fmarkHost.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(fmarkHost.window.decorView.windowToken, 0) }
  }

  private fun formatDate(date : Date?) : String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date ?: Date())
}
