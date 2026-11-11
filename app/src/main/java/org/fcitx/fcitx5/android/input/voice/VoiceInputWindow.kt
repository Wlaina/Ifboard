/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Gboard-style in-keyboard voice typing, backed by the system SpeechRecognizer
 * (Google speech services), so no separate voice IME needs to be enabled.
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import timber.log.Timber
import kotlin.math.min

class VoiceInputWindow : InputWindow.ExtendedInputWindow<VoiceInputWindow>() {

    companion object {
        fun isAvailable(service: FcitxInputMethodService) =
            SpeechRecognizer.isRecognitionAvailable(service)
    }

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val fcitx by manager.fcitx()

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var active = false

    private val language: String
        get() = runCatching {
            val ime = fcitx.runImmediately { inputMethodEntryCached }
            if (ime.uniqueName.startsWith("keyboard-")) "en-US" else "zh-CN"
        }.getOrDefault("zh-CN")

    private val statusText by lazy {
        TextView(context).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            maxLines = 3
            setTextColor(theme.keyTextColor)
        }
    }

    private val hintText by lazy {
        TextView(context).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(theme.candidateCommentColor)
        }
    }

    private val micButton by lazy {
        ImageView(context).apply {
            setImageResource(R.drawable.ic_gb_mic)
            imageTintList = ColorStateList.valueOf(theme.accentKeyTextColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.accentKeyBackgroundColor)
            }
            val p = context.dp(22)
            setPadding(p, p, p, p)
            contentDescription = context.getString(R.string.switch_to_voice_input)
            setOnClickListener { if (listening) stopListening(true) else startListening() }
        }
    }

    private val root by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val m = context.dp(16)
            setPadding(m, m, m, m)
            addView(statusText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = m })
            addView(micButton, LinearLayout.LayoutParams(context.dp(84), context.dp(84)))
            addView(hintText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m })
        }
    }

    override val title: String get() = "语音输入"

    override fun onCreateView(): View = root

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            statusText.text = if (language == "en-US") "Speak now" else "请说话"
            hintText.text = "点按麦克风结束"
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            val s = 1f + min(maxOf(rmsdB, 0f), 10f) / 30f
            micButton.scaleX = s
            micButton.scaleY = s
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            micButton.scaleX = 1f
            micButton.scaleY = 1f
        }

        override fun onError(error: Int) {
            Timber.d("Speech error $error")
            listening = false
            micButton.scaleX = 1f
            micButton.scaleY = 1f
            service.currentInputConnection?.finishComposingText()
            statusText.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络不可用"
                else -> "语音识别暂不可用"
            }
            hintText.text = "点按麦克风重试"
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            val ic = service.currentInputConnection
            if (text.isNotEmpty()) ic?.commitText(text, 1) else ic?.finishComposingText()
            statusText.text = text
            // continuous dictation like Gboard: keep listening until the user taps the mic
            if (active) handler.postDelayed({ if (active) startListening() }, 250)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotEmpty()) {
                service.currentInputConnection?.setComposingText(text, 1)
                statusText.text = text
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            statusText.text = "需要麦克风权限"
            hintText.text = "请在系统设置中允许小企鹅输入法使用麦克风"
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(service).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, service.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        statusText.text = "…"
        listening = true
        r.startListening(intent)
    }

    private fun stopListening(backToKeyboard: Boolean) {
        listening = false
        active = false
        recognizer?.stopListening()
        if (backToKeyboard) windowManager.attachWindow(KeyboardWindow)
    }

    override fun onAttached() {
        active = true
        startListening()
    }

    override fun onDetached() {
        active = false
        listening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        service.currentInputConnection?.finishComposingText()
    }
}
