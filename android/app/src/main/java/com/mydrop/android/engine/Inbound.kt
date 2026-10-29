package com.mydrop.android.engine

import com.google.android.gms.nearby.sharing.Protocol
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionRequestFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionResponseFrame as OfflineResponse
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OsInfo
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame as OfflineV1
import com.google.protobuf.ByteString
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2ClientFinished
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2ClientInit
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2HandshakeCipher
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2Message
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2ServerInit
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.EcP256PublicKey
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.GenericPublicKey
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.PublicKeyType
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.concurrent.ConcurrentHashMap

class IncomingFile(val name: String, val size: Long, val mime: String, val payloadId: Long)

class IncomingRequest(
    val deviceName: String,
    val kind: DeviceKind,
    val pin: String,
    val files: List<IncomingFile>,
    val isText: Boolean = false,
)

interface ReceiveListener {
    /** Called on a worker thread. Block until you know the answer; return true to accept. */
    fun decide(request: IncomingRequest): Boolean
    fun openOutput(file: IncomingFile): OutputStream
    fun onText(text: String, from: String) {}
    fun onProgress(fraction: Double) {}
    fun onFinished(request: IncomingRequest) {}
    fun onFailed(reason: String) {}
}

private class Finished : RuntimeException()

/** Server side: handles one incoming connection from a device that wants to send us files. Blocking. */
class Inbound(private val socket: Socket, private val listener: ReceiveListener) {
    private val channel = Channel(socket)

