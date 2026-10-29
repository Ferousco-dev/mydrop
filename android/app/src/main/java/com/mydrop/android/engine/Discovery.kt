package com.mydrop.android.engine

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.ServerSocket
import java.util.concurrent.Executors

private const val TAG = "MyDropEngine"
private const val FIXED_PORT = 50362

/** Finds nearby devices over mDNS and advertises this phone so others can send to it. */
class Discovery(context: Context, private val ownPrefix: () -> String) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val resolver = Executors.newFixedThreadPool(3)
    private var ownServiceName: String? = null

    // ---------- finding other devices ----------

    fun startDiscovery() {
        if (discoveryListener != null) return
        acquireLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { Log.d(TAG, "discovery started") }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (isMine(info.serviceName)) return
                if (android.os.Build.VERSION.SDK_INT >= 34) watch(info) else resolver.execute { resolve(info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _peers.value = _peers.value.filterNot { it.id == info.serviceName }
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        nsd.discoverServices(Endpoint.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopDiscovery() {
        discoveryListener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discoveryListener = null
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            watchers.values.forEach { try { @android.annotation.SuppressLint("NewApi") nsd.unregisterServiceInfoCallback(it as NsdManager.ServiceInfoCallback) } catch (_: Exception) {} }
        }
        watchers.clear()
        _peers.value = emptyList()
        releaseLock()
    }

    /** Android 14+: follow a service live, so address changes and disappearances show up immediately. */
    private val watchers = HashMap<String, Any>()

    @android.annotation.SuppressLint("NewApi")
    private fun watch(info: NsdServiceInfo) {
        val name = info.serviceName
        if (watchers.containsKey(name)) return
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { watchers.remove(name) }
            override fun onServiceUpdated(s: NsdServiceInfo) {
                val host = (s.hostAddresses.firstOrNull { it is java.net.Inet4Address } ?: s.hostAddresses.firstOrNull())?.hostAddress
                publish(s.serviceName, host, s.port, s.attributes["n"])
            }
            override fun onServiceLost() { _peers.value = _peers.value.filterNot { it.id == name }; watchers.remove(name) }
            override fun onServiceInfoCallbackUnregistered() {}
        }
        watchers[name] = cb
        nsd.registerServiceInfoCallback(info, resolver, cb)
    }

    private fun publish(name: String, host: String?, port: Int, txt: ByteArray?) {
        if (host == null || isMine(name)) return
        try {
            val parsed = txt?.let { Endpoint.parseInfo(Endpoint.b64decode(String(it, Charsets.US_ASCII))) }
            val peer = Peer(name, parsed?.first ?: name, parsed?.second ?: DeviceKind.UNKNOWN, host, port)
            _peers.value = _peers.value.filterNot { it.id == peer.id || (it.name == peer.name && it.host == peer.host) } + peer   // one entry per device
        } catch (e: Exception) { Log.w(TAG, "bad service record", e) }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        val done = java.util.concurrent.CountDownLatch(1)
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "resolve failed $errorCode"); done.countDown() }
            override fun onServiceResolved(s: NsdServiceInfo) {
                if (isMine(s.serviceName)) { done.countDown(); return }
                try {
                    val txt = s.attributes["n"]?.let { String(it, Charsets.US_ASCII) }
                    val parsed = txt?.let { Endpoint.parseInfo(Endpoint.b64decode(it)) }
                    val host = s.host?.hostAddress
                    if (host != null) {
                        val peer = Peer(s.serviceName, parsed?.first ?: s.serviceName, parsed?.second ?: DeviceKind.UNKNOWN, host, s.port)
                        _peers.value = _peers.value.filterNot { it.id == peer.id || (it.name == peer.name && it.host == peer.host) } + peer   // one entry per device
                        Log.d(TAG, "found ${peer.name} at $host:${peer.port}")
                    }
                } catch (e: Exception) { Log.w(TAG, "bad service record", e) }
                done.countDown()
            }
        })
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** True for the advertisement this phone itself publishes (the system may add a " (2)" suffix). */
    private fun isMine(name: String) = name.startsWith(ownPrefix()) || name == ownServiceName

    // ---------- being found ----------

    private var server: ServerSocket? = null
    @Volatile private var accepting = false

    /** Starts listening and advertising. [onConnection] gets each incoming socket on its own thread. */
    fun startAdvertising(endpointId: String, deviceName: String, onConnection: (java.net.Socket) -> Unit) {
        if (server != null) return
        acquireLock()
        // A fixed port means a cached network record can never point at a stale one.
        val ss = try { ServerSocket().apply { reuseAddress = true; receiveBufferSize = 1 shl 20; bind(java.net.InetSocketAddress(FIXED_PORT)) } } catch (_: Exception) { ServerSocket(0) }
        server = ss
        accepting = true
        Thread({
            while (accepting) {
                try {
                    val s = ss.accept()
                    Thread({ onConnection(s) }, "mydrop-incoming").start()
                } catch (_: Exception) { break }
            }
        }, "mydrop-accept").start()

        val name = Endpoint.serviceName(endpointId)
        ownServiceName = name
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = Endpoint.SERVICE_TYPE.trimEnd('.')
            port = ss.localPort
            setAttribute("n", Endpoint.txtValue(Endpoint.endpointInfo(deviceName, DeviceKind.PHONE)))
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                ownServiceName = i.serviceName
                _peers.value = _peers.value.filterNot { isMine(it.id) }
                Log.d(TAG, "advertising as ${i.serviceName} on ${ss.localPort}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "advertise failed $errorCode") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = reg
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
    }

    fun stopAdvertising() {
        accepting = false
        registrationListener?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        registrationListener = null
        try { server?.close() } catch (_: Exception) {}
        server = null
        ownServiceName = null
        releaseLock()
    }

    // ---------- file sharing advertisement ----------

    private var filesListener: NsdManager.RegistrationListener? = null

    fun advertiseFiles(name: String, port: Int) {
        stopAdvertisingFiles()
        acquireLock()
        val info = NsdServiceInfo().apply { serviceName = name; serviceType = "_mydropfiles._tcp"; this.port = port }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, errorCode: Int) {}
        }
        filesListener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stopAdvertisingFiles() {
        filesListener?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        filesListener = null
    }

    private fun acquireLock() {
        if (lock == null) lock = wifi.createMulticastLock("mydrop").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLock() {
        if (discoveryListener == null && server == null) { lock?.let { if (it.isHeld) it.release() }; lock = null }
    }
}
