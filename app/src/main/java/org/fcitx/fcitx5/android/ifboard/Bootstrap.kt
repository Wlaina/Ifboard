/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard first-run import: copies the bundled personal setup (assets folder "ifboard")
 * into the external files dir, without touching files the user already has.
 *   ifboard/rime   -> files/data/rime   (Rime user dir: patches, phrases, sync snapshots)
 *   ifboard/config -> files/config      (fcitx profile, global config, addon conf)
 */
package org.fcitx.fcitx5.android.ifboard

import android.content.Context
import timber.log.Timber
import java.io.File

object Bootstrap {
    private const val ROOT = "ifboard"
    private const val MARKER = ".ifboard_imported"

    fun run(ctx: Context) {
        val ext = ctx.getExternalFilesDir(null) ?: return
        ensureSharedLunarDb(ctx, ext)
        val marker = File(ext, MARKER)
        val stamp = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString() }
            .getOrDefault("0")
        if (marker.exists() && marker.readText() == stamp) return
        runCatching {
            copyTree(ctx, ROOT + "/rime", File(ext, "data/rime"))
            copyTree(ctx, ROOT + "/config", File(ext, "config"))
            ensureRimeCorrection(File(ext, "data/rime"))
            // readable Rime device id for dictionary sync, e.g. "Android-houji"
            val inst = File(ext, "data/rime/installation.yaml")
            if (!inst.exists()) {
                inst.parentFile?.mkdirs()
                inst.writeText("installation_id: \"Android-${android.os.Build.DEVICE}\"\n")
            }
            marker.writeText(stamp)
        }.onFailure { Timber.w(it, "Ifboard bootstrap failed") }
    }

    private const val CORRECTION_MARKER = ".ifboard_correction_v1"
    private const val CORRECTION_LINE = "  translator/enable_correction: true"

    /**
     * 打开 librime 自带的邻键纠错（NearSearchCorrector，librime ≥ 1.9）：整句输入时每个音节都允许
     * 一次相邻键替换，纠错候选由 Rime 自己的词库与语法模型排序。只在每台设备上补一次，
     * 之后用户手动删掉这行也不会被加回来。
     */
    internal fun ensureRimeCorrection(rimeDir: File) {
        val marker = File(rimeDir, CORRECTION_MARKER)
        if (marker.exists()) return
        runCatching {
            rimeDir.mkdirs()
            for (schema in listOf("rime_ice", "double_pinyin_flypy")) {
                val f = File(rimeDir, "$schema.custom.yaml")
                if (!f.exists()) {
                    f.writeText("patch:\n$CORRECTION_LINE\n")
                    continue
                }
                val text = f.readText()
                if (text.contains("enable_correction")) continue
                // 只处理常规的「patch:」顶层写法，其他结构不去碰
                if (!Regex("(?m)^patch:\\s*$").containsMatchIn(text)) continue
                f.appendText((if (text.endsWith("\n")) "" else "\n") + CORRECTION_LINE + "\n")
            }
            marker.writeText("1")
        }.onFailure { Timber.w(it, "enable Rime correction failed") }
    }

    // lunar.lua uses ReverseDb("lua/lunar.db"), which librime resolves inside Rime's user data dir
    private fun ensureSharedLunarDb(ctx: Context, ext: File) {
        val target = File(ext, "data/rime/lua/lunar.db")
        if (target.isFile && target.length() > 0) return
        runCatching {
            target.parentFile?.mkdirs()
            ctx.assets.open("usr/share/rime-data/lua/lunar.db").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }.onFailure { Timber.w(it, "Copy lunar.db failed") }
    }

    private fun copyTree(ctx: Context, assetPath: String, dest: File) {
        val children = ctx.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            // a file
            if (dest.exists()) return
            dest.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        children.forEach { copyTree(ctx, "$assetPath/$it", File(dest, it)) }
    }
}
