package com.nuvio.app.features.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import okhttp3.OkHttpClient
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * The phone's home network (Wi-Fi or Ethernet, never a VPN), for talking to TVs directly. With a VPN on,
 * Android sends traffic through the VPN by default, which swallows TV searches and commands; tying
 * sockets to this network keeps them on the local network.
 */
internal class CastLocalNetwork private constructor(
    val network: Network,
    /** The phone's own IPv4 address on the home network, where the relay listens for the TV. */
    val ownAddress: InetAddress?,
    private val prefixLength: Int,
    /** False when the VPN refuses to let traffic bypass it; then the normal route is used. */
    val canBind: Boolean,
) {
    fun bind(socket: DatagramSocket) {
        if (canBind) network.bindSocket(socket)
    }

    fun httpClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .apply { if (canBind) socketFactory(network.socketFactory) }
        .build()

    /** Every other address on the home network, for asking each one directly when multicast doesn't get through. */
    fun neighbours(): List<InetAddress> {
        val own = ownAddress ?: return emptyList()
        // Typical home networks have up to 254 addresses (/24); skip anything too big to sweep quickly.
        if (prefixLength !in 22..30) return emptyList()
        val ownInt = ByteBuffer.wrap(own.address).int
        val mask = -1 shl (32 - prefixLength)
        val first = (ownInt and mask) + 1
        val last = (ownInt or mask.inv()) - 1
        return (first..last).filter { it != ownInt }
            .map { InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(it).array()) }
    }

    companion object {
        private const val TAG = "CastLocalNetwork"

        /** Null when the phone isn't on Wi-Fi or Ethernet. */
        @Suppress("DEPRECATION") // allNetworks is fine for a one-off lookup.
        fun find(context: Context): CastLocalNetwork? {
            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val network = connectivity.allNetworks.firstOrNull { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } ?: return null
            val link = connectivity.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }
            val canBind = runCatching { DatagramSocket().use { network.bindSocket(it) } }.isSuccess
            if (!canBind) Log.i(TAG, "Can't bind to Wi-Fi (VPN blocks it); using the default route")
            return CastLocalNetwork(network, link?.address, link?.prefixLength ?: 0, canBind)
        }

        fun hasVpn(context: Context): Boolean {
            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
            @Suppress("DEPRECATION")
            return connectivity.allNetworks.any {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }

        /** A short-timeout client for TV commands. */
        val baseClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        }
    }
}
