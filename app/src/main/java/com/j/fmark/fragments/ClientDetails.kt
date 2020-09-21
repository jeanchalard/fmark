package com.j.fmark.fragments

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.j.fmark.FMark
import com.j.fmark.LOGEVERYTHING
import com.j.fmark.R
import com.j.fmark.fdrive.ClientFolder
import com.j.fmark.fdrive.FMarkRoot
import com.j.fmark.formatDate
import com.j.fmark.logAlways
import com.j.fmark.now
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("ClientDetails", s, e) }

class ClientDetails(private val fmarkHost : FMark, private val clientFolder : ClientFolder?, private val root : FMarkRoot) : DialogFragment() {
  // - is allowed in a file name but I'm using it as a separator. It's a bit shitty but that's simplest
  // TODO : remove this limitation. Drive doesn't have it, and it can be removed by storing the metadata in a file in the directory instead of in the file name
  private val filenameForbiddenCharacters = arrayOf('?', ':', '\"', '*', '|', '/', '\\', '<', '>', '-')

  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View? {
    log("onCreateView ${savedInstanceState}")
    val view = inflater.inflate(R.layout.fragment_client_details, container, false)
    val inputName = view.findViewById<EditText>(R.id.client_details_name)
    val inputReading = view.findViewById<EditText>(R.id.client_details_reading)
    val inputComment = view.findViewById<EditText>(R.id.client_details_comment)

    view.findViewById<Button>(R.id.client_details_ok).setOnClickListener { onFinish(true) }
    view.findViewById<Button>(R.id.client_details_cancel).setOnClickListener { onFinish(false) }
    log("Client folder ${clientFolder}")
    if (null != clientFolder) {
      inputName.setText(clientFolder.name)
      inputReading.setText(clientFolder.reading)
      view.findViewById<TextView>(R.id.client_details_creation_date_value)?.text = formatDate(clientFolder.createdDate)
      view.findViewById<TextView>(R.id.client_details_last_update_date_value)?.text = formatDate(clientFolder.modifiedDate)
    } else {
      val now = now()
      view.findViewById<TextView>(R.id.client_details_creation_date_value)?.text = formatDate(now)
      view.findViewById<TextView>(R.id.client_details_last_update_date_value)?.text = formatDate(now)
    }
    dialog?.window?.setBackgroundDrawableResource(R.drawable.rounded_square)
    val listener = object : TextWatcher {
      override fun beforeTextChanged(s : CharSequence?, start : Int, count : Int, after : Int) = Unit
      override fun onTextChanged(s : CharSequence?, start : Int, before : Int, count : Int) {}
      override fun afterTextChanged(s : Editable?) {
        view.findViewById<TextView>(R.id.client_details_character_error).visibility =
         if (filenameForbiddenCharacters.any { c -> inputName.text.contains(c) || inputReading.text.contains(c) || inputComment.text.contains(c) })
           View.VISIBLE
         else
           View.INVISIBLE
      }
    }
    inputName.addTextChangedListener(listener)
    inputReading.addTextChangedListener(listener)
    inputComment.addTextChangedListener(listener)
    log("onCreateView end")
    return view
  }

  private fun onFinish(ok : Boolean) {
    log("onFinish ok = ${ok}")
    if (!ok) { fmarkHost.supportFragmentManager.popBackStack(); return }
    val name = view?.findViewById<EditText>(R.id.client_details_name)?.text?.toString()
    val reading = view?.findViewById<EditText>(R.id.client_details_reading)?.text?.toString()
    val comment = view?.findViewById<EditText>(R.id.client_details_comment)?.text?.toString()
    log("Finishing with ${name} -- ${reading} (${comment})")
    if (null == name || null == reading || null == comment) throw NullPointerException("Name, reading and comment can't be null when validating the dialog")
    MainScope().launch {
      root.clientList(searchString = name, exactMatch = true).count.let { count ->
        log("Existing client count named ${name} : ${count}")
        if (count == 0)
          validateDetails(clientFolder, name, reading, comment)
        else {
          val message = resources.getQuantityString(R.plurals.client_already_exists, count, count)
          withContext(Dispatchers.Main) {
            AlertDialog.Builder(fmarkHost)
             .setMessage(message)
             .setPositiveButton(android.R.string.ok) { _, _ -> GlobalScope.launch(Dispatchers.Main) { validateDetails(clientFolder, name, reading, comment) } }
             .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
             .show()
          }
        }
      }
    }
  }

  private suspend fun validateDetails(folder : ClientFolder?, name : String, reading : String, comment : String) {
    fmarkHost.supportFragmentManager.popBackStack()
    log("Validating ${name}, folder = ${folder}")
    if (null == folder)
      withContext(Dispatchers.Main) { fmarkHost.startSessionEditor(root.createClient(name, reading, comment).newSession()) } // It's a new client.
    else
      fmarkHost.renameClient(folder, name, reading, comment)
  }

  override fun onDetach() {
    log("onDetach")
    super.onDetach()
    // This is atrocious. Why do I need to say "post" for this to work when a button is pressed (as opposed to dismissing by clicking outside the dialog ?)
    // I've spent too much valuable time on this. This solution sucks but it works.
    fmarkHost.window.decorView.post { (fmarkHost.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(fmarkHost.window.decorView.windowToken, 0) }
  }
}
