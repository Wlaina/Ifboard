/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: 平台过滤版更新检查单测
 */
package org.fcitx.fcitx5.android.ifboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IfboardSyncTest {

    @Test
    fun skips_windows_only_release_and_picks_newest_apk_release() {
        val json = """
            [
              {
                "tag_name": "v1.2.8-win",
                "draft": false,
                "prerelease": false,
                "body": "Windows hotfix",
                "html_url": "https://github.com/Wlaina/Ifboard/releases/tag/v1.2.8-win",
                "assets": [
                  { "name": "Ifboard-Windows-Setup-1.2.8.exe", "browser_download_url": "https://example.com/win.exe" }
                ]
              },
              {
                "tag_name": "v1.2.7",
                "draft": false,
                "prerelease": false,
                "body": "Android + Windows sync release",
                "html_url": "https://github.com/Wlaina/Ifboard/releases/tag/v1.2.7",
                "assets": [
                  { "name": "Ifboard-1.2.7-arm64-v8a.apk", "browser_download_url": "https://example.com/app.apk" },
                  { "name": "Ifboard-Windows-Setup-1.2.7.exe", "browser_download_url": "https://example.com/win127.exe" }
                ]
              }
            ]
        """.trimIndent()

        val rel = IfboardSync.parseLatestAndroidRelease(json, "1.2.6")
        assertEquals("1.2.7", rel?.version)
        assertEquals("https://example.com/app.apk", rel?.apkUrl)
    }

    @Test
    fun returns_null_when_already_latest_for_android() {
        val json = """
            [
              {
                "tag_name": "v1.2.8",
                "draft": false,
                "prerelease": true,
                "body": "beta",
                "html_url": "https://example.com/pre",
                "assets": [
                  { "name": "Ifboard-1.2.8.apk", "browser_download_url": "https://example.com/beta.apk" }
                ]
              },
              {
                "tag_name": "v1.2.7",
                "draft": false,
                "prerelease": false,
                "body": "stable",
                "html_url": "https://example.com/127",
                "assets": [
                  { "name": "Ifboard-1.2.7-arm64-v8a.apk", "browser_download_url": "https://example.com/app.apk" }
                ]
              }
            ]
        """.trimIndent()

        assertNull(IfboardSync.parseLatestAndroidRelease(json, "1.2.7"))
    }
}
