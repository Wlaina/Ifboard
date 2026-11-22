/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.ifboard.clipsync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class ClipSyncCryptoTest {

    // 由 Windows 端 IfboardWin.Services.ClipSyncKeys / ClipSyncCrypto 生成（iterations = 1000）
    private val keys = ClipSyncKeys.derive(" Test@Example.com ", "pässwörd-测试", 1000)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    @Test
    fun deriveMatchesWindows() {
        assertEquals("C8B735679F8C037B8BCFD65495C1E2D7C7C3BBEAB12F7AD8F1C93D567DECF002", hex(keys.enc))
        assertEquals("6438CB613558FFE8C8EF540131C3A1D9470854C3063A66391DE0E858AC935DB7", hex(keys.disc))
        assertEquals("65e3fcf5", keys.fingerprint)
    }

    @Test
    fun opensWindowsSealedMessage() {
        val sealed = Base64.getDecoder().decode(
            "W1ENIsePmSTdM21KBKvwngdbOQrxDx7So/sF/nJojkfa26ITLB5G/x0hHeOiqny+dDyjGHW5OamnWnfVBekjHeGJE2I1SC6j+plpuh1/9FU2OQVs7KYV"
        )
        val msg = ClipMessage.decode(ClipSyncCrypto.open(keys.enc, sealed)!!)!!
        assertEquals("clip", msg.op)
        assertEquals("abc123", msg.str("id"))
        assertEquals("text/plain", msg.str("mime"))
        assertEquals("你好 clip", String(msg.body, Charsets.UTF_8))
    }

    @Test
    fun roundTripAndTamper() {
        val body = ByteArray(5000) { (it % 251).toByte() }
        val msg = ClipMessage.of("clip", "id" to "x", "ts" to 42L, body = body)
        val sealed = ClipSyncCrypto.seal(keys.enc, msg.encode())
        val back = ClipMessage.decode(ClipSyncCrypto.open(keys.enc, sealed)!!)!!
        assertEquals(42L, back.long("ts"))
        assertArrayEquals(body, back.body)
        // 篡改任意一个字节都必须解密失败
        sealed[sealed.size / 2] = (sealed[sealed.size / 2].toInt() xor 1).toByte()
        assertNull(ClipSyncCrypto.open(keys.enc, sealed))
        // 另一把钥匙也打不开
        val other = ClipSyncKeys.derive("test@example.com", "wrong", 1000)
        assertNull(ClipSyncCrypto.open(other.enc, ClipSyncCrypto.seal(keys.enc, msg.encode())))
    }
}
