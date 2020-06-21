package com.j.fmark

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.j.fmark.fdrive.ClientFolder
import com.j.fmark.fdrive.FDrive
import com.j.fmark.fdrive.FMarkRoot
import com.j.fmark.fdrive.LocalDiskFMarkRoot
import com.j.fmark.fdrive.RESTFMarkRoot
import com.j.fmark.fdrive.SessionFolder
import com.j.fmark.fdrive.SignInException
import com.j.fmark.fragments.ClientDetails
import com.j.fmark.fragments.ClientHistory
import com.j.fmark.fragments.ClientListFragment
import com.j.fmark.fragments.FEditor
import com.j.fmark.fragments.SignInErrorFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class FMark : AppCompatActivity() {
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

  override fun onCreate(icicle : Bundle?) {
    super.onCreate(null)
    setContentView(R.layout.activity_fmark)
    findViewById<View>(R.id.list_fragment).clipToOutline = true // This should be set in XML but a bug in the resource parser makes it impossible
    insertSpinner = findViewById(R.id.insert_loading)
    topSpinner = findViewById(R.id.top_loading) ?: insertSpinner
    saveIndicator = createSaveIndicator(findViewById<FrameLayout>(R.id.action_bar_container))
    supportFragmentManager.addOnBackStackChangedListener {
      val actionBar = supportActionBar ?: return@addOnBackStackChangedListener
      when (val fragment = lastFragment) {
        is ClientListFragment -> actionBar.setTitle(R.string.titlebar_main)
        is ClientDetails      -> actionBar.setTitle(R.string.titlebar_client_details)
        is ClientHistory      -> { fragment.onResume(); actionBar.title = String.format(Locale.getDefault(), getString(R.string.titlebar_editor), fragment.name) }
      }
      invalidateOptionsMenu()
    }
    startSignIn()
  }

  private fun createSaveIndicator(parent : ViewGroup) : SaveIndicator {
    val indicator = layoutInflater.inflate(R.layout.save_indicator, parent, false) as SaveIndicator
    parent.addView(indicator)
    return indicator
  }

  private fun startSignIn() {
    if (DBGLOG) log("Starting sign in...")
    insertSpinnerVisible = true
    GlobalScope.launch(Dispatchers.Main) {
      try {
        if (DBGLOG) log("Getting account...")
        val account = FDrive.getAccount(this@FMark, GOOGLE_SIGN_IN_CODE)
        if (DBGLOG) log("Account : ${account}")
        if (null != account) startClientList(account)
        // else, wait for sign in activity → onActivityResult
      } catch (e : SignInException) {
        offlineError(e.message)
      }
    }
  }

  private suspend fun startClientList(account : GoogleSignInAccount) {
//    val root = LocalDiskFMarkRoot(this)
    val root = RESTFMarkRoot(this, account)
    insertSpinnerVisible = false
    supportFragmentManager.beginTransaction().replace(R.id.list_fragment, ClientListFragment(this@FMark, root)).commit()
  }

  fun offlineError(msgId : Int) = offlineError(resources.getString(msgId))
  private fun offlineError(msg : String?) {
    supportFragmentManager.beginTransaction().replace(R.id.list_fragment, SignInErrorFragment(msg, ::startSignIn)).commit()
  }

  override fun onBackPressed() {
    when (val fragment = lastFragment) {
      is FEditor -> fragment.onBackPressed()
      else       -> super.onBackPressed()
    }
  }

  override fun onPrepareOptionsMenu(menu : Menu?) : Boolean {
    if (null == menu) return super.onPrepareOptionsMenu(menu)
    val isHome = when (lastFragment) {
      is ClientListFragment, is ClientDetails -> true
      is FEditor                              -> false
      else                                    -> true
    }
    menu.findItem(R.id.action_button_refresh).isVisible = isHome
    menu.findItem(R.id.action_button_clear).isVisible = !isHome
    menu.findItem(R.id.action_button_undo).isVisible = !isHome
    menu.findItem(R.id.action_button_save).isVisible = !isHome
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu : Menu) : Boolean {
    menuInflater.inflate(R.menu.menu_feditor, menu)
    return super.onCreateOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item : MenuItem) : Boolean {
    val fragment = lastFragment
    when (fragment) {
      is FEditor            -> return fragment.onOptionsItemSelected(item)
      is ClientListFragment -> fragment.refresh()
    }
    return super.onOptionsItemSelected(item)
  }

  override fun onActivityResult(requestCode : Int, resultCode : Int, data : Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (GOOGLE_SIGN_IN_CODE != requestCode) return // How did the control get here ?
    onSignIn(data)
  }

  private fun onSignIn(data : Intent?) {
    val signInResult = Auth.GoogleSignInApi.getSignInResultFromIntent(data)
    val account = signInResult?.signInAccount
    if (null == account || !signInResult.isSuccess) {
      findViewById<View>(R.id.insert_loading).visibility = View.GONE
      offlineError(R.string.sign_in_fail_eventual)
    } else
      GlobalScope.launch(Dispatchers.Main) { startClientList(account) }
  }

  fun showClientDetails(clientFolder : ClientFolder?, root : FMarkRoot) {
    val f = ClientDetails(this, clientFolder, root)
    val transaction = supportFragmentManager.beginTransaction()
     .addToBackStack(null)
    f.show(transaction, "details")
  }

  fun startClientEditor(clientFolder : ClientFolder) =
   supportFragmentManager.beginTransaction()
    .addToBackStack("client")
    .replace(R.id.list_fragment, ClientHistory(this, clientFolder), "client")
    .commit()

  fun startSessionEditor(sessionFolder : SessionFolder) {
    val fEditor = FEditor(this, sessionFolder)
    supportFragmentManager.beginTransaction().apply {
      addToBackStack("editor")
      replace(R.id.top_fragment, fEditor, "editor")
    }.commit()
  }

  // TODO : remove this function and have listeners on the ClientFolder object
  suspend fun renameClient(clientFolder : ClientFolder, name : String, reading : String) {
    clientFolder.rename(name, reading)
    withContext(Dispatchers.Main) {
      supportFragmentManager.fragments.forEach {
        if (it is ClientListFragment) it.notifyRenamed(clientFolder)
      }
    }
  }
}
