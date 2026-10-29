package com.mydrop.android.engine

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Keys for one secure session, already mapped to this side's point of view. */
class SessionKeys(
    val decryptKey: ByteArray,
    val recvHmacKey: ByteArray,
    val encryptKey: ByteArray,
    val sendHmacKey: ByteArray,
    val authKey: ByteArray,
) {
    val pinCode: String get() = Crypto.pinCode(authKey)
}

object Crypto {
    private val random = SecureRandom()
    private val ecParams: ECParameterSpec by lazy {
        val p = AlgorithmParameters.getInstance("EC")
        p.init(ECGenParameterSpec("secp256r1"))
        p.getParameterSpec(ECParameterSpec::class.java)
    }

    // SHA256("D2D")
    private val D2D_SALT = byteArrayOf(
        0x82.toByte(), 0xAA.toByte(), 0x55, 0xA0.toByte(), 0xD3.toByte(), 0x97.toByte(), 0xF8.toByte(), 0x83.toByte(),
        0x46, 0xCA.toByte(), 0x1C, 0xEE.toByte(), 0x8D.toByte(), 0x39, 0x09, 0xB9.toByte(),
        0x5F, 0x13, 0xFA.toByte(), 0x7D, 0xEB.toByte(), 0x1D, 0x4A, 0xB3.toByte(),
        0x83.toByte(), 0x76, 0xB8.toByte(), 0x25, 0x6D, 0xA8.toByte(), 0x55, 0x10,
    )

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        var t = ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        var i = 1
        while (out.size() < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun generateKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        return g.generateKeyPair()
    }

    /** x and y as two's-complement big-endian bytes, as the peers expect. */
    fun signedX(pub: ECPublicKey): ByteArray = pub.w.affineX.toByteArray()
    fun signedY(pub: ECPublicKey): ByteArray = pub.w.affineY.toByteArray()

    fun publicKeyFrom(x: ByteArray, y: ByteArray): ECPublicKey {
        val point = ECPoint(BigInteger(1, x.takeLast32()), BigInteger(1, y.takeLast32()))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, ecParams)) as ECPublicKey
    }

    private fun ByteArray.takeLast32(): ByteArray = if (size > 32) copyOfRange(size - 32, size) else this

    /**
     * Finishes the UKEY2 exchange and derives the four message keys.
     * [clientInit] and [serverInit] are the raw serialized UKEY2 messages as they went over the wire.
     */
    fun deriveSessionKeys(
        privateKey: ECPrivateKey, peerPublic: ECPublicKey,
        clientInit: ByteArray, serverInit: ByteArray, isServer: Boolean,
    ): SessionKeys {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peerPublic, true)
        val dhs = ka.generateSecret()
        val derived = sha256(dhs)

        val info = clientInit + serverInit
        val authString = hkdf(derived, "UKEY2 v1 auth".toByteArray(), info, 32)
        val nextSecret = hkdf(derived, "UKEY2 v1 next".toByteArray(), info, 32)

        val d2dClient = hkdf(nextSecret, D2D_SALT, "client".toByteArray(), 32)
        val d2dServer = hkdf(nextSecret, D2D_SALT, "server".toByteArray(), 32)
        val smsgSalt = sha256("SecureMessage".toByteArray())

        val clientEnc = hkdf(d2dClient, smsgSalt, "ENC:2".toByteArray(), 32)
        val clientSig = hkdf(d2dClient, smsgSalt, "SIG:1".toByteArray(), 32)
        val serverEnc = hkdf(d2dServer, smsgSalt, "ENC:2".toByteArray(), 32)
        val serverSig = hkdf(d2dServer, smsgSalt, "SIG:1".toByteArray(), 32)

        return if (isServer) SessionKeys(clientEnc, clientSig, serverEnc, serverSig, authString)
        else SessionKeys(serverEnc, serverSig, clientEnc, clientSig, authString)
    }

    fun pinCode(authKey: ByteArray): String {
        var hash = 0
        var multiplier = 1
        for (b in authKey) {
            hash = (hash + b.toInt() * multiplier) % 9973
            multiplier = (multiplier * 31) % 9973
        }
        return "%04d".format(Math.abs(hash))
    }

    fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }
}
