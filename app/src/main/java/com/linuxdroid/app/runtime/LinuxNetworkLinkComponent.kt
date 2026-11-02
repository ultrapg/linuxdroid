package com.linuxdroid.app.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.linuxdroid.app.core.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.security.MessageDigest

class LinuxNetworkLinkComponent(
    context: Context,
    private val rootDir: File,
) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    private var currentProps: LinkProperties? = null
    private var currentCaps: NetworkCapabilities? = null

    fun publishImmediate() = synchronized(lock) {
        currentNetwork = connectivity?.activeNetwork
        currentProps = currentNetwork?.let { connectivity?.getLinkProperties(it) }
        currentCaps = currentNetwork?.let { connectivity?.getNetworkCapabilities(it) }
        writeAll(currentProps, currentCaps)
    }

    fun start() {
        publishImmediate()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = synchronized(lock) {
                currentNetwork = network
                currentProps = connectivity?.getLinkProperties(network)
                currentCaps = connectivity?.getNetworkCapabilities(network)
                writeAll(currentProps, currentCaps)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = synchronized(lock) {
                if (network == currentNetwork) {
                    currentCaps = networkCapabilities
                    writeAll(currentProps, currentCaps)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = synchronized(lock) {
                if (network == currentNetwork) {
                    currentProps = linkProperties
                    writeAll(currentProps, currentCaps)
                }
            }

            override fun onLost(network: Network) = synchronized(lock) {
                if (network == currentNetwork) {
                    currentNetwork = null
                    currentProps = null
                    currentCaps = null
                    writeAll(null, null)
                }
            }
        }
        synchronized(lock) { callback = cb }
        runCatching { connectivity?.registerDefaultNetworkCallback(cb) }
            .onFailure { Log.w(TAG, "Could not register network callback", it) }
    }

    fun stop() {
        val cb = synchronized(lock) { callback.also { callback = null } } ?: return
        runCatching { connectivity?.unregisterNetworkCallback(cb) }
    }

    private fun writeAll(props: LinkProperties?, caps: NetworkCapabilities?) {
        FileUtils.writeString(File(rootDir, LINK_FILE), describeLink(props))
        writeNetworkJson(props, caps)
        writeResolvConf(props)
    }

    @Suppress("DEPRECATION")
    private fun writeNetworkJson(props: LinkProperties?, caps: NetworkCapabilities?) {
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val state = JSONObject().apply {
            put("transport", transport)
            put("interface", props?.interfaceName ?: "wlan0")
            put("mtu", if (Build.VERSION.SDK_INT >= 29 && (props?.mtu ?: 0) > 0) props!!.mtu else DEFAULT_MTU)
            put("connected", props != null && caps != null)
            put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            put("captivePortal", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true)
            put("metered", caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } ?: false)
            put("wifiEnabled", wifiManager?.isWifiEnabled == true)
            put("scanAllowed", false)
            put("lastScan", -1L)
            put("accessPoints", JSONArray())
            put("addresses", JSONArray().apply {
                props?.linkAddresses.orEmpty().forEach {
                    put(JSONObject().put("address", it.address.hostAddress?.substringBefore('%')).put("prefix", it.prefixLength))
                }
            })
            put("gateways", JSONArray().apply {
                props?.routes.orEmpty().filter { it.isDefaultRoute }.forEach { route ->
                    route.gateway?.takeUnless { it.isAnyLocalAddress }?.hostAddress?.substringBefore('%')?.let { put(it) }
                }
            })
            put("dns", JSONArray().apply {
                props?.dnsServers.orEmpty().filter { !it.isLinkLocalAddress }.forEach {
                    put(it.hostAddress?.substringBefore('%'))
                }
            })
            if (transport == "wifi") {
                val info = (if (Build.VERSION.SDK_INT >= 29) caps?.transportInfo as? WifiInfo else null)
                    ?: runCatching { wifiManager?.connectionInfo }.getOrNull()
                if (info != null) {
                    val ssid = runCatching { info.ssid }.getOrNull()
                        ?.removeSurrounding("\"")
                        ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                        ?: "Android Wi-Fi"
                    put("ssid", ssid)
                    put("strength", WifiManager.calculateSignalLevel(info.rssi, 101).coerceIn(0, 100))
                    put("frequency", info.frequency.coerceAtLeast(0))
                    put("bitrate", info.linkSpeed.coerceAtLeast(0) * 1000)
                }
            }
        }
        FileUtils.writeString(File(rootDir, NETWORK_JSON_FILE), state.toString())
    }

    private fun writeResolvConf(props: LinkProperties?) {
        val activeDns = props?.dnsServers.orEmpty()
            .filter { !it.isLinkLocalAddress && !it.isLoopbackAddress && !it.isAnyLocalAddress }
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
        val servers = (activeDns + listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111")).distinct().take(6)
        val resolv = buildString {
            append("# Managed dynamically by LinuxDroid Network Bridge\n")
            for (s in servers) append("nameserver $s\n")
            append("options timeout:2 attempts:2\n")
        }
        val etc = File(rootDir, "etc").apply { mkdirs() }
        FileUtils.writeString(File(etc, "resolv.conf"), resolv)
        val hosts = File(etc, "hosts")
        if (!hosts.isFile || !hosts.readText().contains("localhost")) {
            FileUtils.writeString(hosts, "127.0.0.1 localhost linuxdroid\n::1 localhost ip6-localhost\n")
        }
    }

    private fun describeLink(props: LinkProperties?): String {
        val addrs = props?.linkAddresses.orEmpty().filter { it.address is Inet4Address || it.address is Inet6Address }
        val iface = props?.interfaceName?.takeIf { addrs.isNotEmpty() } ?: "eth0"
        val mtu = if (Build.VERSION.SDK_INT >= 29 && (props?.mtu ?: 0) > 0) props!!.mtu else DEFAULT_MTU
        return buildString {
            append("if $iface 2 $mtu\n")
            append("mac ${seededMac()}\n")
            if (addrs.isEmpty()) {
                append("addr 10.0.0.2 24\n")
                return@buildString
            }
            for (a in addrs) {
                append("addr ${a.address.hostAddress?.substringBefore('%')} ${a.prefixLength}\n")
            }
            for (family in listOf(Inet4Address::class.java, Inet6Address::class.java)) {
                val gw = props?.routes.orEmpty()
                    .firstOrNull { it.isDefaultRoute && family.isInstance(it.gateway) && !it.gateway!!.isAnyLocalAddress }
                    ?.gateway ?: continue
                append("gw ${gw.hostAddress?.substringBefore('%')}\n")
            }
        }
    }

    private fun seededMac(): String {
        val id = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: "linuxdroid"
        val bytes = MessageDigest.getInstance("SHA-1").digest(id.toByteArray(Charsets.UTF_8))
        val b0 = (bytes[0].toInt() and 0xfc) or 0x02
        return listOf(b0, bytes[1].toInt(), bytes[2].toInt(), bytes[3].toInt(), bytes[4].toInt(), bytes[5].toInt())
            .joinToString(":") { "%02x".format(it and 0xff) }
    }

    companion object {
        private const val TAG = "LinuxNetworkLink"
        private const val LINK_FILE = "etc/linuxdroid-net"
        private const val NETWORK_JSON_FILE = "etc/linuxdroid-network.json"
        private const val DEFAULT_MTU = 1500
    }
}
