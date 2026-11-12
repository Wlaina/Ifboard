/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: vivo / iQOO and Xiaomi block "pop-up UI from background" for newly installed apps.
 * An input method is treated as background there, so every page opened from the keyboard
 * (settings, menu entries, clipboard editor, microphone permission) silently failed.
 * Gboard is exempt as a system app. This helper
 *  - tells whether the permission is granted (when the ROM lets us find out),
 *  - explains it in the keyboard when a launch is blocked,
 *  - opens the vendor's permission page so the user can turn it on once.
 */
package org.fcitx.fcitx5.android.ifboard

import android.app.Activity
import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast

object BackgroundStart {

    enum class Vendor { Vivo, Xiaomi, Other }

    val vendor: Vendor by lazy {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        when {
            "vivo" in m || "iqoo" in m -> Vendor.Vivo
            "xiaomi" in m || "redmi" in m || "poco" in m -> Vendor.Xiaomi
            else -> Vendor.Other
        }
    }

    @Volatile
    private var lastResumed = 0L

    private const val PREFS = "ifboard_bg_start"
    private const val KEY_BLOCKED = "blocked_seen"
    private const val KEY_SNOOZE = "snoozed_version"

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                lastResumed = SystemClock.uptimeMillis()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** true / false when the ROM tells us, null when unknown (or not a restricting ROM: true) */
    fun isAllowed(ctx: Context): Boolean? = when (vendor) {
        Vendor.Vivo -> runCatching {
            ctx.contentResolver.query(
                Uri.parse("content://com.vivo.permissionmanager.provider.permission/start_bg_activity"),
                null, "pkgname=?", arrayOf(ctx.packageName), null
            )?.use { c ->
                if (!c.moveToFirst()) null
                else c.getInt(c.getColumnIndexOrThrow("currentstate")) == 0
            }
        }.getOrNull() ?: blockedFallback(ctx)
        Vendor.Xiaomi -> runCatching {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            val m = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
            )
            // 10021 = OP_BACKGROUND_START_ACTIVITY on MIUI / HyperOS
            (m.invoke(ops, 10021, Process.myUid(), ctx.packageName) as Int) == AppOpsManager.MODE_ALLOWED
        }.getOrNull() ?: blockedFallback(ctx)
        Vendor.Other -> true
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** without a direct answer, rely on whether a launch from the keyboard was seen to fail */
    private fun blockedFallback(ctx: Context): Boolean? =
        if (prefs(ctx).getBoolean(KEY_BLOCKED, false)) false else null

    private val hint: String
        get() = when (vendor) {
            Vendor.Vivo -> "vivo 系统拦截了从键盘打开页面。请打开 Ifboard 应用，按提示允许「后台弹出界面」"
            Vendor.Xiaomi -> "小米系统拦截了从键盘打开页面。请打开 Ifboard 应用，按提示允许「后台弹出界面」"
            Vendor.Other -> "系统拦截了从键盘打开页面"
        }

    /**
     * Start [intent] from the keyboard. Restricting ROMs drop the launch without any error,
     * so when one of our own pages did not come up, explain why instead of doing nothing.
     */
    fun startFromIme(ctx: Context, intent: Intent, ownPage: Boolean = true) {
        if (ctx is Activity || vendor == Vendor.Other) {
            ctx.startActivity(intent)
            return
        }
        val known = isAllowed(ctx)
        val t = SystemClock.uptimeMillis()
        runCatching { ctx.startActivity(intent) }
        if (known == false) {
            Toast.makeText(ctx, hint, Toast.LENGTH_LONG).show()
            return
        }
        if (!ownPage) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (lastResumed < t) {
                prefs(ctx).edit().putBoolean(KEY_BLOCKED, true).apply()
                Toast.makeText(ctx, hint, Toast.LENGTH_LONG).show()
            } else if (known == null) {
                // it worked: the permission has been granted meanwhile
                prefs(ctx).edit().putBoolean(KEY_BLOCKED, false).apply()
            }
        }, 1200)
    }

    /** whether the app should ask for the permission now (once per version unless it keeps failing) */
    fun shouldPrompt(ctx: Context, version: String): Boolean {
        if (vendor == Vendor.Other || isAllowed(ctx) != false) return false
        return prefs(ctx).getString(KEY_SNOOZE, null) != version
    }

    fun snooze(ctx: Context, version: String) = prefs(ctx).edit().putString(KEY_SNOOZE, version).apply()

    /** open the vendor page where "后台弹出界面" can be turned on */
    fun openPermissionPage(activity: Activity) {
        val pkg = activity.packageName
        val candidates = when (vendor) {
            Vendor.Vivo -> listOf(
                Intent("permission.intent.action.softPermissionDetail").putExtra("packagename", pkg),
                Intent("secure.intent.action.softPermissionDetail").putExtra("packagename", pkg)
            )
            Vendor.Xiaomi -> listOf(
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", pkg),
                Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg)
            )
            Vendor.Other -> emptyList()
        } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
        for (i in candidates) {
            if (runCatching { activity.startActivity(i) }.isSuccess) {
                prefs(activity).edit().putBoolean(KEY_BLOCKED, false).apply()
                return
            }
        }
    }
}
