package com.j.fmark

import android.accounts.Account
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AccelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.coroutineScope
import androidx.work.Configuration
import com.google.android.gms.auth.api.Auth
import com.google.android.gms.common.api.ApiException
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException

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
    set(v) = setSpinnerVisible(topSpinner, v)
  var insertSpinnerVisible : Boolean
    get() = insertSpinner.visibility == View.VISIBLE
    set(v) = setSpinnerVisible(insertSpinner, v)
  lateinit var cloudButton : CloudButton
  var undoButtonEnabled = true
    set(v) { field = v; invalidateOptionsMenu() }
  // Yeah Android fragment lifecycle is still horrendous
  private val pendingFragmentTransactions = ConcurrentLinkedQueue<FragmentTransaction>()

  init {
    log("FMark activity created")
    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
      log("exception ${throwable}")
      throwable.stackTrace.forEach { log(it.toString()) }
      throw throwable
    }
  }

  // A function to make a view appear over a short lapse of time. This is useful for spinners, first because having them appear smoothly
  // is nicer, and also because when they only last one or two frames it makes them a lot less glaring. This takes the alpha wherever it
  // used to stand.
  private val appearInterpolator = AccelerateInterpolator()
  private fun setSpinnerVisible(v : View, visible : Boolean) {
    log("Appear ${v} from alpha ${v.alpha}")
    if (visible) {
      v.visibility = View.VISIBLE
      v.animate().apply {
        interpolator = appearInterpolator
        duration = 500
        alpha(1f)
        start()
      }
    } else {
      v.animate().cancel()
      v.visibility = View.GONE
      v.alpha = 0f
    }
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
    startSignIn(showErrorPageOnFailure = true)
    getNetworking(this) // Prime the singleton
  }

  private fun startSignIn(showErrorPageOnFailure : Boolean) {
    log("Starting sign in...")
    insertSpinnerVisible = true
    MainScope().launch {
      try {
        log("Getting account...")
        val root = if (showErrorPageOnFailure) {
          val account = FDrive.fetchAccount(this@FMark, suspendForever = false)?.account ?: throw SignInException(getString(R.string.sign_in_fail_cant_get_account))
          FDrive.Root(this@FMark, CompletableDeferred(account))
        }
        else
          LiveCache.getRoot { FDrive.Root(this@FMark) }
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
  @Suppress("MoveLambdaOutsideParentheses") // expected here for readability of the call to SignInErrorFragment
  private fun offlineError(msg : String?) {
    insertSpinnerVisible = false
    replaceFragment(SignInErrorFragment(msg, { startSignIn(showErrorPageOnFailure = true) }, { startSignIn(false) }))
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
    menu.findItem(R.id.action_button_undo).isEnabled = undoButtonEnabled
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
      startClientList(account)
  }

  fun showClientDetails(clientFolder : ClientFolder?, root : FMarkRoot) {
    log("Show client details ${clientFolder?.name}")
    val f = ClientDetails(this, clientFolder, root)
    val transaction = supportFragmentManager.beginTransaction()
     .addToBackStack(null)
    f.show(transaction, "details")
  }

  private fun startClientList(account : Account) {
    lifecycle.coroutineScope.launch(Dispatchers.Main) {
      try {
        startClientList(LiveCache.getRoot { FDrive.Root(this@FMark, CompletableDeferred(account)) })
      } catch (e : Exception) {
        // ExecutionException and ApiException are expected when the client is not signed in. When this happens,
        // creating the Root will run the authentication flow, which when complete will call startClientList again.
        // Any other exception is unexpected, so rethrow it. Otherwise, simply exit and wait for the flow to complete.
        if (e !is ExecutionException && e !is ApiException) throw e
      }
    }
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

  suspend fun createOrRenameClient(root : FMarkRoot, clientFolder : ClientFolder?, name : String, reading : String, comment : String) {
    log("Create or rename client ${clientFolder?.name} → ${name} - ${reading} (${comment})")
    if (null == clientFolder) { // It's a new client.
      withContext(Dispatchers.Main) {
        topSpinnerVisible = true
        startSessionEditor(root.createClient(name, reading, comment).newSession(), newEmptySessionData())
      }
    } else {
      clientFolder.rename(name, reading, comment)
      // TODO : remove this part and have listeners on the ClientFolder object
      withContext(Dispatchers.Main) {
        supportFragmentManager.fragments.forEach {
          if (it is ClientListFragment) it.notifyRenamed(clientFolder)
        }
      }
    }
  }
}
