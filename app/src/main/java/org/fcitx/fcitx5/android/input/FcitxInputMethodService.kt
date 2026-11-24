/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.LruCache
import android.util.Size
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.forceShowSelf
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.isTypeNull
import org.fcitx.fcitx5.android.utils.monitorCursorAnchor
import org.fcitx.fcitx5.android.utils.styledFloat
import org.fcitx.fcitx5.android.utils.withBatchEdit
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import org.fcitx.fcitx5.android.utils.styledColorCompat
import timber.log.Timber
import kotlin.math.max

class FcitxInputMethodService : LifecycleInputMethodService() {

    private lateinit var fcitx: FcitxConnection

    private var jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private val cachedKeyEvents = LruCache<Int, KeyEvent>(78)
    private var cachedKeyEventIndex = 0

    /**
     * Saves MetaState produced by hardware keyboard with "sticky" modifier keys, to clear them in order.
     * See also [InputConnection#clearMetaKeyStates(int)](https://developer.android.com/reference/android/view/inputmethod/InputConnection#clearMetaKeyStates(int))
     */
    private var lastMetaState: Int = 0

    private lateinit var pkgNameCache: PackageNameCache

    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    private var inputView: InputView? = null
    private var candidatesView: CandidatesView? = null

    private val navbarMgr = NavigationBarManager()
    private val inputDeviceMgr = InputDeviceManager { isVirtualKeyboard ->
        postFcitxJob {
            setCandidatePagingMode(if (isVirtualKeyboard) 0 else 1)
        }
        currentInputConnection?.monitorCursorAnchor(!isVirtualKeyboard)
        if (isVirtualKeyboard) {
            hideStatusIcon()
        } else {
            showStatusIcon(StatusIconMapping.fromEntry(fcitx.runImmediately { inputMethodEntryCached }))
        }
        window.window?.let {
            navbarMgr.evaluate(it, isVirtualKeyboard)
        }
    }

    private var capabilityFlags = CapabilityFlags.DefaultFlags

    private val selection = CursorTracker()

    val currentInputSelection: CursorRange
        get() = selection.latest

    private val composing = CursorRange()
    private var composingText = FormattedText.Empty

    private fun resetComposingState() {
        composing.clear()
        composingText = FormattedText.Empty
    }

    private var cursorUpdateIndex: Int = 0

    private var highlightColor: Int = 0x66008577 // material_deep_teal_500 with alpha 0.4

    private val prefs = AppPrefs.getInstance()
    private val inlineSuggestions by prefs.keyboard.inlineSuggestions
    private val ignoreSystemCursor by prefs.advanced.ignoreSystemCursor

    private val recreateInputViewPrefs: Array<ManagedPreference<*>> = arrayOf(
        prefs.keyboard.expandKeypressArea,
        prefs.advanced.disableAnimation,
        prefs.advanced.ignoreSystemWindowInsets,
    )

    private fun replaceInputView(theme: Theme): InputView {
        val newInputView = InputView(this, fcitx, theme)
        setInputView(newInputView)
        inputDeviceMgr.setInputView(newInputView)
        inputView = newInputView
        return newInputView
    }

    private fun replaceCandidateView(theme: Theme): CandidatesView {
        val newCandidatesView = CandidatesView(this, fcitx, theme)
        // replace CandidatesView manually
        contentView.removeView(candidatesView)
        // put CandidatesView directly under content view
        contentView.addView(newCandidatesView)
        inputDeviceMgr.setCandidatesView(newCandidatesView)
        candidatesView = newCandidatesView
        return newCandidatesView
    }

    private fun replaceInputViews(theme: Theme) {
        navbarMgr.evaluate(window.window!!, inputDeviceMgr.isVirtualKeyboard)
        replaceInputView(theme)
        replaceCandidateView(theme)
    }

