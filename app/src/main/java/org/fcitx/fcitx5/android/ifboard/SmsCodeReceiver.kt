/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: 监听系统短信广播，提取验证码并写入剪贴板
 */
package org.fcitx.fcitx5.android.ifboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import timber.log.Timber

class SmsCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!SmsCodeHelper.isEnabled(context)) return
        val messages = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        }.onFailure { Timber.w(it, "getMessagesFromIntent failed") }.getOrNull()
        if (messages.isNullOrEmpty()) return
        val fullBody = buildString {
            for (msg in messages) {
                msg?.displayMessageBody?.let { append(it) }
            }
        }
        val ts = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        SmsCodeHelper.handleIncomingSms(context.applicationContext, fullBody, ts)
    }
}
