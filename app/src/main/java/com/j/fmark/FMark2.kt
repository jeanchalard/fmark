package com.j.fmark

import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.j.fmark.drive.FDrive
import com.j.fmark.fragments.ClientDetails
import com.j.fmark.fragments.ClientHistory
import com.j.fmark.fragments.ClientList
import com.j.fmark.fragments.ClientList2
import com.j.fmark.fragments.FEditor
import com.j.fmark.fragments.SignInErrorFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.Locale

class FMark2 : AppCompatActivity()
{
  private val lastFragment : Fragment?
    get() = supportFragmentManager.fragments.lastOrNull()
  private lateinit var topSpinner : View
  private lateinit var insertSpinner : View
  var topSpinnerVisible : Boolean
    get() = topSpinner.visibility == View.VISIBLE
    set(v) { topSpinner.visibility = if (v) View.VISIBLE else View.GONE }
  var insertSpinnerVisible : Boolean
    get() = insertSpinner.visibility == View.VISIBLE
    set(v) { insertSpinner.visibility = if (v) View.VISIBLE else View.GONE }
  lateinit var saveIndicator : SaveIndicator

  override fun onCreate(icicle : Bundle?)
  {
    super.onCreate(null)
    setContentView(R.layout.activity_fmark)
    findViewById<View>(R.id.list_fragment).clipToOutline = true // This should be set in XML but a bug in the resource parser makes it impossible
    insertSpinner = findViewById(R.id.insert_loading)
    topSpinner = findViewById(R.id.top_loading) ?: insertSpinner
    saveIndicator = createSaveIndicator(findViewById<FrameLayout>(R.id.action_bar_container))
    supportFragmentManager.addOnBackStackChangedListener {
      val actionBar = supportActionBar ?: return@addOnBackStackChangedListener
      val fragment = lastFragment
      Log.e("frag", "${fragment?.javaClass}")
      when (fragment) {
        is ClientList    -> actionBar.setTitle(R.string.titlebar_main)
        is ClientDetails -> actionBar.setTitle(R.string.titlebar_client_details)
        is ClientHistory -> { fragment.onResume(); actionBar.title = String.format(Locale.getDefault(), getString(R.string.titlebar_editor), fragment.name) }
      }
      invalidateOptionsMenu()
    }
    startSignIn()
  }

  private fun createSaveIndicator(parent : ViewGroup) : SaveIndicator
  {
    val indicator = layoutInflater.inflate(R.layout.save_indicator, parent, false) as SaveIndicator
    parent.addView(indicator)
    return indicator
  }

  private fun startSignIn()
  {
    insertSpinnerVisible = true
    GlobalScope.launch(Dispatchers.Main) {
      val account = FDrive.getAccount(this@FMark, GOOGLE_SIGN_IN_CODE)
      if (null != account) startClientList(account)
      // else, wait for sign in activity → onActivityResult
    }
  }

  private fun startClientList(account : GoogleSignInAccount)
  {
    val credential = GoogleAccountCredential.usingOAuth2(this, arrayListOf(DriveScopes.DRIVE_FILE))
    credential.selectedAccount = account.account
    val drive = Drive.Builder(NetHttpTransport(), GsonFactory(), credential)
     .setApplicationName(getString(R.string.gservices_app_name))
     .build()
    findViewById<View>(R.id.insert_loading).visibility = View.GONE
    supportFragmentManager.beginTransaction().replace(R.id.list_fragment, ClientList2(this@FMark, drive)).commit()
  }

  fun offlineError(msgId : Int) = offlineError(resources.getString(msgId))
  private fun offlineError(msg : String?)
  {
    supportFragmentManager.beginTransaction().replace(R.id.list_fragment, SignInErrorFragment(msg, ::startSignIn)).commit()
  }

  override fun onBackPressed()
  {
    val fragment = lastFragment
    when (fragment) {
      is FEditor -> fragment.onBackPressed()
      else -> super.onBackPressed()
    }
  }

  override fun onPrepareOptionsMenu(menu : Menu?) : Boolean
  {
    if (null == menu) return super.onPrepareOptionsMenu(menu)
    val isHome = when (lastFragment) {
      is ClientList, is ClientDetails -> true
      is FEditor -> false
      else -> true
    }
    menu.findItem(R.id.action_button_refresh).isVisible = isHome
    menu.findItem(R.id.action_button_clear).isVisible = !isHome
    menu.findItem(R.id.action_button_undo).isVisible = !isHome
    menu.findItem(R.id.action_button_save).isVisible = !isHome
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu : Menu) : Boolean
  {
    menuInflater.inflate(R.menu.menu_feditor, menu)
    return super.onCreateOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item : MenuItem?) : Boolean
  {
    val fragment = lastFragment
    when (fragment) {
      is FEditor -> return fragment.onOptionsItemSelected(item)
      is ClientList -> fragment.refresh()
    }
    return super.onOptionsItemSelected(item)
  }

  override fun onActivityResult(requestCode : Int, resultCode : Int, data : Intent?)
  {
    super.onActivityResult(requestCode, resultCode, data)
    if (GOOGLE_SIGN_IN_CODE != requestCode) return // How did the control get here ?
    onSignIn(data)
  }

  private fun onSignIn(data : Intent?)
  {
    val signInResult = Auth.GoogleSignInApi.getSignInResultFromIntent(data)
    val account = signInResult.signInAccount
    if (!signInResult.isSuccess || null == account)
    {
      findViewById<View>(R.id.insert_loading).visibility = View.GONE
      offlineError(R.string.sign_in_fail_eventual)
    }
    else
      startClientList(account)
  }
}