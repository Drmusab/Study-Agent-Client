package com.studyagent.client.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Android connectivity awareness - distinguishes No network vs Agent unreachable
 */
class NetworkMonitor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob())
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isNetworkAvailable = MutableStateFlow(isCurrentlyAvailable())
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    private val _networkType = MutableStateFlow(detectNetworkType())
    val networkType: StateFlow<NetworkType> = _networkType.asStateFlow()

    enum class NetworkType {
        WIFI, CELLULAR, VPN, ETHERNET, UNKNOWN, NONE
    }

    init {
        observeNetworkChanges()
    }

    private fun isCurrentlyAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun detectNetworkType(): NetworkType {
        val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.UNKNOWN
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.UNKNOWN
        }
    }

    private fun observeNetworkChanges() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    _isNetworkAvailable.value = true
                    _networkType.value = detectNetworkType()
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    _isNetworkAvailable.value = isCurrentlyAvailable()
                    _networkType.value = detectNetworkType()
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                scope.launch {
                    _isNetworkAvailable.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    _networkType.value = detectNetworkType()
                }
            }
        }

        connectivityManager.registerNetworkCallback(request, callback)
    }

    fun networkFlow(): Flow<Boolean> = callbackFlow {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(isCurrentlyAvailable()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }

        connectivityManager.registerNetworkCallback(request, callback)
        trySend(isCurrentlyAvailable())

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
}

/** JVM fallback for tests - no Android */
class FakeNetworkMonitor(
    initiallyAvailable: Boolean = true
) {
    private val _isNetworkAvailable = MutableStateFlow(initiallyAvailable)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    fun setAvailable(available: Boolean) {
        _isNetworkAvailable.value = available
    }
}
