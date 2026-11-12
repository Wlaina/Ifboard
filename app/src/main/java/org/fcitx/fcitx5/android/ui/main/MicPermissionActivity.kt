/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: an input method cannot ask for runtime permissions itself, so voice typing opens this
 * invisible activity to request the microphone permission (Gboard does the same).
 */
package org.fcitx.fcitx5.android.ui.main

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

class MicPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            finish()
            return
        }
        if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "已允许麦克风，回到输入框再点一次麦克风即可语音输入", Toast.LENGTH_LONG).show()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            // denied permanently: the system dialog will not show again, open the app's settings page
            Toast.makeText(this, "请在「权限」里允许 Ifboard 使用麦克风", Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        finish()
    }

    companion object {
        fun launch(context: android.content.Context) {
            org.fcitx.fcitx5.android.ifboard.BackgroundStart.startFromIme(
                context,
                Intent(context, MicPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }
}