    @Keep
    private val recreateInputViewListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        replaceInputView(ThemeManager.activeTheme)
    }

    @Keep
    private val recreateCandidatesViewListener = ManagedPreferenceProvider.OnChangeListener {
        replaceCandidateView(ThemeManager.activeTheme)
    }

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        replaceInputViews(it)
    }

    /**
     * Post a fcitx operation to [jobs] to be executed
     *
     * Unlike `fcitx.runOnReady` or `fcitx.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postFcitxJob] ensures that operations are executed sequentially.
     */
    fun postFcitxJob(block: suspend FcitxAPI.() -> Unit): Job {
        val job = fcitx.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            fcitx.runOnReady(block)
        }
        jobs.trySend(job)
        return job
    }

    /**
     * Ifboard: 键盘开着时锁屏再解锁（例如在微信里），系统有时不会重新下发窗口边距，
     * 键盘就不会再延伸到导航栏后面，底部那条露出透明。亮屏 / 解锁时主动重新请求边距并重设导航栏。
     * 边距本来就正确时不会有任何变化。
     */
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            refreshWindowInsets()
        }
    }

    private fun refreshWindowInsets() {
        window.window?.let { navbarMgr.evaluate(it, inputDeviceMgr.isVirtualKeyboard) }
        val v = inputView ?: return
        v.requestApplyInsets()
        // 解锁动画结束后系统才给出最终边距：稍后再请求一次
        v.postDelayed({ if (v.isAttachedToWindow) v.requestApplyInsets() }, 400)
    }

    override fun onCreate() {
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this, screenStateReceiver,
                android.content.IntentFilter().apply {
                    addAction(android.content.Intent.ACTION_USER_PRESENT)
                    addAction(android.content.Intent.ACTION_SCREEN_ON)
                },
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        fcitx = FcitxDaemon.connect(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            fcitx.runImmediately { eventFlow }.collect {
                handleFcitxEvent(it)
            }
        }
        pkgNameCache = PackageNameCache(this)
        recreateInputViewPrefs.forEach {
            it.registerOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            postFcitxJob {
                SubtypeManager.syncWith(enabledIme())
            }
        }
        super.onCreate()
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        lastKnownConfig = resources.configuration
        registerQuickPasteObservers()
    }

    private var mediaScreenshotObserver: android.database.ContentObserver? = null
    private var smsOtpObserver: android.database.ContentObserver? = null
    private var screenshotDebounceJob: Job? = null

    private fun registerQuickPasteObservers() {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        if (mediaScreenshotObserver == null) {
            val obs = object : android.database.ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    screenshotDebounceJob?.cancel()
                    screenshotDebounceJob = lifecycleScope.launch(Dispatchers.IO) {
                        val before = org.fcitx.fcitx5.android.input.clipboard.ClipboardImages.lastDetectedScreenshot?.uri
                        // MIUI/HyperOS 先写入 IS_PENDING=1 再延后提交，若首次未检测到则短重试两次
                        for (waitMs in longArrayOf(350L, 650L, 1100L)) {
                            delay(waitMs)
                            org.fcitx.fcitx5.android.input.clipboard.ClipboardImages.checkAndNotifyScreenshot(
                                this@FcitxInputMethodService
                            )
                            if (org.fcitx.fcitx5.android.input.clipboard.ClipboardImages.lastDetectedScreenshot?.uri != before) break
                        }
                    }
                }
            }
            val registered = runCatching {
                contentResolver.registerContentObserver(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    obs
                )
                true
            }.getOrDefault(false)
            if (registered) mediaScreenshotObserver = obs
        }
        ensureSmsObserverRegistered()
    }

    private fun ensureSmsObserverRegistered() {
        if (smsOtpObserver != null) return
        if (!org.fcitx.fcitx5.android.ifboard.SmsCodeHelper.hasReadSmsPermission(this)) return
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val obs = object : android.database.ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                lifecycleScope.launch(Dispatchers.IO) {
                    delay(200L)
                    org.fcitx.fcitx5.android.ifboard.SmsCodeHelper.queryLatestInboxOtp(
                        this@FcitxInputMethodService
                    )
                }
            }
        }
        val registered = runCatching {
            contentResolver.registerContentObserver(
                android.net.Uri.parse("content://sms"),
                true,
                obs
            )
            true
        }.getOrDefault(false)
        if (registered) smsOtpObserver = obs
    }

    /** Ifboard: inline tools (translation) can capture what the keyboard types */
    var textInterceptor: org.fcitx.fcitx5.android.input.inline.TextInterceptor? = null

    private fun handleFcitxEvent(event: FcitxEvent<*>) {
        when (event) {
            is FcitxEvent.CommitStringEvent -> {
                commitText(event.data.text, event.data.cursor)
            }
            is FcitxEvent.KeyEvent -> event.data.let event@{
                if (it.states.virtual) {
                    // KeyEvent from virtual keyboard
                    when (it.sym.sym) {
                        FcitxKeyMapping.FcitxKey_BackSpace ->
                            if (textInterceptor?.onBackspace() != true) handleBackspaceKey()
                        FcitxKeyMapping.FcitxKey_Return ->
                            if (textInterceptor?.onReturn() != true) handleReturnKey()
                        FcitxKeyMapping.FcitxKey_Left -> handleArrowKey(KeyEvent.KEYCODE_DPAD_LEFT)
                        FcitxKeyMapping.FcitxKey_Right -> handleArrowKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                        else -> if (it.unicode > 0) {
                            commitText(Character.toString(it.unicode))
                        } else {
                            Timber.w("Unhandled Virtual KeyEvent: $it")
                        }
                    }
                } else {
                    // KeyEvent from physical keyboard (or input method engine forwardKey)
                    // use cached event if available
                    cachedKeyEvents.remove(it.timestamp)?.let { keyEvent ->
                        /**
                         * intercept the KeyEvent which would cause the default [android.text.method.QwertyKeyListener]
                         * to show a Gingerbread-style CharacterPickerDialog
                         */
                        if (keyEvent.unicodeChar == KeyCharacterMap.PICKER_DIALOG_INPUT.code) {
                            currentInputConnection?.sendKeyEvent(
                                KeyEvent(
                                    keyEvent.downTime, keyEvent.eventTime,
                                    keyEvent.action, keyEvent.keyCode,
                                    keyEvent.repeatCount, keyEvent.metaState, -1,
                                    keyEvent.scanCode, keyEvent.flags, keyEvent.source
                                )
                            )
                            return@event
                        }
                        currentInputConnection?.sendKeyEvent(keyEvent)
                        if (KeyEvent.isModifierKey(keyEvent.keyCode)) {
                            when (keyEvent.action) {
                                KeyEvent.ACTION_DOWN -> {
                                    // save current metaState when modifier key down
                                    lastMetaState = keyEvent.metaState
                                }
                                KeyEvent.ACTION_UP -> {
                                    // only clear metaState that would be missing when this modifier key up
                                    currentInputConnection?.clearMetaKeyStates(lastMetaState xor keyEvent.metaState)
                                    lastMetaState = keyEvent.metaState
                                }
                            }
                        }
                        return@event
                    }
                    // Ifboard: 「.」「,」若按模拟按键直接发给应用会绕过自动空格，开启时改为提交文本
                    if (autoSpaceAfterPeriod && (it.unicode == '.'.code || it.unicode == ','.code) &&
                        it.states.metaState == 0
                    ) {
                        if (!it.up) commitText(Character.toString(it.unicode))
                        return@event
                    }
                    // 自动空格之后的空格同理，交给 commitText 吞掉重复空格
                    if (autoSpaceCursor >= 0 && it.unicode == ' '.code && it.states.metaState == 0) {
                        if (!it.up) commitText(" ")
                        return@event
                    }
                    // simulate key event
                    val keyCode = it.sym.keyCode
                    if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                        // recognized keyCode
                        val eventTime = SystemClock.uptimeMillis()
                        if (it.up) {
                            sendUpKeyEvent(eventTime, keyCode, it.states.metaState)
                        } else {
                            sendDownKeyEvent(eventTime, keyCode, it.states.metaState)
                        }
                    } else {
                        // no matching keyCode, commit character once on key down
                        if (!it.up && it.unicode > 0) {
                            commitText(Character.toString(it.unicode))
                        } else {
                            Timber.w("Unhandled Fcitx KeyEvent: $it")
                        }
                    }
                }
            }
            is FcitxEvent.ClientPreeditEvent -> {
                val interceptor = textInterceptor
                if (interceptor != null) interceptor.onComposing(event.data.toString())
                else updateComposingText(event.data)
            }
            is FcitxEvent.DeleteSurroundingEvent -> {
                val (before, after) = event.data
                handleDeleteSurrounding(before, after)
            }
            is FcitxEvent.IMChangeEvent -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val im = event.data.uniqueName
                    val subtype = SubtypeManager.subtypeOf(im) ?: return
                    skipNextSubtypeChange = im
                    // [^1]: notify system that input method subtype has changed
                    switchInputMethod(InputMethodUtil.componentName, subtype)
                }
                if (inputDeviceMgr.evaluateOnInputMethodActivate()) {
                    showStatusIcon(StatusIconMapping.fromEntry(event.data))
                }
            }
            is FcitxEvent.SwitchInputMethodEvent -> {
                val (reason) = event.data
                if (reason != FcitxEvent.SwitchInputMethodEvent.Reason.CapabilityChanged &&
                    reason != FcitxEvent.SwitchInputMethodEvent.Reason.Other
                ) {
                    if (inputDeviceMgr.evaluateOnInputMethodSwitch()) {
                        // show inputView for [CandidatesView] when input method switched by user
                        forceShowSelf()
                    }
                }
            }
            else -> {}
        }
    }

    private fun handleDeleteSurrounding(before: Int, after: Int) {
        val ic = currentInputConnection ?: return
        if (before > 0) {
            selection.predictOffset(-before)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ic.deleteSurroundingTextInCodePoints(before, after)
        } else {
            ic.deleteSurroundingText(before, after)
        }
    }

    private fun handleBackspaceKey() {
        val lastSelection = selection.latest
        if (lastSelection.isNotEmpty()) {
            selection.predict(lastSelection.start)
        } else if (lastSelection.start > 0) {
            selection.predictOffset(-1)
        }
        // In practice nobody (apart from ourselves) would set `privateImeOptions` to our
        // `DeleteSurroundingFlag`, leading to a behavior of simulating backspace key pressing
        // in almost every EditText.
        if (currentInputEditorInfo.privateImeOptions != DeleteSurroundingFlag ||
            currentInputEditorInfo.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        ) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        if (lastSelection.isEmpty()) {
            if (lastSelection.start <= 0) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                currentInputConnection.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                currentInputConnection.deleteSurroundingText(1, 0)
            }
        } else {
            currentInputConnection.commitText("", 0)
        }
    }

    private fun handleReturnKey() {
        currentInputEditorInfo.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL ||
                imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            ) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                return
            }
            if (actionLabel?.isNotEmpty() == true && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                currentInputConnection.performEditorAction(actionId)
                return
            }
            when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_NONE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                else -> currentInputConnection.performEditorAction(action)
            }
        }
    }

    private fun handleArrowKey(keyCode: Int) {
        val type = currentInputEditorInfo.inputType and InputType.TYPE_MASK_CLASS
        val variation = currentInputEditorInfo.inputType and InputType.TYPE_MASK_VARIATION
        if (type == InputType.TYPE_NULL ||
            // confirm URL suggestion in browser location bar, see also https://bugzilla.mozilla.org/show_bug.cgi?id=1999915
            type == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI
        ) {
            sendDownUpKeyEvents(keyCode)
            return
        }
        val (start, end) = currentInputSelection
        val offset = if (start == end) 1 else 0
        val target = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> start - offset
            KeyEvent.KEYCODE_DPAD_RIGHT -> end + offset
            else -> return
        }
        currentInputConnection.setSelection(target, target)
    }

    /** Ifboard: 自动补在「.」「,」后面的空格所在的光标位置（-1 表示没有） */
    private var autoSpaceCursor = -1

    /** Ifboard: 自动加在「.」后面的那个空格的位置（-1 表示没有）；之后边打边判断后面的词是不是网址后缀 */
    private var dotSpaceAt = -1

    /** 已经因为网址后缀撤回了那个空格（x.com），这时 [dotSpaceAt] 是后缀第一个字母的位置 */
    private var dotSpaceJoined = false

    /** 撤回时后缀首字母是大写（句首自动大写成的 Com）：补回空格时恢复大写 */
    private var dotSpaceJoinedCap = false

    private fun resetDomainTracking() {
        dotSpaceAt = -1
        dotSpaceJoined = false
        dotSpaceJoinedCap = false
    }

    private fun isAsciiWord(w: String) = w.isNotEmpty() && w.all { it in 'a'..'z' || it in 'A'..'Z' }

    /** 当前是英文输入（键盘布局类输入法，不是拼音 / 双拼等） */
    private fun isEnglishInput(): Boolean = runCatching {
        val entry = fcitx.runImmediately { inputMethodEntryCached }
        entry.uniqueName.startsWith("keyboard")
    }.getOrDefault(false)

    /** 是不是网址后缀：不是英文单词的后缀（com / org / cn …）全小写或首字母大写都认；net / info 等只认全小写 */
    private fun isDomainTld(word: String): Boolean {
        if (!isAsciiWord(word)) return false
        val lower = word.lowercase()
        val capitalized = word[0].isUpperCase() && word.substring(1) == word.substring(1).lowercase()
        return when {
            lower in DomainTlds -> word == lower || capitalized
            lower in DomainTldsLowerOnly -> word == lower
            else -> false
        }
    }

    /**
     * 组字（英文边打边显示）时的网址后缀判断：组字内容一凑成后缀，就先撤下组字、删掉句点后的自动空格；
     * 组字内容不再是后缀时补回空格。返回 true 表示改动了组字区（调用方需要重新放上组字内容）。
     */
    private fun adjustDomainSpaceForComposing(ic: InputConnection, preedit: String): Boolean {
        val at = dotSpaceAt
        if (at < 0) return false
        // 只在英文输入时判断：拼音的组字（de、ca…）不是网址
        if (!isAsciiWord(preedit) || !isEnglishInput()) {
            resetDomainTracking()
            return false
        }
        val cs = if (composing.isEmpty()) selection.latest.start else composing.start
        val joined = dotSpaceJoined
        if (cs != (if (joined) at else at + 1)) {
            resetDomainTracking()
            return false
        }
        // 核对组字区前面确实是「x. 」（撤回前）或「x.」（撤回后）
        val compLen = (selection.latest.start - cs).coerceAtLeast(0)
        val b = ic.getTextBeforeCursor(compLen + 3, 0)?.toString().orEmpty().dropLast(compLen)
        val n = b.length
        val shapeOk = if (joined) {
            n >= 2 && b[n - 1] == '.' && b[n - 2].isLetterOrDigit()
        } else {
            n >= 3 && b[n - 1] == ' ' && b[n - 2] == '.' && b[n - 3].isLetterOrDigit()
        }
        if (!shapeOk) {
            resetDomainTracking()
            return false
        }
        val tld = isDomainTld(preedit)
        if (!joined && tld) {
            if (composing.isNotEmpty()) ic.setComposingText("", 1)
            ic.deleteSurroundingText(1, 0)
            composing.clear()
            selection.predict(cs - 1)
            dotSpaceJoined = true
            dotSpaceJoinedCap = preedit[0].isUpperCase()
            return true
        }
        if (joined && !tld) {
            if (composing.isNotEmpty()) ic.setComposingText("", 1)
            ic.commitText(" ", 1)
            composing.clear()
            selection.predict(cs + 1)
            dotSpaceJoined = false
            dotSpaceJoinedCap = false
            return true
        }
        return false
    }

    /**
     * 「example. com」→「example.com」，边打边判断：句点后自动加了空格，接着打的字母一凑成网址后缀
     * （com / org / cn …）就立刻撤回那个空格（马上发送也没问题）；之后再打字母使它不再是后缀
     * （come、computer），立刻补回空格并恢复原来的大写。
     * 返回要代替 [text] 提交的文本；null 表示照常提交 [text]。
     */
    private fun trackDomainWord(text: String, ic: InputConnection): String? {
        val at = dotSpaceAt
        if (at < 0) return null
        // 打了字母以外的东西（空格、标点、数字…）：这个词结束，不再跟踪
        if (!isAsciiWord(text) || composing.isNotEmpty()) {
            resetDomainTracking()
            return null
        }
        val cursor = selection.latest.start
        if (selection.latest.end != cursor) {
            resetDomainTracking()
            return null
        }
        val wordStart = if (dotSpaceJoined) at else at + 1
        val existingLen = cursor - wordStart
        if (existingLen !in 0..12) {
            resetDomainTracking()
            return null
        }
        // 撤回前：「x. 词」；撤回后：「x.词」——核对光标前的文字确实是这个样子（防止光标挪走后误改）
        val before = ic.getTextBeforeCursor(existingLen + 3, 0)?.toString().orEmpty()
        val n = before.length
        val shapeOk = when {
            dotSpaceJoined -> n >= existingLen + 2 && before[n - existingLen - 1] == '.' &&
                before[n - existingLen - 2].isLetterOrDigit()
            else -> n >= existingLen + 3 && before[n - existingLen - 1] == ' ' &&
                before[n - existingLen - 2] == '.' && before[n - existingLen - 3].isLetterOrDigit()
        }
        val existing = before.takeLast(existingLen)
        if (!shapeOk || (existingLen > 0 && !isAsciiWord(existing))) {
            resetDomainTracking()
            return null
        }
        val word = existing + text
        if (!dotSpaceJoined) {
            if (!isDomainTld(word)) return null // 还不是后缀：继续等
            // 凑成后缀：撤回空格，后缀改小写
            dotSpaceJoinedCap = word[0].isUpperCase()
            dotSpaceJoined = true
            ic.deleteSurroundingText(existingLen + 1, 0)
            selection.predict(cursor - existingLen - 1)
            return word.lowercase()
        }
        if (isDomainTld(word.lowercase())) return text.lowercase() // 仍是后缀（如 co → com 之类）
        // 不再是后缀（come、computer）：补回空格，恢复原来的大写
        dotSpaceJoined = false
        val restored = if (dotSpaceJoinedCap && existing.isNotEmpty()) {
            existing.replaceFirstChar { it.uppercaseChar() }
        } else {
            existing
        }
        dotSpaceJoinedCap = false
        ic.deleteSurroundingText(existingLen, 0)
        selection.predict(cursor - existingLen)
        return " $restored$text"
    }
    private val autoSpaceAfterPeriod by prefs.keyboard.autoSpaceAfterPeriod

    private fun editorAllowsAutoSpace(): Boolean {
        val type = currentInputEditorInfo?.inputType ?: return false
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_FILTER -> false
            else -> {
                val info = currentInputEditorInfo
                // 登录 / 账号类输入框几乎都会关掉输入建议，而且是单行、不要句首大写和自动更正。
                // Gemini / Google 搜索等聊天框也关了输入建议，但它们是多行、要求句首大写 / 自动更正，照常加空格
                val proseHints = InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                    InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                if (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0 && type and proseHints == 0) return false
                // 很多 App 与网页的账号框只是普通文本框：按提示文字识别邮箱 / 账号 / 密码 / 网址
                val hint = listOfNotNull(info?.hintText, info?.label).joinToString(" ")
                !AccountFieldHint.containsMatchIn(hint)
            }
        }
    }

    /**
     * 正在输入的内容看起来是邮箱 / 网址 / 账号：当前这串字符（上一个空白之后）里有 @ / : 之类，
     * 或者单行输入框里到目前为止一个空格都没有（john.doe、example.com 这类）。
     */
    private fun looksLikeAccountText(ic: InputConnection, beforeStart: Int, composingLen: Int, text: String): Boolean {
        val existing = ic.getTextBeforeCursor(AccountScanChars + composingLen, 0)
            ?.toString().orEmpty().dropLast(composingLen)
        val all = existing + text.dropLast(1)
        val token = all.takeLastWhile { !it.isWhitespace() }
        if (token.any { it == '@' || it == '/' || it == ':' || it == '\\' }) return true
        // 网址：www. / http 开头，或这串字符里已经有一个点（mail.google. 的第二个点）
        if (token.startsWith("www", ignoreCase = true) || token.startsWith("http", ignoreCase = true)) return true
        if (token.length >= 3 && token.contains('.') &&
            token.substringAfterLast('.').let { it.length >= 2 && it.all { c -> c.isLetterOrDigit() || c == '-' } }
        ) return true
        val type = currentInputEditorInfo?.inputType ?: 0
        val multiLine = type and (InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0
        val reachedStart = existing.length < AccountScanChars && beforeStart <= existing.length
        return !multiLine && reachedStart && all.none { it.isWhitespace() }
    }

    /**
     * 「输入 . 或 , 后自动加空格」：返回实际要提交的文本，null 表示这次提交被吞掉
     * （紧跟自动空格之后用户又按了空格）。
     * 兼容「单词.」「候选.」这类和句点一起提交的情况。
     */
    private fun applyAutoSpaceAfterPeriod(text: String, ic: InputConnection): String? {
        val prevAuto = autoSpaceCursor
        autoSpaceCursor = -1
        if (!autoSpaceAfterPeriod || text.isEmpty()) return text
        if (dotSpaceAt >= 0 && composing.isNotEmpty()) {
            // 组字的词上屏，这个词结束：
            //  · 已撤回空格且上屏的正是网址后缀：统一小写（句首自动大写成的 Com → com）
            //  · 已撤回空格但上屏的是别的词（选了联想词 company、拼音上屏「的」）：把空格补回去
            val joined = dotSpaceJoined
            resetDomainTracking()
            if (joined) return if (isDomainTld(text)) text.lowercase() else " $text"
            if (isAsciiWord(text)) return text
        }
        if (dotSpaceAt >= 0) {
            // 句点后自动空格之后正在打的词：凑成网址后缀就立刻撤回空格，不再是后缀就立刻补回
            trackDomainWord(text, ic)?.let { return it }
            if (isAsciiWord(text) && dotSpaceAt >= 0) return text
        }
        val cursorNow = selection.latest.start
        val justAutoSpaced = composing.isEmpty() && prevAuto >= 0 && prevAuto == cursorNow &&
            selection.latest.end == cursorNow
        if (justAutoSpaced) {
            if (text == " ") return null
            // 自动空格后紧接标点（如「.」「,」「)」）：先收回那个空格，标点贴着前文
            if (text[0] in AutoSpaceGluePunct && ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
                ic.deleteSurroundingText(1, 0)
                selection.predict(cursorNow - 1)
            }
        }
        val mark = text.last()
        if ((mark != '.' && mark != ',') || !editorAllowsAutoSpace()) return text
        // 句点前两个字符：取「输入框里组字区之前的文字 + 本次提交」的末尾
        val beforeStart = if (composing.isEmpty()) selection.latest.start else composing.start
        val composingLen = (selection.latest.start - beforeStart).coerceAtLeast(0)
        val existing = ic.getTextBeforeCursor(2 + composingLen, 0)
            ?.toString().orEmpty().dropLast(composingLen).takeLast(2)
        val tail = (existing + text.dropLast(1)).takeLast(2)
        val prev = tail.lastOrNull()
        val prev2 = if (tail.length >= 2) tail[0] else null
        // 小数点 / 千分位（3.14、1,000）、行首或空格后（.net）不加；连续标点（,, / ...）交给收回空格的逻辑
        if (prev == null || prev.isDigit() || prev.isWhitespace()) return text
        // 正在输入邮箱 / 网址 / 账号时不加
        if (looksLikeAccountText(ic, beforeStart, composingLen, text)) return text
        // 句点前只有单个字母（e.g. / U.S. / J. K.）不加，免得缩写被拆开；逗号不受此限
        if (mark == '.' && prev.isLetter() && prev.code < 0x80 && (prev2 == null || !prev2.isLetter())) return text
        val start = when {
            composing.isNotEmpty() -> composing.start
            else -> minOf(selection.latest.start, selection.latest.end)
        }
        autoSpaceCursor = start + text.length + 1
        resetDomainTracking()
        if (mark == '.') dotSpaceAt = start + text.length
        return "$text "
    }

    fun commitText(text: String, cursor: Int = -1) {
        textInterceptor?.let { it.onCommit(text); return }
        val ic = currentInputConnection ?: return
        @Suppress("NAME_SHADOWING")
        val text = applyAutoSpaceAfterPeriod(text, ic) ?: return
        // when composing text equals commit content, finish composing text as-is
        if (composing.isNotEmpty() && composingText.toString() == text) {
            val c = if (cursor == -1) text.length else cursor
            val target = composing.start + c
            resetComposingState()
            ic.withBatchEdit {
                if (selection.current.start != target) {
                    selection.predict(target)
                    ic.setSelection(target, target)
                }
                ic.finishComposingText()
            }
            return
        }
        // committed text should replace composing (if any), replace selected range (if any),
        // or simply prepend before cursor
        val start = if (composing.isEmpty()) selection.latest.start else composing.start
        resetComposingState()
        if (cursor == -1) {
            selection.predict(start + text.length)
            ic.commitText(text, 1)
        } else {
            val target = start + cursor
            selection.predict(target)
            ic.withBatchEdit {
                commitText(text, 1)
                setSelection(target, target)
            }
        }
    }

    private fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                ScancodeMapping.keyCodeToScancode(keyEventCode),
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    private fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                ScancodeMapping.keyCodeToScancode(keyEventCode),
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    /** 清空输入框：选中内容与光标前后全部文字，作为一次批量编辑 */
    fun deleteAllText() {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            // 全选后一次性删除：编辑器把它记成一步，误清空时一次撤销就能全部找回
            if (ic.performContextMenuAction(android.R.id.selectAll)) {
                ic.commitText("", 1)
                return
            }
            if (ic.getSelectedText(0)?.isNotEmpty() == true) ic.commitText("", 1)
            // 不支持全选的编辑器：分批删，直到光标前后都没有文字
            for (i in 0 until 50) {
                val before = ic.getTextBeforeCursor(DeleteAllChunk, 0)?.length ?: 0
                val after = ic.getTextAfterCursor(DeleteAllChunk, 0)?.length ?: 0
                if (before == 0 && after == 0) break
                ic.deleteSurroundingText(before, after)
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    fun deleteSelection() {
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        selection.predict(lastSelection.start)
        currentInputConnection?.commitText("", 1)
    }

    fun sendCombinationKeyEvents(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false
    ) {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun applySelectionOffset(offsetStart: Int, offsetEnd: Int = 0) {
        val lastSelection = selection.latest
        currentInputConnection?.also {
            val start = max(lastSelection.start + offsetStart, 0)
            val end = max(lastSelection.end + offsetEnd, 0)
            if (start > end) return
            selection.predict(start, end)
            it.setSelection(start, end)
        }
    }

    fun cancelSelection() {
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        val end = lastSelection.end
        selection.predict(end)
        currentInputConnection?.setSelection(end, end)
    }

    private lateinit var lastKnownConfig: Configuration

    override fun onConfigurationChanged(newConfig: Configuration) {
        postFcitxJob { reset() }
        /**
         * skip keyboard|keyboardHidden changes, because we have [inputDeviceMgr]
         * skip uiMode (system light/dark mode) changes, because we have [onThemeChangeListener]
         * to replace InputView(s) when needed
         * [android.inputmethodservice.InputMethodService.onConfigurationChanged] would call
         * resetStateForNewConfiguration() which calls initViews() causes InputView(s) to be replaced again
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1984
         */
        val f = ActivityInfo.CONFIG_KEYBOARD or
                ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
                ActivityInfo.CONFIG_UI_MODE
        val diff = lastKnownConfig.diff(newConfig)
        Timber.d("onConfigurationChanged diff=$diff")
        /**
         * perform `super.onConfigurationChanged` only when `newConfig` diff fall outside "skipped" flags
         * we have to calculate the mask ourselves because nobody knows how `handledConfigChanges` works
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/inputmethodservice/InputMethodService.java#1876
         */
        if (diff and f != diff) {
            super.onConfigurationChanged(newConfig)
        }
        lastKnownConfig = newConfig
        window.window?.let {
            navbarMgr.evaluate(it, inputDeviceMgr.isVirtualKeyboard)
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        window.window?.let {
            navbarMgr.evaluate(it, inputDeviceMgr.isVirtualKeyboard)
        }
        // 键盘重新显示时也确认一次边距（锁屏 / 分屏切换后可能是旧值）
        inputView?.requestApplyInsets()
        ClipboardManager.syncFromSystemClipboard()
        try {
            highlightColor = styledColorCompat(android.R.attr.colorAccent).alpha(0.4f)
        } catch (_: Exception) {
            Timber.w("Device does not support android.R.attr.colorAccent which it should have.")
        }
        InputFeedbacks.syncSystemPrefs()
    }

    override fun onCreateInputView(): View? {
        replaceInputViews(ThemeManager.activeTheme)
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        // input method layout has not changed in 11 years:
        // https://android.googlesource.com/platform/frameworks/base/+/ae3349e1c34f7aceddc526cd11d9ac44951e97b6/core/res/res/layout/input_method.xml
        // expand inputArea to fullscreen
        contentView.findViewById<FrameLayout>(android.R.id.inputArea)
            .updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        /**
         * expand InputView to fullscreen, since [android.inputmethodservice.InputMethodService.setInputView]
         * would set InputView's height to [ViewGroup.LayoutParams.WRAP_CONTENT]
         */
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (win.attributes.layoutInDisplayCutoutMode != mode) {
                win.attributes = win.attributes.apply {
                    layoutInDisplayCutoutMode = mode
                }
            }
        }
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private var inputViewLocation = intArrayOf(0, 0)

    fun updateFloatingWindowState() {
        window.window?.let {
            navbarMgr.evaluate(it, inputDeviceMgr.isVirtualKeyboard)
        }
        if (::decorView.isInitialized) {
            decorView.requestLayout()
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        if (inputDeviceMgr.isVirtualKeyboard) {
            val view = inputView
            if (view != null && view.isFloating) {
                val n = decorView.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
                val h = decorView.height - n
                outInsets.apply {
                    contentTopInsets = h
                    visibleTopInsets = h
                    touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    touchableRegion.setEmpty()
                    view.collectTouchableRegions(touchableRegion)
                }
                return
            }
            view?.keyboardView?.let { kv ->
                kv.getLocationInWindow(inputViewLocation)
                // 悬浮 ⇄ 停靠过渡动画期间键盘带着平移量：按最终位置算，免得应用区域被临时压缩露出黑底
                inputViewLocation[1] -= kv.translationY.toInt()
            }
            outInsets.apply {
                contentTopInsets = inputViewLocation[1]
                visibleTopInsets = inputViewLocation[1]
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        } else {
            val n = decorView.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
            val h = decorView.height - n
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    override fun onEvaluateFullscreenMode() = false

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        // reason to use a self increment index rather than timestamp:
        // KeyUp and KeyDown events actually can happen on the same time
        val timestamp = cachedKeyEventIndex++
        cachedKeyEvents.put(timestamp, event)
        val sym = KeySym.fromKeyEvent(event)
        if (sym != null) {
            val states = KeyStates.fromKeyEvent(event)
            val up = event.action == KeyEvent.ACTION_UP
            postFcitxJob {
                sendKey(sym, states, event.scanCode, up, timestamp)
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // request to show floating CandidatesView when pressing physical keyboard
        if (inputDeviceMgr.evaluateOnKeyDown(event, this)) {
            postFcitxJob {
                focus(true)
            }
            forceShowSelf()
        }
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return forwardKeyEvent(event) || super.onKeyUp(keyCode, event)
    }

    // Added in API level 14, deprecated in 29
    // it's needed because editors still use it even on API 36
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        inputDeviceMgr.evaluateOnViewClicked(this)
    }

    @RequiresApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        inputDeviceMgr.evaluateOnUpdateEditorToolType(toolType, this)
    }

    private var firstBindInput = true

    override fun onBindInput() {
        val uid = currentInputBinding.uid
        val pkgName = pkgNameCache.forUid(uid)
        Timber.d("onBindInput: uid=$uid pkg=$pkgName")
        postFcitxJob {
            // ensure InputContext has been created before focusing it
            activate(uid, pkgName)
        }
        if (firstBindInput) {
            firstBindInput = false
            // only use input method from subtype for the first `onBindInput`, because
            // 1. fcitx has `ShareInputState` option, thus reading input method from subtype
            //    everytime would ruin `ShareInputState=Program`
            // 2. im from subtype should be read once, when user changes input method from other
            //    app to a subtype of ours via system input method picker (on 34+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val subtype = inputMethodManager.currentInputMethodSubtype ?: return
                val im = SubtypeManager.inputMethodOf(subtype)
                postFcitxJob {
                    activateIme(im)
                }
            }
        }
    }

    /**
     * When input method changes internally (eg. via language switch key or keyboard shortcut),
     * we want to notify system that subtype has changed (see [^1]), then ignore the incoming
     * [onCurrentInputMethodSubtypeChanged] callback.
     * Input method should only be changed when user changes subtype in system input method picker
     * manually.
     */
    private var skipNextSubtypeChange: String? = null

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val im = SubtypeManager.inputMethodOf(newSubtype)
            Timber.d("onCurrentInputMethodSubtypeChanged: im=$im")
            // don't change input method if this "subtype change" was our notify to system
            // see [^1]
            if (skipNextSubtypeChange == im) {
                skipNextSubtypeChange = null
                return
            }
            postFcitxJob {
                activateIme(im)
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        // 换了输入框：不再跟踪上一个框里的网址后缀
        resetDomainTracking()
        // update selection as soon as possible
        // sometimes when restarting input, onUpdateSelection happens before onStartInput, and
        // initialSel{Start,End} is outdated. but it's the client app's responsibility to send
        // right cursor position, try to workaround this would simply introduce more bugs.
        selection.resetTo(attribute.initialSelStart, attribute.initialSelEnd)
        resetComposingState()
        val flags = CapabilityFlags.fromEditorInfo(attribute)
        capabilityFlags = flags
        // EditorInfo may change between onStartInput and onStartInputView
        inputDeviceMgr.notifyOnStartInput(attribute)
        Timber.d("onStartInput: initialSel=${selection.current}, restarting=$restarting")
        val isNullType = attribute.isTypeNull()
        // wait until InputContext created/activated
        postFcitxJob {
            if (restarting) {
                // when input restarts in the same editor, focus out to clear previous state
                focus(false)
                // try focus out before changing CapabilityFlags,
                // to avoid confusing state of different text fields
            }
            // EditorInfo can be different in onStartInput and onStartInputView,
            // especially in browsers
            setCapFlags(flags)
            // for hardware keyboard, focus to allow switching input methods before onStartInputView
            if (!isNullType) {
                focus(true)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        Timber.d("onStartInputView: restarting=$restarting")
        ensureSmsObserverRegistered()
        org.fcitx.fcitx5.android.ifboard.IfboardSync.maybeAutoSync(applicationContext)
        // 剪贴板同步：弹出键盘时连上电脑并取回电脑最新复制的内容（未开启时不做任何事）
        org.fcitx.fcitx5.android.ifboard.clipsync.ClipSync.onKeyboardShown()
        // 离线语音：后台预加载模型，按麦克风即可立即开始听（未下载模型时不做任何事）
        org.fcitx.fcitx5.android.ifboard.voice.OfflineAsr.preload(applicationContext)
        postFcitxJob {
            focus(true)
        }
        if (inputDeviceMgr.evaluateOnStartInputView(info, this)) {
            // because onStartInputView will always be called after onStartInput,
            // editorInfo and capFlags should be up-to-date
            inputView?.startInput(info, capabilityFlags, restarting)
        } else {
            if (currentInputConnection?.monitorCursorAnchor() != true) {
                if (!decorLocationUpdated) {
                    updateDecorLocation()
                }
                // anchor CandidatesView to bottom-left corner in case InputConnection does not
                // support monitoring CursorAnchorInfo
                candidatesView?.updateCursorAnchor(contentSize)
            }
            showStatusIcon(StatusIconMapping.fromEntry(fcitx.runImmediately { inputMethodEntryCached }))
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        // onUpdateSelection can left behind when user types quickly enough, eg. long press backspace
        cursorUpdateIndex += 1
        Timber.d("onUpdateSelection: old=[$oldSelStart,$oldSelEnd] new=[$newSelStart,$newSelEnd] cand=[$candidatesStart,$candidatesEnd]")
        handleCursorUpdate(
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
            cursorUpdateIndex
        )
        inputView?.updateSelection(newSelStart, newSelEnd)
    }

    private val contentSize = floatArrayOf(0f, 0f)
    private val decorLocation = floatArrayOf(0f, 0f)
    private val decorLocationInt = intArrayOf(0, 0)
    private var decorLocationUpdated = false

    private fun updateDecorLocation() {
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] = contentView.height.toFloat()
        decorView.getLocationOnScreen(decorLocationInt)
        decorLocation[0] = decorLocationInt[0].toFloat()
        decorLocation[1] = decorLocationInt[1].toFloat()
        // contentSize and decorLocation can be completely wrong,
        // when measuring right after the very first onStartInputView() of an IMS' lifecycle
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            decorLocationUpdated = true
        }
    }

    private val anchorPosition = floatArrayOf(0f, 0f, 0f, 0f)

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        if (inputDeviceMgr.isVirtualKeyboard) return
        val bounds = info.getCharacterBounds(0)
        if (bounds != null) {
            // anchor to start of composing span instead of insertion mark if available
            val horizontal =
                if (candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right else bounds.left
            anchorPosition[0] = horizontal
            anchorPosition[1] = bounds.bottom
            anchorPosition[2] = horizontal
            anchorPosition[3] = bounds.top
        } else {
            anchorPosition[0] = info.insertionMarkerHorizontal
            anchorPosition[1] = info.insertionMarkerBottom
            anchorPosition[2] = info.insertionMarkerHorizontal
            anchorPosition[3] = info.insertionMarkerTop
        }
        // avoid calling `decorView.getLocationOnScreen` repeatedly
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        if (anchorPosition.any(Float::isNaN)) {
            // anchor candidates view to bottom-left corner in case CursorAnchorInfo is invalid
            candidatesView?.updateCursorAnchor(contentSize)
            return
        }
        // params of `Matrix.mapPoints` must be [x0, y0, x1, y1]
        info.matrix.mapPoints(anchorPosition)
        val (xOffset, yOffset) = decorLocation
        anchorPosition[0] -= xOffset
        anchorPosition[1] -= yOffset
        anchorPosition[2] -= xOffset
        anchorPosition[3] -= yOffset
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    private fun handleCursorUpdate(
        newSelStart: Int,
        newSelEnd: Int,
        newComposingStart: Int,
        newComposingEnd: Int,
        updateIndex: Int
    ) {
        // Ifboard: 翻译栏工作时编辑框里的 composing 是译文，不属于 fcitx，不能据此 reset 正在输入的拼音
        textInterceptor?.let {
            selection.resetTo(newSelStart, newSelEnd)
            it.onEditorSelectionChanged(newSelStart, newSelEnd, newComposingStart, newComposingEnd)
            return
        }
        if (selection.consume(newSelStart, newSelEnd)) {
            // try restore composing range in case it was dropped by InputFilter
            // but only when prediction matches, since InputFilter can also change editor content
            // ref:
            // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/widget/Editor.java#2083
            // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/widget/TextView.java#7351
            if (newComposingStart == -1 && newComposingEnd == -1 && composing.isNotEmpty()) {
                currentInputConnection?.setComposingRegion(composing.start, composing.end)
            }
            return // do nothing if prediction matches
        } else {
            // cursor update can't match any prediction: it's treated as a user input
            selection.resetTo(newSelStart, newSelEnd)
        }
        // skip selection range update, we only care about selection cursor (zero width) here
        if (newSelStart != newSelEnd) return
        // do reset if composing is empty && input panel is not empty
        if (composing.isEmpty()) {
            postFcitxJob {
                if (!isEmpty()) {
                    Timber.d("handleCursorUpdate: reset")
                    reset()
                }
            }
            return
        }
        // check if cursor inside composing text
        if (composing.contains(newSelStart)) {
            if (ignoreSystemCursor) return
            // fcitx cursor position is relative to client preedit (composing text)
            val position = newSelStart - composing.start
            // move fcitx cursor when cursor position changed
            if (position != composingText.cursor) {
                // cursor in InvokeActionEvent counts by "UTF-8 characters"
                val codePointPosition = composingText.codePointCountUntil(position)
                postFcitxJob {
                    if (updateIndex != cursorUpdateIndex) return@postFcitxJob
                    Timber.d("handleCursorUpdate: move fcitx cursor to $codePointPosition")
                    moveCursor(codePointPosition)
                }
            }
        } else {
            Timber.d("handleCursorUpdate: focus out/in")
            resetComposingState()
            // cursor outside composing range, finish composing as-is
            currentInputConnection?.finishComposingText()
            // `fcitx.reset()` here would commit preedit after new cursor position
            // since we have `ClientUnfocusCommit`, focus out and in would do the trick
            postFcitxJob {
                focusOutIn()
            }
        }
    }

    // because setComposingText(text, cursor) can only put cursor at end of composing,
    // sometimes onUpdateSelection would receive event with wrong cursor position.
    // those events need to be filtered.
    // because of https://android.googlesource.com/platform/frameworks/base.git/+/refs/tags/android-11.0.0_r45/core/java/android/view/inputmethod/BaseInputConnection.java#851
    // it's not possible to set cursor inside composing text
    private fun updateComposingText(text: FormattedText) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        // 英文边打边显示（组字）时：凑成网址后缀就撤回句点后的自动空格，不再是后缀就补回
        val rewrote = !text.isEmpty() && adjustDomainSpaceForComposing(ic, text.toString())
        val lastSelection = selection.latest
        if (!rewrote && composingText.spanEquals(text)) {
            // composing text content is up-to-date
            // update cursor only when it's not empty AND cursor position is valid
            if (text.length > 0 && text.cursor >= 0) {
                val p = text.cursor + composing.start
                if (p != lastSelection.start) {
                    Timber.d("updateComposingText: set Android selection ($p, $p)")
                    ic.setSelection(p, p)
                    selection.predict(p)
                }
            }
        } else {
            // composing text content changed
            Timber.d("updateComposingText: '$text' lastSelection=$lastSelection")
            if (text.isEmpty()) {
                if (composing.isEmpty()) {
                    // do not reset saved selection range when incoming composing
                    // and saved composing range are both empty:
                    // composing.start is invalid when it's empty.
                    selection.predict(lastSelection.start)
                } else {
                    // clear composing text, put cursor at start of original composing
                    selection.predict(composing.start)
                    composing.clear()
                }
                ic.setComposingText("", 1)
            } else {
                val start = if (composing.isEmpty()) lastSelection.start else composing.start
                composing.update(start, start + text.length)
                // skip cursor reposition when:
                // - preedit cursor is at the end
                // - cursor position is invalid
                if (text.cursor == text.length || text.cursor < 0) {
                    selection.predict(composing.end)
                    ic.setComposingText(text.toSpannedString(highlightColor), 1)
                } else {
                    val p = text.cursor + composing.start
                    selection.predict(p)
                    ic.setComposingText(text.toSpannedString(highlightColor), 1)
                    ic.setSelection(p, p)
                }
            }
            Timber.d("updateComposingText: composing=$composing")
        }
        composingText = text
        ic.endBatchEdit()
    }

    /**
     * Finish composing text and leave cursor position as-is.
     * Also updates internal composing state of [FcitxInputMethodService].
     */
    fun finishComposing() {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) return
        composing.clear()
        composingText = FormattedText.Empty
        ic.finishComposingText()
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        // ignore inline suggestion when disabled by user || using physical keyboard with floating candidates view
        if (!inlineSuggestions || !inputDeviceMgr.isVirtualKeyboard) return null
        val theme = ThemeManager.activeTheme
        val chipDrawable =
            if (theme.isDark) R.drawable.bkg_inline_suggestion_dark else R.drawable.bkg_inline_suggestion_light
        val chipBg = Icon.createWithResource(this, chipDrawable).setTint(theme.keyTextColor)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                ViewStyle.Builder()
                    .setBackgroundColor(Color.TRANSPARENT)
                    .setPadding(0, 0, 0, 0)
                    .build()
            )
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(chipBg)
                    .setPadding(dp(10), 0, dp(10), 0)
                    .build()
            )
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setLayoutMargin(dp(4), 0, dp(4), 0)
                    .setTextColor(theme.keyTextColor)
                    .setTextSize(14f)
                    .build()
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(theme.altKeyTextColor)
                    .setTextSize(12f)
                    .build()
            )
            .setStartIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .setEndIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .build()
        val styleBundle = UiVersions.newStylesBuilder()
            .addStyle(style)
            .build()
        val spec = InlinePresentationSpec
            .Builder(Size(0, 0), Size(Int.MAX_VALUE, Int.MAX_VALUE))
            .setStyle(styleBundle)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inlineSuggestions || !inputDeviceMgr.isVirtualKeyboard) return false
        return inputView?.handleInlineSuggestions(response) == true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        decorLocationUpdated = false
        inputDeviceMgr.onFinishInputView()
        currentInputConnection?.apply {
            finishComposingText()
            monitorCursorAnchor(false)
        }
        resetComposingState()
        // 收起键盘时必须停止语音识别，否则识别器会自动续听并一直占用麦克风
        inputView?.stopInlineVoice()
        org.fcitx.fcitx5.android.ifboard.clipsync.ClipSync.onKeyboardHidden()
        org.fcitx.fcitx5.android.ifboard.voice.OfflineAsr.onKeyboardHidden()
        postFcitxJob {
            focusOutIn()
        }
        hideStatusIcon()
        showingDialog?.dismiss()
    }

    override fun onFinishInput() {
        Timber.d("onFinishInput")
        postFcitxJob {
            focus(false)
        }
        capabilityFlags = CapabilityFlags.DefaultFlags
    }

    override fun onUnbindInput() {
        cachedKeyEvents.evictAll()
        cachedKeyEventIndex = 0
        cursorUpdateIndex = 0
        // currentInputBinding can be null on some devices under some special Multi-screen mode
        val uid = currentInputBinding?.uid ?: return
        Timber.d("onUnbindInput: uid=$uid")
        postFcitxJob {
            deactivate(uid)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenStateReceiver) }
        inputView?.stopInlineVoice()
        screenshotDebounceJob?.cancel()
        mediaScreenshotObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        smsOtpObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        super.onDestroy()
        // Fcitx might be used in super.onDestroy()
        FcitxDaemon.disconnect(javaClass.name)
    }

    private var showingDialog: Dialog? = null

    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        dialog.window?.also {
            it.attributes.apply {
                token = decorView.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            it.addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
            it.setDimAmount(styledFloat(android.R.attr.backgroundDimAmount))
        }
        dialog.setOnDismissListener {
            showingDialog = null
        }
        dialog.show()
        showingDialog = dialog
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** 判断是否在输入邮箱 / 账号时往前看的字符数 */
        private const val AccountScanChars = 200
        private val AccountFieldHint = Regex(
            "邮箱|郵箱|电邮|账号|帐号|賬號|用户名|用戶名|登录名|密码|密碼|手机号|手機號|网址|網址|链接|域名|" +
                "e-?mail|mail|user ?name|user ?id|account|login|sign ?in|password|passcode|\\bpin\\b|url|website|domain|phone",
            RegexOption.IGNORE_CASE
        )
        private const val AutoSpaceGluePunct = ".,;:!?)]}'\""

        /** 网址后缀：都不是英文单词，句首大写的「Com」也认 */
        private val DomainTlds = setOf(
            "com", "org", "edu", "gov", "mil", "io", "cn", "hk", "tw", "jp", "kr", "uk", "de", "fr", "nl", "ru",
            "hu", "pl", "se", "ch", "au", "ca", "eu", "xyz", "icu", "biz", "cz", "sk", "ro", "gr", "pt", "br",
            "mx", "nz", "sg", "vn", "ua", "tr", "dk", "fi", "ie", "il", "za", "ae"
        )

        /** 也是英文单词的后缀：只认全小写 */
        private val DomainTldsLowerOnly = setOf("net", "info", "tv", "cc")
        private const val DeleteAllChunk = 100_000
        const val DeleteSurroundingFlag = "org.fcitx.fcitx5.android.DELETE_SURROUNDING"
    }
}
