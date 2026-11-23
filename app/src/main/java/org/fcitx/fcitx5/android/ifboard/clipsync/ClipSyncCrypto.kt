/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard clipboard sync: key derivation, authenticated encryption and message framing.
 * Must stay byte-compatible with windows/IfboardWin/Services/ClipSyncCrypto.cs.
 */
package org.fcitx.fcitx5.android.ifboard.clipsync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 两端用同一个 WebDAV 账号密码推导出相同的主密钥：
 *   master = PBKDF2-HMAC-SHA256(password, "ifboard-clip-v1|" + user.lowercase(), 120000, 32)
 *   enc  = HMAC(master, "enc")   AES-256-GCM 内容加密
 *   disc = HMAC(master, "disc")  局域网发现的请求 / 回应校验
 *   fp   = HMAC(master, "fp") 前 4 字节，仅用于界面上核对两端是否同一把钥匙（不泄露密钥）
 */
class ClipSyncKeys private constructor(val enc: ByteArray, val disc: ByteArray, val fingerprint: String) {

    companion object {
        const val ITERATIONS = 120_000
        private const val SALT_PREFIX = "ifboard-clip-v1|"

        fun derive(user: String, password: String, iterations: Int = ITERATIONS): ClipSyncKeys {
            val salt = (SALT_PREFIX + user.trim().lowercase()).toByteArray(Charsets.UTF_8)
            val master = pbkdf2Sha256(password.toByteArray(Charsets.UTF_8), salt, iterations, 32)
            val enc = hmac(master, "enc".toByteArray())
            val disc = hmac(master, "disc".toByteArray())
            val fp = hmac(master, "fp".toByteArray()).copyOf(4).joinToString("") { "%02x".format(it) }
            master.fill(0)
            return ClipSyncKeys(enc, disc, fp)
        }

        /** 手写 PBKDF2，避免不同 Android 实现对密码字符编码的差异，保证与 .NET 完全一致 */
        fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
            val hLen = 32
            val blocks = (length + hLen - 1) / hLen
            val out = ByteArray(blocks * hLen)
            for (i in 1..blocks) {
                mac.update(salt)
                mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
                var u = mac.doFinal()
                val t = u.copyOf()
                for (n in 1 until iterations) {
                    u = mac.doFinal(u)
                    for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
                System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
            }
            return out.copyOf(length)
        }

        internal fun restore(enc: ByteArray, disc: ByteArray, fingerprint: String) = ClipSyncKeys(enc, disc, fingerprint)

        fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    }
}

object ClipSyncCrypto {
    private val AAD = "IFBC1".toByteArray()
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    /** nonce(12) || ciphertext || tag(16) */
    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = randomBytes(NONCE_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(AAD)
        val ct = c.doFinal(plain)
        return nonce + ct
    }

    /** 解密失败（密钥不同 / 被篡改）返回 null */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray? = runCatching {
        if (sealed.size < NONCE_LEN + 16) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_LEN))
        c.updateAAD(AAD)
        c.doFinal(sealed, NONCE_LEN, sealed.size - NONCE_LEN)
    }.getOrNull()
}

/** 明文消息：一行 JSON 头 + 换行符 + 正文字节（文字为 UTF-8，图片为 PNG 等原始字节） */
class ClipMessage(val header: JsonObject, val body: ByteArray = ByteArray(0)) {
    val op: String get() = str("op").orEmpty()

    fun str(key: String): String? = (header[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun long(key: String): Long = (header[key] as? JsonPrimitive)?.longOrNull ?: 0L

    fun encode(): ByteArray = ByteArrayOutputStream().apply {
        write(header.toString().toByteArray(Charsets.UTF_8))
        write(NEWLINE.toInt())
        write(body)
    }.toByteArray()

    companion object {
        private const val NEWLINE: Byte = 0x0A

        fun decode(plain: ByteArray): ClipMessage? = runCatching {
            val nl = plain.indexOf(NEWLINE)
            if (nl <= 0) return null
            val header = Json.parseToJsonElement(String(plain, 0, nl, Charsets.UTF_8)) as? JsonObject ?: return null
            ClipMessage(header, plain.copyOfRange(nl + 1, plain.size))
        }.getOrNull()

        /** 多条内容打包（从旧到新）：每条 = 4 字节大端长度 + 该条 encode() 的字节；与 Windows 端一致 */
        fun encodeBatch(items: List<ClipMessage>): ByteArray = ByteArrayOutputStream().apply {
            for (it in items) {
                val b = it.encode()
                write(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
                write(b)
            }
        }.toByteArray()

        fun decodeBatch(body: ByteArray): List<ClipMessage> {
            val out = mutableListOf<ClipMessage>()
            var off = 0
            while (off + 4 <= body.size) {
                val n = ((body[off].toInt() and 0xff) shl 24) or ((body[off + 1].toInt() and 0xff) shl 16) or
                    ((body[off + 2].toInt() and 0xff) shl 8) or (body[off + 3].toInt() and 0xff)
                off += 4
                if (n <= 0 || off + n > body.size) break
                decode(body.copyOfRange(off, off + n))?.let { out += it }
                off += n
            }
            return out
        }

        fun of(op: String, vararg pairs: Pair<String, Any?>, body: ByteArray = ByteArray(0)) =
            ClipMessage(buildJsonObject {
                put("op", op)
                pairs.forEach { (k, v) ->
                    when (v) {
                        null -> {}
                        is String -> put(k, v)
                        is Number -> put(k, v)
                        is Boolean -> put(k, v)
                        else -> put(k, v.toString())
                    }
                }
            }, body)
    }
}
