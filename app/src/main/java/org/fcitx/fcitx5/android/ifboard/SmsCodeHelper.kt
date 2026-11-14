/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: 短信验证码提取、自动写入剪贴板与工具栏快捷粘贴管理
 */
package org.fcitx.fcitx5.android.ifboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.utils.WeakHashSet
import timber.log.Timber

object SmsCodeHelper {

    private const val PREFS = "ifb_sms_otp"
    private const val KEY_ENABLED = "otp_auto_copy_enabled"
    private const val KEY_LAST_CODE = "last_code"
    private const val KEY_LAST_TS = "last_ts"
    private const val KEY_LAST_SIG = "last_sig"
    private const val KEY_CONSUMED = "last_consumed"
    private const val KEY_CONSUMED_AT = "last_consumed_at"

    const val OTP_VALID_MS = 120_000L

    data class OtpEntry(val code: String, val timestamp: Long)

    fun interface OnOtpListener {
        fun onOtpDetected(entry: OtpEntry)
    }

    private val listeners = WeakHashSet<OnOtpListener>()

    fun addListener(l: OnOtpListener) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: OnOtpListener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun hasSmsPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasReadSmsPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private val KEYWORD_REGEX = Regex(
        """(验证码|校验码|动态码|确认码|安全码|登录码|注册码|授权码|激活码|动态密码|一次性密码|一次性校验码|verification\s*code|security\s*code|passcode|login\s*code|pin\s*code|one[-\s]*time\s*(?:password|code)|\botp\b|\bcode\b)""",
        RegexOption.IGNORE_CASE
    )

    // 紧邻关键词出现的 4~8 位码（含字母数字组合），排除紧接小数点、金额、电话等常见干扰
    private val NEAR_KEYWORD_REGEX = Regex(
        """(?:验证码|校验码|动态码|确认码|安全码|登录码|注册码|授权码|激活码|动态密码|一次性密码|一次性校验码|verification\s*code|security\s*code|passcode|login\s*code|pin\s*code|\botp\b|\bcode\b)\s*(?:为|是|is|:|：|】)?\s*[\[【(（]?\s*([A-Za-z0-9]{4,8})(?![A-Za-z0-9%]|\.\d)""",
        RegexOption.IGNORE_CASE
    )

    private val DIGIT_TOKEN_REGEX = Regex("""(?<![\d.\-/])(\d{4,8})(?![\d.\-/元角分%年])""")

    /** 从短信正文中提取验证码；非验证码类短信返回 null */
    fun extractCode(body: String): String? {
        if (body.isBlank()) return null
        val kwMatch = KEYWORD_REGEX.find(body) ?: return null

        // 1. 优先匹配紧跟在验证码关键词后面的 4-8 位编码（至少含一个数字，避免误吞英文单词如 "please"）
        NEAR_KEYWORD_REGEX.find(body)?.groupValues?.getOrNull(1)?.let { candidate ->
            if (candidate.any { it.isDigit() } && !isLikelyPhoneOrDate(body, candidate)) {
                return candidate
            }
        }

        // 2. 兜底：在整条短信里找最像验证码的独立数字串，按离关键词距离 + 长度偏好(6/4位优先)排序
        val kwCenter = (kwMatch.range.first + kwMatch.range.last) / 2
        val candidates = DIGIT_TOKEN_REGEX.findAll(body)
            .map { it.groupValues[1] to it.range.first }
            .filter { (token, idx) ->
                // 排除明显年份（如 2026）除非无其他候选且距关键词很近
                val after = body.getOrNull(idx + token.length)
                after != '年' && after != '月' && after != '日' && !isLikelyPhoneOrDate(body, token)
            }
            .toList()
        if (candidates.isEmpty()) return null

        return candidates.minByOrNull { (token, idx) ->
            val dist = kotlin.math.abs(idx - kwCenter)
            val lenPenalty = when (token.length) {
                6 -> 0
                4 -> 8
                5, 8 -> 18
                else -> 30
            }
            dist + lenPenalty
        }?.first
    }

