package com.j.fmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.ConnectivityManager.CONNECTIVITY_ACTION
import android.net.ConnectivityManager.EXTRA_NETWORK_INFO
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkInfo
import android.os.Build
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import com.j.fmark.fdrive.CommandStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

const val PROBABLY_FRESH_DELAY_MS = 86_400_000L
const val WAIT_FOR_NETWORK = 500L

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
  fun removeListener(l : (Network?) -> Unit) = synchronized(listeners) {
    listeners.remove(l)
  }

  suspend fun waitForNetwork() : Network {
    val deferred : CompletableDeferred<Network>
    synchronized(listeners) {
      val net = network
      if (null != net) return net
      deferred = CompletableDeferred()
      val listener = object : (Network?) -> Unit {
        override fun invoke(n : Network?) {
          if (null == n) return
          listeners.remove(this)
          deferred.complete(n)
        }
      }
      addListener(listener)
    }
    val network = deferred.await()
    CommandStatus.suspendUntilQueueIdle()
    return network
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
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val filter = IntentFilter(CONNECTIVITY_ACTION)
    log("Register CONNECTIVITY_ACTION listener")
    context.registerReceiver(object : BroadcastReceiver() {
      override fun onReceive(context : Context?, intent : Intent?) {
        log("Received CONNECTIVITY_ACTION ${intent}")
        if (null == intent) return
        val info = intent.getParcelableExtra<NetworkInfo>(EXTRA_NETWORK_INFO)
        log("Default network ${info}")
        cm.allNetworks.forEach {
          val iinfo = cm.getNetworkInfo(it)
          log("Available network ${it} ${iinfo}")
          if (iinfo.type == info.type) { // NetworkInfo#equals doesn't even work >.>
            log("It's the default")
            network = it
            return
          }
        }
      }
    }, filter)
  }
}

fun <T> load(context : Context,
 fromDrive : (suspend () -> T)?,
 fromCache : suspend () -> T,
 isCacheFresh : suspend () -> Boolean? // Boolean.TRUE if the cache is fresh, Boolean.FALSE if the cache is not fresh, null if the cache is absent
) : Flow<T> {
  suspend fun FlowCollector<T>.loadFromNetworkAnyway(networking : Networking, compare : T?) {
    if (null == fromDrive) return
    log("loadFromNetworkAnyway")
    val start = now()
    networking.waitForNetwork()
    val networkAvailable = now()
    log("Waited ${networkAvailable - start}ms for network")
    CommandStatus.suspendUntilQueueIdle()
    log("Waited ${now() - networkAvailable}ms for queue to become idle")
    val netData = fromDrive().also { log("Retrieved data from Drive in ${now() - start}ms including waiting time") }
    if (netData != compare) {
      log("Fetched different data from the network")
      this.emit(netData)
    } else log("Data loaded from the network was identical")
  }

  log("load...")
  return flow<T> {
    if (null == fromDrive)
      emit(fromCache())
    else {
      val networking = getNetworking(context)
      val isCacheFresh = isCacheFresh()
      if (null == isCacheFresh) {
        log("Cache absent, waiting for network")
        loadFromNetworkAnyway(networking, null)
        // TODO : timeout
      } else {
        if (null == networking.network) {
          log("No network, returning cache")
          val data = fromCache()
          emit(data)
          loadFromNetworkAnyway(networking, data)
        } else {
          if (isCacheFresh) {
            // TODO : Load from network and register to listen
            log("Cache fresh, reading from cache")
            val data = fromCache()
            emit(data)
            loadFromNetworkAnyway(networking, data)
          } else {
            log("Data old : trying to fetch from network with ${WAIT_FOR_NETWORK}ms grace")
            val dataFromDrive = GlobalScope.async { fromDrive() }
            val start = now()
            // Run to completion even if time out
            val obtained = withTimeoutOrNull(WAIT_FOR_NETWORK) { dataFromDrive.await() }
            if (null != obtained) {
              log("Read data from Drive in ${now() - start}ms")
              emit(obtained)
            } else {
              log("Network timeout, returning from cache")
              val data = fromCache()
              emit(data)
              val netData = dataFromDrive.await()
              if (netData != data) emit(netData)
            }
          }
        }
      }
    }
  }.flowOn(Dispatchers.IO)
   .conflate()
}
