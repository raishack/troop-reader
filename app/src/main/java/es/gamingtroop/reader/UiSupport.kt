package es.gamingtroop.reader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

interface NetworkSource {
    fun available(): Boolean
    fun subscribe(changed: () -> Unit): () -> Unit
}
class AndroidNetworkSource(context: Context): NetworkSource {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    override fun available(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    // INTERNET means we can attempt Kavita. Android VALIDATED can remain false on VPNs or
    // when its connectivity-check endpoint is blocked; it must not permanently disable reading.
    override fun subscribe(changed: () -> Unit): () -> Unit {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = changed()
        }
        manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        return { manager.unregisterNetworkCallback(callback) }
    }
}
data class NetworkState(val available: Boolean, val generation: Int = 0)
val LocalNetworkSource = staticCompositionLocalOf<NetworkSource?> { null }
@Composable fun networkState(context: Context): NetworkState {
    val provided = LocalNetworkSource.current
    val source = remember(context, provided) { provided ?: AndroidNetworkSource(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember(source) { mutableStateOf(NetworkState(source.available())) }
    fun refresh(force: Boolean = false) {
        val available = source.available()
        if(force || available != state.available) state = NetworkState(available, state.generation + 1)
    }
    DisposableEffect(source, lifecycle) {
        var unsubscribe: (() -> Unit)? = null
        fun start() {
            if(unsubscribe == null) unsubscribe = source.subscribe { refresh() }
            refresh(true)
        }
        val observer = LifecycleEventObserver { _, event -> when(event) {
            Lifecycle.Event.ON_RESUME -> start()
            Lifecycle.Event.ON_STOP -> { unsubscribe?.invoke(); unsubscribe = null }
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose { lifecycle.removeObserver(observer); unsubscribe?.invoke() }
    }
    // Recover even if a vendor drops a callback during Wi-Fi/mobile handover.
    LaunchedEffect(source, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while(true) { refresh(); delay(5000) }
        }
    }
    return state
}
