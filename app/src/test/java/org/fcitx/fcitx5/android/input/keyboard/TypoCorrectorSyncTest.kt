/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: 触摸习惯学习补强与 WebDAV 云同步合并逻辑单测
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TypoCorrectorSyncTest {

    private val dataDir = File("src/main/assets/usr/share/rime-data")
    private val g = KeyboardGeometryForTest.qwerty()

    @Before
    fun setUp() {
        TypoCorrector.setModeForTest(GlideTyping.Mode.Flypy)
        TypoCorrector.clear()
        TypoCorrector.resetOffsets()
    }

    private fun tap(ch: Char, dx: Float = 0f, dy: Float = 0f) {
        val k = ch - 'a'
        TypoCorrector.recordTap(ch, g.cx[k] + dx * g.keyW, g.cy[k] + dy * g.keyH, g)
    }

    @Test
    fun serialize_and_parse_round_trip() {
        val x = FloatArray(26).apply { this['g' - 'a'] = 0.145f; this['u' - 'a'] = -0.092f }
        val y = FloatArray(26).apply { this['g' - 'a'] = -0.05f }
        val src = TypoCorrector.SyncPayload(1, 1763150000000L, 12345L, 42, x, y)
        val json = TypoCorrector.serializePayload(src)
        val parsed = TypoCorrector.parsePayload(json)
        assertNotNull(parsed)
        assertEquals(42, parsed!!.samples)
        assertEquals(1763150000000L, parsed.updatedAt)
        assertEquals(12345L, parsed.resetAt)
        assertEquals(0.145f, parsed.offX['g' - 'a'], 0.001f)
        assertEquals(-0.092f, parsed.offX['u' - 'a'], 0.001f)
        assertEquals(-0.05f, parsed.offY['g' - 'a'], 0.001f)
    }

    @Test
    fun merge_restores_cloud_when_local_empty_and_respects_local_reset() {
        val xCloud = FloatArray(26).apply { this[0] = 0.12f }
        val remote = TypoCorrector.SyncPayload(1, 2000L, 0L, 60, xCloud, FloatArray(26))

        // 1. 新设备（本地 0 样本且无重置）：完整拉取云端
        val freshLocal = TypoCorrector.SyncPayload(1, 0L, 0L, 0, FloatArray(26), FloatArray(26))
        val resPull = TypoCorrector.mergePayloads(freshLocal, remote, 0L)
        assertEquals(TypoCorrector.SyncMergeOutcome.UPDATED_LOCAL, resPull.outcome)
        assertEquals(60, resPull.merged.samples)
        assertEquals(0.12f, resPull.merged.offX[0], 0.001f)

        // 2. 本机在远端更新之后刚点过“重置”（resetAt = 3000 > remote.updatedAt = 2000）
        val resetLocal = TypoCorrector.SyncPayload(1, 3000L, 3000L, 0, FloatArray(26), FloatArray(26))
        val resReset = TypoCorrector.mergePayloads(resetLocal, remote, 2000L)
        assertEquals(TypoCorrector.SyncMergeOutcome.PUSH_LOCAL, resReset.outcome)
        assertEquals(0, resReset.merged.samples)
        assertTrue(resReset.pushNeeded)
    }

    @Test
    fun merge_blends_independently_updated_devices() {
        val xLocal = FloatArray(26).apply { this[0] = 0.20f }
        val xRemote = FloatArray(26).apply { this[0] = 0.00f }
        val local = TypoCorrector.SyncPayload(1, 5000L, 0L, 50, xLocal, FloatArray(26))
        val remote = TypoCorrector.SyncPayload(1, 6000L, 0L, 50, xRemote, FloatArray(26))
        // lastSyncedRemoteAt = 1000L 表示双方自上次同步后均各自有新学习
        val res = TypoCorrector.mergePayloads(local, remote, 1000L)
        assertEquals(TypoCorrector.SyncMergeOutcome.UPDATED_LOCAL, res.outcome)
        assertTrue(res.pushNeeded)
        assertEquals(0.10f, res.merged.offX[0], 0.002f)
    }

    @Test
    fun literal_commit_learns_longer_sentences_over_8_taps() {
        assumeTrue(File(dataDir, "cn_dicts/base.dict.yaml").exists())
        if (!GlideTyping.isReady(GlideTyping.Mode.Flypy)) {
            GlideTyping.loadBlocking(GlideTyping.Mode.Flypy) { File(dataDir, it).inputStream() }
        }
        // 5 个字 = 10 个键，全部合法音节（如 ni ha wu ai ni），偏移在允许范围内应触发学习
        val chars = "nihawuaini"
        for (c in chars) tap(c, dx = 0.25f)
        TypoCorrector.onLiteralCommitted()
        val firstOffX = TypoCorrector.dumpOffsets().split(',').take(26).map { it.toFloat() }
        assertTrue("10键长句直接上屏应当更新触摸习惯偏移", firstOffX.any { it > 0.001f })
    }

    @Test
    fun engine_candidate_selected_learns_true_code_under_enable_correction() {
        assumeTrue(File(dataDir, "cn_dicts/base.dict.yaml").exists())
        if (!GlideTyping.isReady(GlideTyping.Mode.Flypy)) {
            GlideTyping.loadBlocking(GlideTyping.Mode.Flypy) { File(dataDir, it).inputStream() }
        }
        // 打错成非法音节 "gige"（"u" 偏向右按成了 "i"），Rime enable_correction 返回「谷歌」并被选中
        tap('g'); tap('i', dx = -0.35f); tap('g'); tap('e')
        TypoCorrector.onEngineCandidateSelected("谷歌")
        val offXList = TypoCorrector.dumpOffsets().split(',').take(26).map { it.toFloat() }
        // 应当学在真实编码 'u' 上（'u' 在 QWERTY 位于 'i' 左侧，但 tap 落在 'i' 左侧意味着用户点得比 u 中心偏右）
        assertTrue("应把落点习惯计入真实编码 u 而不是误触键 i", kotlin.math.abs(offXList['u' - 'a']) > 0.005f)
        assertEquals(0f, offXList['i' - 'a'], 0.0001f)
    }
}
