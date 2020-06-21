package com.j.fmark

import android.app.Application
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
import android.os.ConditionVariable
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList

fun getNetworking(context : Context) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Networking24(context) else NetworkingOld(context)

open class Networking {
  @GuardedBy("listeners")
  @Volatile public var network : Network? = null
    get() = synchronized(listeners) { field }
    protected set(n : Network?) {
      synchronized(listeners) {
        field = n
        if (null == n) return
        listeners.forEach { it.complete(n) }
        listeners.clear()
      }
    }

  @GuardedBy("listeners")
  private val listeners = CopyOnWriteArrayList<CompletableDeferred<Network>>()

  suspend fun wait() : Network {
    val deferred : CompletableDeferred<Network>
    synchronized(listeners) {
      val net = network
      if (null != net) return net
      deferred = CompletableDeferred<Network>()
      listeners.add(deferred)
    }
    return deferred.await()
  }
}

@RequiresApi(Build.VERSION_CODES.N)
class Networking24(context : Context) : Networking() {
  init {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    cm.registerDefaultNetworkCallback(object : NetworkCallback() {
      override fun onAvailable(n : Network?) { network = n }
      override fun onLost(n : Network?) { network = null }
    })
  }
}

class NetworkingOld(context : Context) : Networking() {
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
