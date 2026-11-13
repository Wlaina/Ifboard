package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.input.clipboard.ClipboardAdapter
import org.fcitx.fcitx5.android.input.clipboard.ClipboardImages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardTest {

    @Test
    fun excerptTextPreservesLineBreaksWhenMasked() {
        assertEquals("•••\n•••", ClipboardAdapter.excerptText("abc\ndef", mask = true))
        assertEquals("abc\ndef", ClipboardAdapter.excerptText("abc\ndef", mask = false))
    }

    @Test
    fun selectOutdatedIdsKeepsMostRecentByTimestamp() {
        // 模拟重复复制导致旧 id 拥有更新时间戳的场景
        val entries = listOf(
            ClipboardEntry(id = 1, text = "recopied-newest", timestamp = 300L),
            ClipboardEntry(id = 2, text = "oldest", timestamp = 100L),
            ClipboardEntry(id = 3, text = "middle", timestamp = 200L)
        )
        assertArrayEquals(intArrayOf(2), ClipboardEntry.selectOutdatedIds(entries, limit = 2))
        assertArrayEquals(intArrayOf(1, 2, 3), ClipboardEntry.selectOutdatedIds(entries, limit = 0))
    }

    @Test
    fun dismissOneStateFiltersOnlyTargetAndPreservesUndo() {
        val initial = ClipboardImages.DismissState(clearedAt = 100L, clearedUri = null)
        val clipUri = "content://clipboard/image/1"
        val shotNewer = "content://media/external/images/media/10"
        val shotOlder = "content://media/external/images/media/9"

        // 单删其中一张截图，其余截图和剪贴板图片仍可见
        val afterDeleteShot = ClipboardImages.computeDismissOneState(initial, shotNewer)
        assertTrue(ClipboardImages.shouldFilterImage(shotNewer, 300L, isFromClipboard = false, afterDeleteShot))
        assertFalse(ClipboardImages.shouldFilterImage(shotOlder, 200L, isFromClipboard = false, afterDeleteShot))
        assertFalse(ClipboardImages.shouldFilterImage(clipUri, 400L, isFromClipboard = true, afterDeleteShot))

        // 再单删系统剪贴板图片，两张被删均被过滤，剩余旧截图仍保留
        val afterDeleteClip = ClipboardImages.computeDismissOneState(afterDeleteShot, clipUri)
        assertTrue(ClipboardImages.shouldFilterImage(clipUri, 400L, isFromClipboard = true, afterDeleteClip))
        assertTrue(ClipboardImages.shouldFilterImage(shotNewer, 300L, isFromClipboard = false, afterDeleteClip))
        assertFalse(ClipboardImages.shouldFilterImage(shotOlder, 200L, isFromClipboard = false, afterDeleteClip))

        // 恢复 initial 状态后全部可见
        assertFalse(ClipboardImages.shouldFilterImage(shotNewer, 300L, isFromClipboard = false, initial))
        assertFalse(ClipboardImages.shouldFilterImage(clipUri, 400L, isFromClipboard = true, initial))
    }

    @Test
    fun dismissAllStateHidesAllDisplayedAndCompactsOldScreenshotUris() {
        val clip1 = "content://clipboard/image/1"
        val clip2 = "content://clipboard/image/2"
        val shotOld = "content://media/external/images/media/8"
        val shotRecent = "content://media/external/images/media/11"

        val beforeAll = ClipboardImages.DismissState(
            clearedAt = 100L,
            clearedUri = null,
            dismissedUris = setOf(shotOld)
        )
        val displayed = listOf(
            ClipboardImages.DismissImageMeta(clip1, fromClipboard = true, timestamp = 500L),
            ClipboardImages.DismissImageMeta(clip2, fromClipboard = true, timestamp = 500L),
            ClipboardImages.DismissImageMeta(shotRecent, fromClipboard = false, timestamp = 450L)
        )

        val cleared = ClipboardImages.computeDismissAllState(beforeAll, displayed, nowMs = 600L)
        assertEquals(600L, cleared.clearedAt)
        assertEquals(setOf(clip1, clip2), cleared.dismissedUris)
        assertTrue(ClipboardImages.shouldFilterImage(clip1, 650L, isFromClipboard = true, cleared))
        assertTrue(ClipboardImages.shouldFilterImage(clip2, 650L, isFromClipboard = true, cleared))
        assertTrue(ClipboardImages.shouldFilterImage(shotRecent, 450L, isFromClipboard = false, cleared))
        assertTrue(ClipboardImages.shouldFilterImage(shotOld, 200L, isFromClipboard = false, cleared))

        // 新拍的截图时间戳 > clearedAt 不被屏蔽，新复制的其他剪贴板图片亦不被屏蔽
        assertFalse(
            ClipboardImages.shouldFilterImage(
                "content://media/external/images/media/12",
                timestamp = 601L,
                isFromClipboard = false,
                cleared
            )
        )
        assertFalse(
            ClipboardImages.shouldFilterImage(
                "content://clipboard/image/3",
                timestamp = 650L,
                isFromClipboard = true,
                cleared
            )
        )
    }
}
