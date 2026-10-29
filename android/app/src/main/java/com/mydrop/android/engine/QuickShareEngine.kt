package com.mydrop.android.engine

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** The whole Quick Share engine behind one object: find devices, send files, receive files. */
class QuickShareEngine(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("mydrop", Context.MODE_PRIVATE)
    private val discovery = Discovery(appContext) { Endpoint.serviceName(endpointId) }

    val peers: StateFlow<List<Peer>> get() = discovery.peers

    var deviceName: String
        get() = prefs.getString("deviceName", null) ?: android.os.Build.MODEL
        set(value) { prefs.edit().putString("deviceName", value).apply() }

    val endpointId: String
        get() = prefs.getString("endpointId", null) ?: Endpoint.newEndpointId().also { prefs.edit().putString("endpointId", it).apply() }

    fun advertiseFiles(port: Int) = discovery.advertiseFiles(deviceName, port)
    fun stopAdvertisingFiles() = discovery.stopAdvertisingFiles()

    fun startDiscovery() = discovery.startDiscovery()
    fun stopDiscovery() = discovery.stopDiscovery()

    fun startReceiving(listener: ReceiveListener) {
        discovery.startAdvertising(endpointId, deviceName) { socket -> Inbound(socket, listener).run() }
    }

    fun stopReceiving() = discovery.stopAdvertising()

    fun sendText(peer: Peer, text: String, listener: SendListener): Outbound {
        val out = Outbound(peer, emptyList(), deviceName, endpointId, listener, text)
        Thread({ out.run() }, "mydrop-send-text").start()
        return out
    }

    /** Sends [items] to [peer] on a background thread. Call cancel() on the result to stop. */
    fun send(peer: Peer, items: List<SendItem>, listener: SendListener): Outbound {
        val out = Outbound(peer, items, deviceName, endpointId, listener)
        Thread({ out.run() }, "mydrop-send").start()
        return out
    }
}
