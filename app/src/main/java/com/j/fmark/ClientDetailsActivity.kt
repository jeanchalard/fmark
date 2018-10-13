package com.j.fmark

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

class ClientDetailsActivity : AppCompatActivity()
{
  override fun onCreate(icicle : Bundle?)
  {
    super.onCreate(icicle)
    setContentView(R.layout.activity_client_details)
  }

  fun onCancel(v : View)
  {
    getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
    setResult(Activity.RESULT_CANCELED)
    finish()
  }

  fun onOk(v : View)
  {
    val intent = Intent().apply {
      putExtra(EXTRA_KEY_NAME, findViewById<EditText>(R.id.client_details_name).text.toString())
      putExtra(EXTRA_KEY_READING, findViewById<EditText>(R.id.client_details_reading).text.toString())
    }
    setResult(RESULT_OK, intent)
  }
}
