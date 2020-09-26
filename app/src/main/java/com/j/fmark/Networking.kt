package com.j.fmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.ConnectivityManager.CONNECTIVITY_ACTION
import android.net.ConnectivityManager.EXTRA_NETWORK
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.os.Build
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

val PROBABLY_FRESH_DELAY_MS = 86_400_000L
val WAIT_FOR_NETWORK = 500L

private const val DBG = false
@Suppress("NOTHING_TO_INLINE", "ConstantConditionIf") private inline fun log(s : String, e : java.lang.Exception? = null) { if (DBG || LOGEVERYTHING) logAlways("Networking", s, e) }

fun getNetworking(context : Context) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Networking24.get(context.applicationContext) else NetworkingOld.get(context.applicationContext)

open class Networking {
  @GuardedBy("listeners")
  @Volatile public var network : Network? = null
    get() = synchronized(listeners) { field }
    protected set(n) = synchronized(listeners) {
      field = n
      listeners.forEach { it(n) }
    }

  @GuardedBy("listeners")
  private val listeners = CopyOnWriteArrayList<(Network?) -> Unit>()

  // Careful, the listener is called locked. It's bad practice but this is a small app with very contained code, not a lib
  fun addListener(l : (Network?) -> Unit) = synchronized(listeners) {
    listeners.add(l)
    l(network)
  }

  suspend fun waitForNetwork() : Network {
    val deferred : CompletableDeferred<Network> = CompletableDeferred()
    val listener = object : (Network?) -> Unit {
      override fun invoke(n : Network?) {
        if (null == n) return
        listeners.remove(this)
        deferred.complete(n)
      }
    }
    synchronized(listeners) {
      val net = network
      if (null != net) return net
      addListener(listener)
    }
    return deferred.await()
  }
}

@RequiresApi(Build.VERSION_CODES.N)
class Networking24(context : Context) : Networking() {
  companion object {
    @Volatile private lateinit var INSTANCE : Networking24
    public fun get(context : Context) : Networking24 {
      if (this::INSTANCE.isInitialized) return INSTANCE
      synchronized(this) {
        if (this::INSTANCE.isInitialized) return INSTANCE
        INSTANCE = Networking24(context)
        return INSTANCE
      }
    }
  }

  init {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    cm.registerDefaultNetworkCallback(object : NetworkCallback() {
      override fun onAvailable(n : Network?) { log("Available ${n}"); network = n }
      override fun onLost(n : Network?) { log("Lost ${n}"); network = null }
    })
  }
}

class NetworkingOld(context : Context) : Networking() {
  companion object {
    @Volatile private lateinit var INSTANCE : NetworkingOld
    public fun get(context : Context) : NetworkingOld {
      if (this::INSTANCE.isInitialized) return INSTANCE
      synchronized(this) {
        if (this::INSTANCE.isInitialized) return INSTANCE
        INSTANCE = NetworkingOld(context)
        return INSTANCE
      }
    }
  }

  init {
    val filter = IntentFilter(CONNECTIVITY_ACTION)
    context.registerReceiver(object : BroadcastReceiver() {
      override fun onReceive(context : Context?, intent : Intent?) {
        if (null == intent) return
        network = intent.getParcelableExtra(EXTRA_NETWORK) as? Network
      }
    }, filter)
  }
}

suspend fun <T> fromCacheOrNetwork(context : Context,
 fromDrive : suspend () -> T,
 fromCache : suspend () -> T,
 isCacheFresh : suspend () -> Boolean? // Boolean.TRUE if the cache is fresh, Boolean.FALSE if the cache is not fresh, null if the cache is absent
) : T {
  log("fromCacheOrNetwork...")
  return withContext(Dispatchers.IO) {
    val networking = getNetworking(context)
    val isCacheFresh = isCacheFresh()
    if (null == isCacheFresh) {
      log("Cache absent, waiting for network")
      val start = now()
      // TODO : timeout
      networking.waitForNetwork()
      fromDrive().also { log("Retrieved data from Drive in ${now() - start}ms") }
    } else {
      if (null == networking.network) {
        log("No network, returning cache")
        fromCache()
      } else {
        if (isCacheFresh) {
          // TODO : Load from network and register to listen
          log("Cache fresh, reading from cache")
          fromCache()
        } else {
          log("Data old : trying to fetch from network with ${WAIT_FOR_NETWORK}ms grace")
          val dataFromDrive = async { fromDrive() }
          val start = now()
          val obtained = withTimeoutOrNull(WAIT_FOR_NETWORK) { dataFromDrive.await() }
          if (null != obtained) {
            log("Read data from Drive in ${now() - start}ms")
            obtained
          } else {
            log("Network timeout, returning from cache")
            // TODO : Register to listen on dataFromDrive
            fromCache()
          }
        }
      }
    }
  }
}