    private fun isLikelyPhoneOrDate(body: String, token: String): Boolean {
        if (token.length == 4 && token.startsWith("20") && body.contains("${token}年")) return true
        // 排除 106/100 开头且后接“详询/客服/致电”的客服号
        if (token.startsWith("100") || token.startsWith("95")) {
            val idx = body.indexOf(token)
            val prefix = if (idx > 0) body.substring(maxOf(0, idx - 6), idx) else ""
            if (prefix.contains("致电") || prefix.contains("客服") || prefix.contains("拨打")) return true
        }
        return false
    }

    /** 处理收到的短信正文，提取成功后自动写入剪贴板并通知监听者 */
    fun handleIncomingSms(ctx: Context, body: String, msgTimestampMs: Long = System.currentTimeMillis()): String? {
        if (!isEnabled(ctx)) return null
        val code = extractCode(body) ?: return null
        val now = System.currentTimeMillis()
        val sig = "${msgTimestampMs / 1000L}:${code}:${body.hashCode()}"
        val p = prefs(ctx)
        val lastSig = p.getString(KEY_LAST_SIG, null)
        val lastCode = p.getString(KEY_LAST_CODE, null)
        val lastTs = p.getLong(KEY_LAST_TS, 0L)
        if (sig == lastSig || (code == lastCode && now - lastTs < 15_000L)) {
            return null
        }

        p.edit()
            .putString(KEY_LAST_CODE, code)
            .putLong(KEY_LAST_TS, now)
            .putString(KEY_LAST_SIG, sig)
            .putBoolean(KEY_CONSUMED, false)
            .apply()

        // 写入系统剪贴板（ClipboardManager 监听器也会记录到剪贴板库）
        ClipboardManager.copyPlainText("验证码", code)

        val entry = OtpEntry(code, now)
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach {
            runCatching { it.onOtpDetected(entry) }.onFailure { t -> Timber.w(t) }
        }
        return code
    }

    /** 检查系统收件箱最近一条短信（需 READ_SMS 权限；用于广播被系统省电延迟时的兜底同步） */
    fun queryLatestInboxOtp(ctx: Context, maxAgeMs: Long = 90_000L): String? {
        if (!isEnabled(ctx) || !hasReadSmsPermission(ctx)) return null
        val since = System.currentTimeMillis() - maxAgeMs
        return runCatching {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                Telephony.Sms.Inbox.CONTENT_URI
            } else {
                Uri.parse("content://sms/inbox")
            }
            ctx.contentResolver.query(
                uri,
                arrayOf("body", "date"),
                "date >= ?",
                arrayOf(since.toString()),
                "date DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    val body = c.getString(0).orEmpty()
                    val date = c.getLong(1)
                    handleIncomingSms(ctx, body, date)
                } else null
            }
        }.onFailure { Timber.w(it, "queryLatestInboxOtp failed") }.getOrNull()
    }

    /** 返回当前未过期的待粘贴验证码（120s 内且未消费） */
    fun activePendingOtp(ctx: Context): OtpEntry? {
        if (!isEnabled(ctx)) return null
        val p = prefs(ctx)
        if (p.getBoolean(KEY_CONSUMED, true)) return null
        val code = p.getString(KEY_LAST_CODE, null) ?: return null
        val ts = p.getLong(KEY_LAST_TS, 0L)
        if (System.currentTimeMillis() - ts > OTP_VALID_MS) return null
        return OtpEntry(code, ts)
    }

    fun markConsumed(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean(KEY_CONSUMED, true)
            .putLong(KEY_CONSUMED_AT, System.currentTimeMillis())
            .apply()
    }

    fun wasRecentlyConsumed(ctx: Context, code: String, withinMs: Long = 10_000L): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_CONSUMED, false)) return false
        val lastCode = p.getString(KEY_LAST_CODE, null) ?: return false
        val consumedAt = p.getLong(KEY_CONSUMED_AT, 0L)
        return code.trim() == lastCode && (System.currentTimeMillis() - consumedAt) <= withinMs
    }
}
