package com.mydrop.android.engine

import com.google.android.gms.nearby.sharing.Protocol
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionRequestFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionResponseFrame as OfflineResponse
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OsInfo
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
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import com.google.android.gms.nearby.sharing.Protocol.FileMetadata as FileMeta
import com.google.android.gms.nearby.sharing.Protocol.IntroductionFrame

/** One file (or content:// item) to send. */
class SendItem(val name: String, val size: Long, val mime: String, val open: () -> InputStream)

interface SendListener {
    fun onConnected(pin: String) {}
    fun onAccepted() {}
    fun onProgress(fraction: Double) {}
    fun onFinished() {}
    fun onFailed(reason: String) {}
}

/** Client side: connects to a device that is advertising and sends it files. Blocking; run it off the main thread. */
class Outbound(
    private val peer: Peer,
    private val items: List<SendItem>,
    private val myName: String,
    private val myEndpointId: String,
    private val listener: SendListener,
    private val text: String? = null,
) {
    @Volatile private var cancelled = false
    private var channel: Channel? = null

    fun cancel() {
        cancelled = true
        channel?.let { c ->
            try {
                c.sendSharingFrame(Protocol.Frame.newBuilder().setVersion(Protocol.Frame.Version.V1)
                    .setV1(Protocol.V1Frame.newBuilder().setType(Protocol.V1Frame.FrameType.CANCEL)).build())
            } catch (_: Exception) {}
            c.sendDisconnection(); c.close()
        }
    }

    fun run() {
        val socket = Socket()
        val ch = Channel(socket)
        channel = ch
        try {
            socket.connect(InetSocketAddress(peer.host, peer.port), 10_000)
            socket.tcpNoDelay = true
            socket.sendBufferSize = 1 shl 20
            socket.receiveBufferSize = 1 shl 20

            // 1. Connection request + UKEY2 client init
            ch.writeFrame(
                ch.offlineFrame {
                    setType(OfflineV1.FrameType.CONNECTION_REQUEST)
                    setConnectionRequest(
                        ConnectionRequestFrame.newBuilder()
                            .setEndpointId(myEndpointId)
                            .setEndpointName(myName)
                            .setEndpointInfo(ByteString.copyFrom(Endpoint.endpointInfo(myName, DeviceKind.PHONE)))
                            .addMediums(ConnectionRequestFrame.Medium.WIFI_LAN),
                    )
                }.toByteArray(),
            )

            val pair = Crypto.generateKeyPair()
            val priv = pair.private as ECPrivateKey
            val pub = pair.public as ECPublicKey

            val finishMsg = Ukey2Message.newBuilder()
                .setMessageType(Ukey2Message.Type.CLIENT_FINISH)
                .setMessageData(
                    Ukey2ClientFinished.newBuilder().setPublicKey(
                        GenericPublicKey.newBuilder().setType(PublicKeyType.EC_P256)
                            .setEcP256PublicKey(
                                EcP256PublicKey.newBuilder()
                                    .setX(ByteString.copyFrom(Crypto.signedX(pub)))
                                    .setY(ByteString.copyFrom(Crypto.signedY(pub))),
                            ).build().toByteString(),
                    ).build().toByteString(),
                ).build().toByteArray()

            val clientInitMsg = Ukey2Message.newBuilder()
                .setMessageType(Ukey2Message.Type.CLIENT_INIT)
                .setMessageData(
                    Ukey2ClientInit.newBuilder()
                        .setVersion(1)
                        .setRandom(ByteString.copyFrom(Crypto.randomBytes(32)))
                        .setNextProtocol("AES_256_CBC-HMAC_SHA256")
                        .addCipherCommitments(
                            Ukey2ClientInit.CipherCommitment.newBuilder()
                                .setHandshakeCipher(Ukey2HandshakeCipher.P256_SHA512)
                                .setCommitment(ByteString.copyFrom(Crypto.sha512(finishMsg))),
                        ).build().toByteString(),
                ).build().toByteArray()
            ch.writeFrame(clientInitMsg)

            // 2. Server init -> keys
            val serverInitRaw = ch.readFrame()
            val serverInitMsg = Ukey2Message.parseFrom(serverInitRaw)
            if (serverInitMsg.messageType != Ukey2Message.Type.SERVER_INIT) throw ProtocolException("Expected server init")
            val serverInit = Ukey2ServerInit.parseFrom(serverInitMsg.messageData)
            if (serverInit.version != 1 || serverInit.random.size() != 32 ||
                serverInit.handshakeCipher != Ukey2HandshakeCipher.P256_SHA512
            ) throw ProtocolException("Unsupported key exchange")
            val serverKey = GenericPublicKey.parseFrom(serverInit.publicKey).ecP256PublicKey
            val peerPub = Crypto.publicKeyFrom(serverKey.x.toByteArray(), serverKey.y.toByteArray())
            ch.keys = Crypto.deriveSessionKeys(priv, peerPub, clientInitMsg, serverInitRaw, isServer = false)

            // 3. Client finish + connection responses (plain)
            ch.writeFrame(finishMsg)
            ch.writeFrame(connectionResponse(ch).toByteArray())
            val resp = OfflineFrame.parseFrom(ch.readFrame())
            if (resp.v1.type != OfflineV1.FrameType.CONNECTION_RESPONSE ||
                resp.v1.connectionResponse.response != OfflineResponse.ResponseStatus.ACCEPT
            ) throw ProtocolException("The other device refused the connection")
            listener.onConnected(ch.keys!!.pinCode)

            // 4. Paired key exchange (not used, but the other side expects it)
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

            var sentResult = false
            var gotResult = false
            while (!(sentResult && gotResult)) {
                val f = ch.readSharingFrame() ?: return
                when (f.v1.type) {
                    Protocol.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION -> {
                        ch.sendSharingFrame(
                            Protocol.Frame.newBuilder().setVersion(Protocol.Frame.Version.V1).setV1(
                                Protocol.V1Frame.newBuilder().setType(Protocol.V1Frame.FrameType.PAIRED_KEY_RESULT)
                                    .setPairedKeyResult(
                                        Protocol.PairedKeyResultFrame.newBuilder()
                                            .setStatus(Protocol.PairedKeyResultFrame.Status.UNABLE),
                                    ),
                            ).build(),
                        )
                        sentResult = true
                    }
                    Protocol.V1Frame.FrameType.PAIRED_KEY_RESULT -> gotResult = true
                    Protocol.V1Frame.FrameType.CANCEL -> throw ProtocolException("The other device cancelled")
                    else -> {}
                }
            }

            // 5. Introduction (what we want to send)
            val ids = items.map { java.util.concurrent.ThreadLocalRandom.current().nextLong() }
            val intro = IntroductionFrame.newBuilder()
            val textId = java.util.concurrent.ThreadLocalRandom.current().nextLong()
            val textBytes = text?.toByteArray(Charsets.UTF_8)
            if (text != null && textBytes != null) {
                intro.addTextMetadata(
                    Protocol.TextMetadata.newBuilder().setType(Protocol.TextMetadata.Type.TEXT)
                        .setTextTitle(text.take(40)).setPayloadId(textId).setSize(textBytes.size.toLong()),
                )
            }
            items.forEachIndexed { i, item ->
                intro.addFileMetadata(
                    FileMeta.newBuilder().setName(sanitize(item.name)).setSize(item.size).setMimeType(item.mime)
                        .setType(typeFor(item)).setPayloadId(ids[i]),
                )
            }
            ch.sendSharingFrame(
                Protocol.Frame.newBuilder().setVersion(Protocol.Frame.Version.V1).setV1(
                    Protocol.V1Frame.newBuilder().setType(Protocol.V1Frame.FrameType.INTRODUCTION).setIntroduction(intro),
                ).build(),
            )

            // 6. Wait for accept / reject
            var accepted = false
            while (!accepted) {
                val f = ch.readSharingFrame() ?: return
                when (f.v1.type) {
                    Protocol.V1Frame.FrameType.RESPONSE -> when (f.v1.connectionResponse.status) {
                        Protocol.ConnectionResponseFrame.Status.ACCEPT -> accepted = true
                        Protocol.ConnectionResponseFrame.Status.NOT_ENOUGH_SPACE -> throw ProtocolException("Not enough space on the other device")
                        Protocol.ConnectionResponseFrame.Status.UNSUPPORTED_ATTACHMENT_TYPE -> throw ProtocolException("The other device can't take this file type")
                        Protocol.ConnectionResponseFrame.Status.TIMED_OUT -> throw ProtocolException("The other device didn't answer in time")
                        else -> throw ProtocolException("The other device declined")
                    }
                    Protocol.V1Frame.FrameType.CANCEL -> throw ProtocolException("The other device cancelled")
                    else -> {}
                }
            }
            listener.onAccepted()
            if (textBytes != null) {
                ch.sendBytesPayload(textId, textBytes)
                ch.sendDisconnection()
                listener.onFinished()
                return
            }

            // 7. Send the files
            val total = items.sumOf { it.size }.coerceAtLeast(1)
            var sent = 0L
            val buffer = ByteArray(1024 * 1024)
            items.forEachIndexed { i, item ->
                item.open().use { stream ->
                    var offset = 0L
                    while (true) {
                        if (cancelled) return
                        val n = stream.read(buffer)
                        if (n <= 0) break
                        ch.sendFileChunk(ids[i], item.size, offset, buffer.copyOf(n), last = false)
                        offset += n; sent += n
                        listener.onProgress(sent.toDouble() / total)
                    }
                    ch.sendFileChunk(ids[i], item.size, offset, null, last = true)
                }
            }
            ch.sendDisconnection()
            // Allow receiver brief time to process final chunks and flush before socket termination
            try { Thread.sleep(600) } catch (_: InterruptedException) {}
            listener.onFinished()
        } catch (e: Exception) {
            if (!cancelled) listener.onFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            try { ch.close() } catch (_: Exception) {}
        }
    }

    private fun connectionResponse(ch: Channel): OfflineFrame = ch.offlineFrame {
        setType(OfflineV1.FrameType.CONNECTION_RESPONSE)
        setConnectionResponse(
            OfflineResponse.newBuilder().setResponse(OfflineResponse.ResponseStatus.ACCEPT).setStatus(0)
                .setOsInfo(OsInfo.newBuilder().setType(OsInfo.OsType.ANDROID)),
        )
    }

    private fun typeFor(item: SendItem): FileMeta.Type = when {
        item.mime.startsWith("image/") -> FileMeta.Type.IMAGE
        item.mime.startsWith("video/") -> FileMeta.Type.VIDEO
        item.mime.startsWith("audio/") -> FileMeta.Type.AUDIO
        item.name.endsWith(".apk", true) -> FileMeta.Type.ANDROID_APP
        else -> FileMeta.Type.UNKNOWN
    }

    private fun sanitize(name: String) = name.replace(Regex("[/\\\\?%*:|\"<>=]"), "_")
}
