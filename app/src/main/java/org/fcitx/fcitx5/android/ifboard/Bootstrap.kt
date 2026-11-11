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
        val marker = File(ext, MARKER)
        val stamp = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString() }
            .getOrDefault("0")
        if (marker.exists() && marker.readText() == stamp) return
        runCatching {
            copyTree(ctx, ROOT + "/rime", File(ext, "data/rime"))
            copyTree(ctx, ROOT + "/config", File(ext, "config"))
            // readable Rime device id for dictionary sync, e.g. "Android-houji"
            val inst = File(ext, "data/rime/installation.yaml")
            if (!inst.exists()) {
                inst.parentFile?.mkdirs()
                inst.writeText("installation_id: \"Android-${android.os.Build.DEVICE}\"\n")
            }
            marker.writeText(stamp)
        }.onFailure { Timber.w(it, "Ifboard bootstrap failed") }
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
