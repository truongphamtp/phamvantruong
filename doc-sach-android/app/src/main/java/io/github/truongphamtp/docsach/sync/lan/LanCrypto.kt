package io.github.truongphamtp.docsach.sync.lan

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mật mã cho đồng bộ qua Wi‑Fi:
 * - Ghép đôi: trao đổi khoá ECDH (P‑256) rồi trộn với mã 6 số người dùng nhập → khoá chung 256 bit.
 *   Người nghe lén trong mạng không tính được khoá (không có khoá bí mật ECDH).
 * - Mỗi yêu cầu ký HMAC‑SHA256 bằng khoá chung; dữ liệu thư viện (ghi chú, vị trí đọc) mã hoá AES‑GCM.
 */
internal object LanCrypto {
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    /** Mã ghép đôi 6 chữ số. */
    fun newCode(): String = (random.nextInt(900_000) + 100_000).toString()

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun publicKey(pair: KeyPair): ByteArray = pair.public.encoded

    fun sharedSecret(own: KeyPair, peerPublic: ByteArray): ByteArray {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublic))
        return KeyAgreement.getInstance("ECDH").run {
            init(own.private)
            doPhase(peer, true)
            generateSecret()
        }
    }

    /** Khoá chung của một cặp máy: phụ thuộc cả bí mật ECDH lẫn mã ghép đôi. */
    fun pairingKey(shared: ByteArray, salt: ByteArray, code: String, hostId: String, joinerId: String): ByteArray =
        hkdf(shared, salt, "docsach-ghep-doi|$code|$hostId|$joinerId".toByteArray(Charsets.UTF_8))

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** HKDF‑SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sameBytes(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** AES‑256‑GCM: kết quả = IV (12 byte) + bản mã kèm thẻ xác thực. */
    fun encrypt(key: ByteArray, plain: ByteArray, aad: String): ByteArray {
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(key: ByteArray, data: ByteArray, aad: String): ByteArray {
        require(data.size > 12 + 16) { "dữ liệu mã hoá quá ngắn" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(data, 12, data.size - 12)
    }
}

/** Ký và kiểm tra chữ ký yêu cầu giữa hai máy đã ghép đôi. */
internal object LanAuth {
    const val ID = "X-DS-Id"
    const val TIME = "X-DS-Time"
    const val NONCE = "X-DS-Nonce"
    const val SIG = "X-DS-Sig"
    const val BODY = "X-DS-Body"

    /** Thân yêu cầu không ký (tệp sách lớn — đã có dấu vân tay kiểm tra khi nhận). */
    const val UNSIGNED_BODY = "-"

    /** Lệch giờ tối đa giữa hai máy. */
    const val MAX_SKEW_MS = 24L * 60 * 60 * 1000

    fun signature(key: ByteArray, method: String, target: String, time: Long, nonce: String, bodyHash: String): String =
        LanCrypto.b64(LanCrypto.hmac(key, "$method\n$target\n$time\n$nonce\n$bodyHash".toByteArray(Charsets.UTF_8)))

    fun headers(key: ByteArray, myId: String, method: String, target: String, bodyHash: String, now: Long): Map<String, String> {
        val nonce = LanCrypto.b64(LanCrypto.randomBytes(12))
        return mapOf(
            ID to myId,
            TIME to now.toString(),
            NONCE to nonce,
            BODY to bodyHash,
            SIG to signature(key, method, target, now, nonce, bodyHash),
        )
    }
}
