package com.mydrop.android.engine

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.KeepAliveFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.DisconnectionFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame as OfflineV1
import com.google.android.gms.nearby.sharing.Protocol
import com.google.security.cryptauth.lib.securegcm.DeviceToDeviceMessagesProto.DeviceToDeviceMessage
import com.google.security.cryptauth.lib.securegcm.SecureGcmProto.GcmMetadata
import com.google.security.cryptauth.lib.securegcm.SecureGcmProto.Type as GcmType
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.EncScheme
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.Header
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.HeaderAndBody
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.SecureMessage
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.SigScheme
import com.google.protobuf.ByteString
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest

class ProtocolException(message: String) : IOException(message)
class PeerDisconnected : IOException("The other device disconnected")

/** One TCP connection: length-prefixed frames, then (after the key exchange) encrypted frames. */
open class Channel(protected val socket: Socket) {
    // Created on first use, because the sender builds this object before the socket is connected.
    private val input by lazy { DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024)) }
    private val output by lazy { BufferedOutputStream(socket.getOutputStream(), 256 * 1024) }
    private val sendLock = Any()
    private var sendSeq = 0
    private var recvSeq = 0
    private val partialPayloads = HashMap<Long, java.io.ByteArrayOutputStream>()
    /** Payload ids that carry plain bytes (such as text) instead of a negotiation message. */
    val rawPayloadIds = HashSet<Long>()
    var onRawPayload: ((Long, ByteArray) -> Unit)? = null

    var keys: SessionKeys? = null
    @Volatile var closed = false

    // ---------- plain framing ----------

    fun readFrame(): ByteArray {
        val len = try { input.readInt() } catch (e: java.io.EOFException) { throw PeerDisconnected() }
        if (len < 0 || len > MAX_FRAME) throw ProtocolException("Unexpected packet length $len")
        val buf = ByteArray(len)
        try { input.readFully(buf) } catch (e: java.io.EOFException) { throw PeerDisconnected() }
        return buf
    }

    fun writeFrame(data: ByteArray) = synchronized(sendLock) {
        val len = data.size
        output.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        output.write(data)
        output.flush()
    }

    // ---------- offline frames ----------

    fun offlineFrame(build: OfflineV1.Builder.() -> Unit): OfflineFrame =
        OfflineFrame.newBuilder().setVersion(OfflineFrame.Version.V1)
            .setV1(OfflineV1.newBuilder().apply(build)).build()

    // ---------- secure messages ----------

    fun sendEncrypted(frame: OfflineFrame) = synchronized(sendLock) {
        val k = keys ?: throw ProtocolException("No keys yet")
        val d2d = DeviceToDeviceMessage.newBuilder()
            .setSequenceNumber(++sendSeq)
            .setMessage(frame.toByteString()).build().toByteArray()
        val iv = Crypto.randomBytes(16)
        val body = Crypto.aesCbcEncrypt(k.encryptKey, iv, d2d)
        val metadata = GcmMetadata.newBuilder().setType(GcmType.DEVICE_TO_DEVICE_MESSAGE).setVersion(1).build()
        val hb = HeaderAndBody.newBuilder()
            .setHeader(
                Header.newBuilder()
                    .setEncryptionScheme(EncScheme.AES_256_CBC)
                    .setSignatureScheme(SigScheme.HMAC_SHA256)
                    .setIv(ByteString.copyFrom(iv))
                    .setPublicMetadata(metadata.toByteString()),
            )
            .setBody(ByteString.copyFrom(body)).build().toByteArray()
        val msg = SecureMessage.newBuilder()
            .setHeaderAndBody(ByteString.copyFrom(hb))
            .setSignature(ByteString.copyFrom(Crypto.hmacSha256(k.sendHmacKey, hb))).build()
        // Sequence numbers must reach the wire in order, so encrypt and write under one lock.
        writeFrame(msg.toByteArray())
    }

    private fun decrypt(raw: ByteArray): OfflineFrame {
        val k = keys ?: throw ProtocolException("No keys yet")
        val msg = SecureMessage.parseFrom(raw)
        val expected = Crypto.hmacSha256(k.recvHmacKey, msg.headerAndBody.toByteArray())
        if (!MessageDigest.isEqual(expected, msg.signature.toByteArray())) throw ProtocolException("Bad signature")
        val hb = HeaderAndBody.parseFrom(msg.headerAndBody)
        val plain = Crypto.aesCbcDecrypt(k.decryptKey, hb.header.iv.toByteArray(), hb.body.toByteArray())
        val d2d = DeviceToDeviceMessage.parseFrom(plain)
        if (d2d.sequenceNumber != ++recvSeq) throw ProtocolException("Wrong sequence number ${d2d.sequenceNumber}")
        return OfflineFrame.parseFrom(d2d.message)
    }

    // ---------- payload layer ----------

    private fun payloadFrame(
        id: Long, type: PayloadTransferFrame.PayloadHeader.PayloadType, total: Long,
        offset: Long, flags: Int, body: ByteArray?,
    ): OfflineFrame {
        val chunk = PayloadTransferFrame.PayloadChunk.newBuilder().setOffset(offset).setFlags(flags)
        if (body != null) chunk.setBody(ByteString.copyFrom(body))
        return offlineFrame {
            setType(OfflineV1.FrameType.PAYLOAD_TRANSFER)
            setPayloadTransfer(
                PayloadTransferFrame.newBuilder()
                    .setPacketType(PayloadTransferFrame.PacketType.DATA)
                    .setPayloadHeader(
                        PayloadTransferFrame.PayloadHeader.newBuilder()
                            .setId(id).setType(type).setTotalSize(total).setIsSensitive(false),
                    )
                    .setPayloadChunk(chunk),
            )
        }
    }

    /** Negotiation messages go out as a bytes payload: the whole message, then an empty "last chunk". */
    fun sendSharingFrame(frame: Protocol.Frame) =
        sendBytesPayload(java.util.concurrent.ThreadLocalRandom.current().nextLong(), frame.toByteArray())

    fun sendBytesPayload(id: Long, data: ByteArray) {
        sendEncrypted(payloadFrame(id, PayloadTransferFrame.PayloadHeader.PayloadType.BYTES, data.size.toLong(), 0, 0, data))
        sendEncrypted(payloadFrame(id, PayloadTransferFrame.PayloadHeader.PayloadType.BYTES, data.size.toLong(), data.size.toLong(), 1, null))
    }

    fun sendFileChunk(id: Long, total: Long, offset: Long, body: ByteArray?, last: Boolean) {
        sendEncrypted(payloadFrame(id, PayloadTransferFrame.PayloadHeader.PayloadType.FILE, total, offset, if (last) 1 else 0, body))
    }

    fun sendKeepAlive(ack: Boolean) {
        sendEncrypted(offlineFrame {
            setType(OfflineV1.FrameType.KEEP_ALIVE)
            setKeepAlive(KeepAliveFrame.newBuilder().setAck(ack))
        })
    }

    fun sendDisconnection() {
        try {
            sendEncrypted(offlineFrame {
                setType(OfflineV1.FrameType.DISCONNECTION)
                setDisconnection(DisconnectionFrame.getDefaultInstance())
            })
        } catch (_: Exception) {}
    }

    /**
     * Reads encrypted frames until a complete negotiation message arrives. File chunks seen on the way
     * go to [onFileChunk]. Keep-alives are answered here.
     */
    fun readSharingFrame(onFileChunk: ((PayloadTransferFrame) -> Unit)? = null): Protocol.Frame? {
        while (true) {
            val frame = decrypt(readFrame())
            if (!frame.hasV1()) continue
            val v1 = frame.v1
            when (v1.type) {
                OfflineV1.FrameType.KEEP_ALIVE -> { if (!v1.keepAlive.ack) sendKeepAlive(true) }
                OfflineV1.FrameType.DISCONNECTION -> throw PeerDisconnected()
                OfflineV1.FrameType.PAYLOAD_TRANSFER -> {
                    val pt = v1.payloadTransfer
                    val header = pt.payloadHeader
                    if (header.type == PayloadTransferFrame.PayloadHeader.PayloadType.BYTES) {
                        val buf = partialPayloads.getOrPut(header.id) { java.io.ByteArrayOutputStream() }
                        if (pt.payloadChunk.hasBody()) buf.write(pt.payloadChunk.body.toByteArray())
                        if (pt.payloadChunk.flags and 1 == 1) {
                            partialPayloads.remove(header.id)
                            if (header.id in rawPayloadIds) { onRawPayload?.invoke(header.id, buf.toByteArray()) }
                            else return Protocol.Frame.parseFrom(buf.toByteArray())
                        }
                    } else if (header.type == PayloadTransferFrame.PayloadHeader.PayloadType.FILE) {
                        onFileChunk?.invoke(pt)
                    }
                }
                else -> {}
            }
            if (closed) return null
        }
    }

    fun close() {
        closed = true
        try { socket.close() } catch (_: Exception) {}
    }

    companion object { const val MAX_FRAME = 5 * 1024 * 1024 }
}
