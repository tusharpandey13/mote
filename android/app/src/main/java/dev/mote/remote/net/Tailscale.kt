package dev.mote.remote.net

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.net.toUri
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** What the app can see of one network: enough to guess whether it is the Tailscale VPN. */
data class NetLink(val vpn: Boolean, val wifi: Boolean, val iface: String?, val addrs: List<InetAddress>)

/**
 * Heuristic, not proof: Android doesn't say which app owns a VPN. A VPN network holding an
 * address in Tailscale's ranges (100.64/10 or fd7a:115c:a1e0::/48) is taken to be Tailscale.
 * Another VPN using CGNAT space would fool it; a Tailscale build with other ranges would be missed.
 */
fun tailnetUp(links: List<NetLink>): Boolean = links.any { l -> l.vpn && l.addrs.any(::isTailnetAddress) }

fun isTailnetAddress(a: InetAddress): Boolean {
    val b = a.address
    return when (a) {
        is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xff) in 64..127
        is Inet6Address -> b.size == 16 && b.take(6).map { it.toInt() and 0xff } == listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0)
        else -> false
    }
}

/** On a non-VPN Wi-Fi network (a LAN route may work without Tailscale). */
fun onWifi(links: List<NetLink>): Boolean = links.any { !it.vpn && it.wifi }

/**
 * Ask to turn Tailscale on only when it is needed: the host has a tailnet route, Tailscale is down,
 * and there is no plausible LAN route (not on Wi-Fi, or the host has no LAN address and wasn't
 * seen nearby).
 */
fun needsTailscalePrompt(routes: List<Route>, tailnetUp: Boolean, onWifi: Boolean, seenOnLan: Boolean): Boolean =
    Route.TAILNET in routes && !tailnetUp && !(onWifi && (seenOnLan || Route.WIFI in routes))

/** The Tailscale Android app (com.tailscale.ipn). Its exported IPNReceiver handles CONNECT_VPN. */
object Tailscale {
    const val PACKAGE = "com.tailscale.ipn"
    private const val RECEIVER = "com.tailscale.ipn.IPNReceiver"
    private const val CONNECT = "com.tailscale.ipn.CONNECT_VPN"

    @Suppress("DEPRECATION") // allNetworks: we need every network, not only the default one
    fun links(c: Context): List<NetLink> = runCatching {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            val lp = cm.getLinkProperties(n)
            NetLink(
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN), caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                lp?.interfaceName, lp?.linkAddresses.orEmpty().map { it.address },
            )
        }
    }.getOrDefault(emptyList())

    fun installed(c: Context): Boolean = c.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    /** Explicit broadcast to Tailscale's IPNReceiver; it starts the VPN if it already has VPN permission and a login. */
    fun requestConnect(c: Context) {
        c.sendBroadcast(Intent(CONNECT).setClassName(PACKAGE, RECEIVER))
    }

    /** Fallback: open the Tailscale app (it may need the user to sign in or grant the VPN permission). */
    fun open(c: Context): Boolean = c.packageManager.getLaunchIntentForPackage(PACKAGE)
        ?.let { runCatching { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess } ?: false

    fun openStore(c: Context) {
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$PACKAGE".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(market) } catch (_: ActivityNotFoundException) {
            runCatching {
                c.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$PACKAGE".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
