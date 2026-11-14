/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: 短信验证码提取单测
 */
package org.fcitx.fcitx5.android.ifboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsCodeHelperTest {

    @Test
    fun extracts_chinese_numeric_codes() {
        assertEquals("482910", SmsCodeHelper.extractCode("【Ifboard】您的登录验证码为482910，请勿泄露给他人。"))
        assertEquals("7392", SmsCodeHelper.extractCode("您的动态码是 7392（5分钟内有效），切勿转告他人。"))
        assertEquals("903112", SmsCodeHelper.extractCode("安全校验码：903112，您正在2026年11月14日修改密码。"))
    }

    @Test
    fun extracts_alphanumeric_and_english_codes() {
        assertEquals("A8F29K", SmsCodeHelper.extractCode("Your verification code: A8F29K. Valid for 10 minutes."))
        assertEquals("654321", SmsCodeHelper.extractCode("Use OTP 654321 to sign in. Do not share this code."))
        assertEquals("381044", SmsCodeHelper.extractCode("Ifboard security code is 381044."))
    }

    @Test
    fun ignores_non_otp_messages_and_noise_numbers() {
        assertNull(SmsCodeHelper.extractCode("您的银行卡于2026年11月14日消费1280元，余额9988元。"))
        assertNull(SmsCodeHelper.extractCode("快递已放至驿站，请凭取件码或联系快递员13800138000领取。".replace("取件码", "取件单号")))
        assertNull(SmsCodeHelper.extractCode("客服致电10086咨询业务，感谢您的支持。"))
    }

    @Test
    fun prefers_actual_code_when_service_number_and_year_present() {
        assertEquals(
            "592841",
            SmsCodeHelper.extractCode("【测试平台】2026年11月14日您正在登录，验证码：592841，如有疑问致电10086。")
        )
    }
}