    fun run() {
        val ch = channel
        try {
            socket.tcpNoDelay = true

            // 1. Connection request
            val first = OfflineFrame.parseFrom(ch.readFrame())
            if (first.v1.type != OfflineV1.FrameType.CONNECTION_REQUEST) throw ProtocolException("Expected connection request")
            val info = Endpoint.parseInfo(first.v1.connectionRequest.endpointInfo.toByteArray())
            val deviceName = info?.first ?: first.v1.connectionRequest.endpointName.ifEmpty { "Unknown device" }
            val kind = info?.second ?: DeviceKind.UNKNOWN

            // 2. UKEY2 client init -> server init
            val clientInitRaw = ch.readFrame()
            val clientInitMsg = Ukey2Message.parseFrom(clientInitRaw)
            if (clientInitMsg.messageType != Ukey2Message.Type.CLIENT_INIT) throw ProtocolException("Expected client init")
            val clientInit = Ukey2ClientInit.parseFrom(clientInitMsg.messageData)
            if (clientInit.version != 1 || clientInit.random.size() != 32) throw ProtocolException("Bad client init")
            if (clientInit.nextProtocol != "AES_256_CBC-HMAC_SHA256") throw ProtocolException("Unsupported protocol")
            val commitment = clientInit.cipherCommitmentsList
                .firstOrNull { it.handshakeCipher == Ukey2HandshakeCipher.P256_SHA512 }?.commitment?.toByteArray()
                ?: throw ProtocolException("No supported cipher")

            val pair = Crypto.generateKeyPair()
            val priv = pair.private as ECPrivateKey
            val pub = pair.public as ECPublicKey
            val serverInitRaw = Ukey2Message.newBuilder()
                .setMessageType(Ukey2Message.Type.SERVER_INIT)
                .setMessageData(
                    Ukey2ServerInit.newBuilder()
                        .setVersion(1)
                        .setRandom(ByteString.copyFrom(Crypto.randomBytes(32)))
                        .setHandshakeCipher(Ukey2HandshakeCipher.P256_SHA512)
                        .setPublicKey(
                            GenericPublicKey.newBuilder().setType(PublicKeyType.EC_P256)
                                .setEcP256PublicKey(
                                    EcP256PublicKey.newBuilder()
                                        .setX(ByteString.copyFrom(Crypto.signedX(pub)))
                                        .setY(ByteString.copyFrom(Crypto.signedY(pub))),
                                ).build().toByteString(),
                        ).build().toByteString(),
                ).build().toByteArray()
            ch.writeFrame(serverInitRaw)

            // 3. Client finish
            val finishRaw = ch.readFrame()
            if (!MessageDigest.isEqual(Crypto.sha512(finishRaw), commitment)) throw ProtocolException("Key commitment mismatch")
            val finishMsg = Ukey2Message.parseFrom(finishRaw)
            if (finishMsg.messageType != Ukey2Message.Type.CLIENT_FINISH) throw ProtocolException("Expected client finish")
            val clientKey = GenericPublicKey.parseFrom(Ukey2ClientFinished.parseFrom(finishMsg.messageData).publicKey).ecP256PublicKey
            val peerPub = Crypto.publicKeyFrom(clientKey.x.toByteArray(), clientKey.y.toByteArray())
            ch.keys = Crypto.deriveSessionKeys(priv, peerPub, clientInitRaw, serverInitRaw, isServer = true)
            val pin = ch.keys!!.pinCode

            // 4. Connection responses (plain), then everything is encrypted
            val resp = OfflineFrame.parseFrom(ch.readFrame())
            if (resp.v1.type != OfflineV1.FrameType.CONNECTION_RESPONSE) throw ProtocolException("Expected connection response")
            ch.writeFrame(
                ch.offlineFrame {
                    setType(OfflineV1.FrameType.CONNECTION_RESPONSE)
                    setConnectionResponse(
                        OfflineResponse.newBuilder().setResponse(OfflineResponse.ResponseStatus.ACCEPT).setStatus(0)
                            .setOsInfo(OsInfo.newBuilder().setType(OsInfo.OsType.ANDROID)),
                    )
                }.toByteArray(),
            )
            ch.sendSharingFrame(
                Protocol.Frame.newBuilder().setVersion(Protocol.Frame.Version.V1).setV1(
                    Protocol.V1Frame.newBuilder().setType(Protocol.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION)
                        .setPairedKeyEncryption(
                            Protocol.PairedKeyEncryptionFrame.newBuilder()
                                .setSecretIdHash(ByteString.copyFrom(Crypto.randomBytes(6)))
                                .setSignedData(ByteString.copyFrom(Crypto.randomBytes(72))),
                        ),
                ).build(),
            )

            // 5. Negotiation, then file chunks
            val outputs = ConcurrentHashMap<Long, OutputStream>()
            val files = ConcurrentHashMap<Long, IncomingFile>()
            val received = ConcurrentHashMap<Long, Long>()
            var request: IncomingRequest? = null
            var totalBytes = 1L
            var totalReceived = 0L
            var remaining = 0
            val open = Object()

            val onChunk: (PayloadTransferFrame) -> Unit = { pt ->
                val id = pt.payloadHeader.id
                val out = outputs[id]
                if (out != null) {
                    val chunk = pt.payloadChunk
                    if (chunk.hasBody() && chunk.body.size() > 0) {
                        out.write(chunk.body.toByteArray())
                        received[id] = (received[id] ?: 0L) + chunk.body.size()
                        totalReceived += chunk.body.size()
                        listener.onProgress(totalReceived.toDouble() / totalBytes)
                    }
                    if (chunk.flags and 1 == 1) {
                        out.close(); outputs.remove(id)
                        synchronized(open) { remaining-- }
                        if (remaining <= 0) throw Finished()
                    }
                }
            }

            try {
                while (true) {
                    val f = ch.readSharingFrame(onChunk) ?: break
                    when (f.v1.type) {
                        Protocol.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION -> ch.sendSharingFrame(
                            Protocol.Frame.newBuilder().setVersion(Protocol.Frame.Version.V1).setV1(
                                Protocol.V1Frame.newBuilder().setType(Protocol.V1Frame.FrameType.PAIRED_KEY_RESULT)
                                    .setPairedKeyResult(
                                        Protocol.PairedKeyResultFrame.newBuilder()
                                            .setStatus(Protocol.PairedKeyResultFrame.Status.UNABLE),
                                    ),
                            ).build(),
                        )
                        Protocol.V1Frame.FrameType.INTRODUCTION -> {
                            val intro = f.v1.introduction
                            if (intro.fileMetadataCount == 0 && intro.textMetadataCount > 0) {
                                val meta = intro.getTextMetadata(0)
                                ch.rawPayloadIds.add(meta.payloadId)
                                ch.onRawPayload = { _, bytes -> listener.onText(String(bytes, Charsets.UTF_8), deviceName); throw Finished() }
                                val req = IncomingRequest(deviceName, kind, pin, emptyList(), isText = true)
                                request = req
                                Thread {
                                    try {
                                        reply(ch, if (listener.decide(req)) Protocol.ConnectionResponseFrame.Status.ACCEPT else Protocol.ConnectionResponseFrame.Status.REJECT)
                                    } catch (e: Exception) { ch.close() }
                                }.start()
                            } else if (intro.fileMetadataCount == 0) {
                                reply(ch, Protocol.ConnectionResponseFrame.Status.UNSUPPORTED_ATTACHMENT_TYPE)
                                ch.sendDisconnection(); return
                            }
                            val list = intro.fileMetadataList.map { IncomingFile(it.name, it.size, it.mimeType, it.payloadId) }
                            list.forEach { files[it.payloadId] = it }
                            totalBytes = list.sumOf { it.size }.coerceAtLeast(1)
                            remaining = list.size
                            val req = IncomingRequest(deviceName, kind, pin, list)
                            request = req
                            // Ask on a separate thread so keep-alives keep being answered while the user decides.
                            Thread {
                                try {
                                    if (listener.decide(req)) {
                                        list.forEach { outputs[it.payloadId] = listener.openOutput(it) }
                                        reply(ch, Protocol.ConnectionResponseFrame.Status.ACCEPT)
                                    } else {
                                        reply(ch, Protocol.ConnectionResponseFrame.Status.REJECT)
                                        ch.sendDisconnection(); ch.close()
                                    }
                                } catch (e: Exception) {
                                    listener.onFailed(e.message ?: "Couldn't save the file"); ch.close()
                                }
                            }.start()
                        }
                        Protocol.V1Frame.FrameType.CANCEL -> throw ProtocolException("The sender cancelled")
                        else -> {}
                    }
                }
            } catch (_: Finished) {
                ch.sendDisconnection()
                request?.let { listener.onFinished(it) }
            } finally {
                outputs.values.forEach { try { it.close() } catch (_: Exception) {} }
            }
        } catch (e: PeerDisconnected) {
            // Normal end after a rejected or finished transfer.
        } catch (e: Exception) {
            listener.onFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            ch.close()
        }
    }

    private fun reply(ch: Channel, status: Protocol.ConnectionResponseFrame.Status) {
        ch.sendSharingFrame(
            Protocol.Frame.newBuilder().setVersion(Protocol.Frame.Version.V1).setV1(
                Protocol.V1Frame.newBuilder().setType(Protocol.V1Frame.FrameType.RESPONSE)
                    .setConnectionResponse(Protocol.ConnectionResponseFrame.newBuilder().setStatus(status)),
            ).build(),
        )
    }
}
