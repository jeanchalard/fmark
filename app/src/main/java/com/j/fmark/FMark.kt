package com.j.fmark

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.work.Configuration
import com.google.android.gms.auth.api.Auth
import com.j.fmark.fdrive.ClientFolder
import com.j.fmark.fdrive.FDrive
import com.j.fmark.fdrive.FMarkRoot
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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("FMark", s, e) }

// Looks unused, but WorkManager likes to use reflection to figure out this stuff
class FMarkApp : Application(), Configuration.Provider {
  override fun getWorkManagerConfiguration() : Configuration = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.VERBOSE).build()
}

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
  lateinit var cloudButton : CloudButton
  // Yeah Android fragment lifecycle is still horrendous
  private val pendingFragmentTransactions = ConcurrentLinkedQueue<FragmentTransaction>()

  init {
    log("FMark activity created")
  }

  override fun onCreate(icicle : Bundle?) {
    super.onCreate(null)
    log("onCreate with icicle = ${icicle}")
    setContentView(R.layout.activity_fmark)
    findViewById<View>(R.id.list_fragment).clipToOutline = true // This should be set in XML but a bug in the resource parser makes it impossible
    insertSpinner = findViewById(R.id.insert_loading)
    topSpinner = findViewById(R.id.top_loading) ?: insertSpinner
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
    getNetworking(this) // Prime the singleton
  }

  private fun startSignIn() {
    log("Starting sign in...")
    insertSpinnerVisible = true
    MainScope().launch {
      try {
        log("Getting account...")
        val root = FDrive.Root(this@FMark)
        log("Account : ${root.account}")
        startClientList(root)
        // else, wait for sign in activity → onActivityResult
      } catch (e : SignInException) {
        log("SignInException", e)
        offlineError(e.message)
      }
    }
  }

  // Yeah, fragment lifecycle is "not a bug" in the framework... it's just terrible design
  private fun replaceFragment(f : Fragment) {
    log("replaceFragment")
    val transaction = supportFragmentManager.beginTransaction().replace(R.id.list_fragment, f)
    if (supportFragmentManager.isStateSaved)
      pendingFragmentTransactions.add(transaction)
    else
      transaction.commit()
  }

  override fun onPostResume() {
    super.onPostResume()
    log("onPostResume")
    while (!pendingFragmentTransactions.isEmpty())
      pendingFragmentTransactions.poll()?.commit()
  }

  override fun onSaveInstanceState(outState : Bundle, outPersistentState : PersistableBundle) {
    super.onSaveInstanceState(outState, outPersistentState)
    log("onSaveInstanceState, outState = ${outState}, outPersistentState = ${outPersistentState}")
  }

  private suspend fun startClientList(root : FDrive.Root) {
    log("startClientList")
    val froot = RESTFMarkRoot(root)
    insertSpinnerVisible = false
    replaceFragment(ClientListFragment(this@FMark, froot))
  }

  fun offlineError(msgId : Int) = offlineError(resources.getString(msgId))
  private fun offlineError(msg : String?) {
    insertSpinnerVisible = false
    replaceFragment(SignInErrorFragment(msg, ::startSignIn))
  }

  override fun onBackPressed() {
    log("onBackPressed")
    when (val fragment = lastFragment) {
      is FEditor -> fragment.onBackPressed()
      else       -> super.onBackPressed()
    }
  }

  override fun onPrepareOptionsMenu(menu : Menu?) : Boolean {
    log("onPrepareOptionsMenu, menu = ${menu}")
    if (null == menu) return super.onPrepareOptionsMenu(menu)
    val isHome = when (lastFragment) {
      is ClientListFragment, is ClientDetails -> true
      is FEditor                              -> false
      else                                    -> true
    }
    menu.findItem(R.id.action_button_refresh).isVisible = isHome
    menu.findItem(R.id.action_button_clear).isVisible = !isHome
    menu.findItem(R.id.action_button_undo).isVisible = !isHome
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu : Menu) : Boolean {
    log("onCreateOptionsMenu")
    menuInflater.inflate(R.menu.menu_feditor, menu)
    cloudButton = menu.findItem(R.id.action_button_save).actionView as CloudButton
    cloudButton.host = this
    return super.onCreateOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item : MenuItem) : Boolean {
    log("onOptionsItemSelected : ${item}")
    return onOptionsItemSelected(item.itemId) ?: super.onOptionsItemSelected(item)
  }

  fun onOptionsItemSelected(itemId : Int) : Boolean? {
    when (val fragment = lastFragment) {
      is FEditor            -> return fragment.onOptionsItemSelected(itemId)
      is ClientListFragment -> fragment.refresh()
    }
    return null
  }

  override fun onActivityResult(requestCode : Int, resultCode : Int, data : Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    log("onActivityResult, requestCode = ${requestCode}, resultCode = ${resultCode}, data = ${data}")
    if (GOOGLE_SIGN_IN_CODE != requestCode) return // How did the control get here ?
    onSignIn(data)
  }

  private fun onSignIn(data : Intent?) {
    val signInResult = Auth.GoogleSignInApi.getSignInResultFromIntent(data)
    val account = signInResult?.signInAccount?.account
    log("onSignIn ${data}, signInResult = ${signInResult}, account = ${account}")
    if (null == account || !signInResult.isSuccess) {
      findViewById<View>(R.id.insert_loading).visibility = View.GONE
      offlineError(R.string.sign_in_fail_eventual)
    } else
      GlobalScope.launch(Dispatchers.Main) { startClientList(LiveCache.getRoot { FDrive.Root(this@FMark, account) }) }
  }

  fun showClientDetails(clientFolder : ClientFolder?, root : FMarkRoot) {
    log("Show client details ${clientFolder?.name}")
    val f = ClientDetails(this, clientFolder, root)
    val transaction = supportFragmentManager.beginTransaction()
     .addToBackStack(null)
    f.show(transaction, "details")
  }

  fun startClientEditor(clientFolder : ClientFolder) : Int {
    log("Starting client editor ${clientFolder.name}")
    return supportFragmentManager.beginTransaction()
     .addToBackStack("client")
     .replace(R.id.list_fragment, ClientHistory(this, clientFolder), "client")
     .commit()
  }

  fun startSessionEditor(sessionFolder : SessionFolder, sessionData : SessionData? = null) {
    log("Starting session editor ${sessionFolder}")
    val fEditor = FEditor(this, sessionFolder, sessionData)
    if (supportFragmentManager.findFragmentByTag("editor") != null) {
      supportFragmentManager.popBackStack("editor", FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }
    supportFragmentManager.beginTransaction().apply {
      addToBackStack("editor")
      replace(R.id.top_fragment, fEditor, "editor")
    }.commit()
  }

  // TODO : remove this function and have listeners on the ClientFolder object
  suspend fun renameClient(clientFolder : ClientFolder, name : String, reading : String, comment : String) {
    log("Rename client ${clientFolder.name} → ${name} - ${reading} (${comment})")
    clientFolder.rename(name, reading, comment)
    withContext(Dispatchers.Main) {
      supportFragmentManager.fragments.forEach {
        if (it is ClientListFragment) it.notifyRenamed(clientFolder)
      }
    }
  }
}
