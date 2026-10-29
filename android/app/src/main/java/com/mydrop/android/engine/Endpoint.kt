package com.mydrop.android.engine

import android.util.Base64

enum class DeviceKind(val code: Int) { UNKNOWN(0), PHONE(1), TABLET(2), LAPTOP(3);
    companion object { fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN } }

/** Another device found on the network. */
data class Peer(
    val id: String,          // mDNS service name
    val name: String,
    val kind: DeviceKind,
    val host: String,
    val port: Int,
)

object Endpoint {
    const val SERVICE_TYPE = "_FC9F5ED42C8A._tcp."

    private fun b64(data: ByteArray) =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun b64decode(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP)

    /** 4 random alphanumeric characters that identify this device to its peers. */
    fun newEndpointId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return String(CharArray(4) { chars[Crypto.randomBytes(1)[0].toInt().and(0xFF) % chars.length] })
    }

    /** mDNS service name: 0x23, endpoint id, service id FC 9F 5E, two zero bytes. */
    fun serviceName(endpointId: String): String =
        b64(byteArrayOf(0x23) + endpointId.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0xFC.toByte(), 0x9F.toByte(), 0x5E, 0, 0))

    /** The "endpoint info" blob: flags, 16 random bytes, length-prefixed visible name. */
    fun endpointInfo(name: String, kind: DeviceKind): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        val flags = (kind.code shl 1)   // version 0, visible, device type
        return byteArrayOf(flags.toByte()) + Crypto.randomBytes(16) + byteArrayOf(nameBytes.size.toByte()) + nameBytes
    }

    fun txtValue(info: ByteArray): String = b64(info)

    /** Reads name and device type out of an endpoint-info blob. Returns null if it is too short. */
    fun parseInfo(info: ByteArray): Pair<String, DeviceKind>? {
        if (info.size < 18) return null
        val len = info[17].toInt() and 0xFF
        if (info.size < 18 + len) return null
        val kind = DeviceKind.from((info[0].toInt() shr 1) and 7)
        return String(info, 18, len, Charsets.UTF_8) to kind
    }
}
