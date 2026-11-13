/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import androidx.lifecycle.lifecycleScope
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardLayoutMetrics
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.unset
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToEndOf
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.startToStartOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.constraintlayout.topToTopOf
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import timber.log.Timber
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    companion object {
        const val FLOATING_CORNER_DP = 18f
        private const val FLOATING_HANDLE_HEIGHT_DP = 28
        private const val DOUBLE_TAP_TIMEOUT_MS = 280L
    }

    private val keyBorder by ThemeManager.prefs.keyBorder
    private val internalPrefs = AppPrefs.getInstance().internal

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val topPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Ifboard liquid glass: blur the app behind the keyboard, keep keys opaque
        val cornerDp = if (isFloating) FLOATING_CORNER_DP else 0f
        if (Glass.applyBackgroundBlur(keyboardView, theme.backgroundColor, cornerRadiusDp = cornerDp, host = this)) {
            customBackground.alpha = 0f
        }
        applyFloatingChrome()
        service.updateFloatingWindowState()
    }

    val translatePanel by lazy {
        org.fcitx.fcitx5.android.input.inline.TranslatePanel(themedContext, theme, service, service.lifecycleScope).apply {
            popupListener = popup.listener
        }
    }

    val voiceBar by lazy {
        org.fcitx.fcitx5.android.input.inline.VoiceBar(themedContext, theme, service) {
            val ime = fcitx.runImmediately { inputMethodEntryCached }
            if (ime.uniqueName.startsWith("keyboard-")) "en-US" else "zh-CN"
        }.apply {
            // hide the toolbar underneath completely while listening (the bar may be translucent)
            onActiveChanged = { on ->
                kawaiiBar.view.animate().cancel()
                kawaiiBar.view.animate().alpha(if (on) 0f else 1f).setDuration(150).start()
            }
        }
    }
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val floatingKeyboardPref = keyboardPrefs.floatingKeyboard
    val isFloating: Boolean
        get() = floatingKeyboardPref.getValue()

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val advancedPrefs = AppPrefs.getInstance().advanced
    private val keyboardHeightPercentBase = advancedPrefs.keyboardHeightPercentBase

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
        keyboardHeightPercentBase,
    )

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private val availableWidthPx: Int
        get() = if (width > 0) width else resources.displayMetrics.widthPixels

    private val availableHeightPx: Int
        get() = if (height > 0) height else resources.displayMetrics.heightPixels

    private val keyboardHeightPx: Int
        get() {
            val baseType = keyboardHeightPercentBase.getValue()
            val landscape = isLandscape
            val percent = (if (landscape) keyboardHeightPercentLandscape else keyboardHeightPercent).getValue()
            Timber.d("keyboardHeightPx get(): baseType=$baseType, percent=$percent, landscape=$landscape")
            return baseType.computeKeyboardHeightPx(context, percent, landscape)
        }

    private val floatingWidthPx: Int
        get() {
            val parentW = availableWidthPx
            return if (isLandscape) {
                (parentW * 0.48f).toInt().coerceIn(minOf(dp(280), parentW), maxOf(minOf(dp(280), parentW), dp(380)))
            } else {
                (parentW * 0.85f).toInt().coerceIn(minOf(dp(280), parentW), maxOf(minOf(dp(280), parentW), dp(344)))
            }
        }

    private val floatingKeyboardHeightPx: Int
        get() = (keyboardHeightPx * 0.84f).toInt().coerceAtLeast(minOf(keyboardHeightPx, dp(150)))

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
        }
    }

    @Keep
    private val onFloatingChangedListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        updateKeyboardSize()
        service.updateFloatingWindowState()
    }

    private val floatingOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(FLOATING_CORNER_DP))
        }
    }

    private val floatingRimDrawable by lazy {
        GradientDrawable().apply {
            cornerRadius = dp(FLOATING_CORNER_DP)
            setStroke(dp(1), if (theme.isDark) 0x38FFFFFF else 0x26000000)
        }
    }

    private var statusBarTopInsetPx = 0
    private var navSafeBottomInsetPx = 0
    private var isDraggingFloating = false
    private var lastParentW = 0
    private var lastParentH = 0

    private val floatingXPref
        get() = if (isLandscape) internalPrefs.floatingXLandscape else internalPrefs.floatingX

    private val floatingYPref
        get() = if (isLandscape) internalPrefs.floatingYLandscape else internalPrefs.floatingY

    private val floatingHandleBar: FrameLayout = buildFloatingHandleBar()
    val keyboardView: View

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)

        keyboardView = constraintLayout {
            id = View.generateViewId()
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            // Ifboard inline translation panel sits above the toolbar
            add(translatePanel.root, lParams(matchParent, wrapContent) {
                topOfParent()
                centerHorizontally()
            })
            add(kawaiiBar.view, lParams(matchParent, currentBarHeightPx()) {
                below(translatePanel.root)
                centerHorizontally()
            })
            // Ifboard inline voice bar overlays the toolbar, keys stay usable
            add(voiceBar.root, lParams(matchParent, currentBarHeightPx()) {
                topToTopOf(kawaiiBar.view)
                centerHorizontally()
            })
            add(topPaddingSpace, lParams(matchParent, currentTopPaddingPx()) {
                below(kawaiiBar.view)
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(topPaddingSpace)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(topPaddingSpace)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(topPaddingSpace)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
            add(floatingHandleBar, lParams(matchParent, dp(FLOATING_HANDLE_HEIGHT_DP)) {
                startOfParent()
                endOfParent()
                bottomOfParent()
            })
        }

        keyboardView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!isFloating) return@addOnLayoutChangeListener
            Glass.invalidateBlur(keyboardView)
            if (!isDraggingFloating) {
                (keyboardView.layoutParams as? LayoutParams)?.let { lp ->
                    val (cx, cy) = clampFloatingPosition(lp.leftMargin, lp.bottomMargin)
                    if (cx != lp.leftMargin || cy != lp.bottomMargin) {
                        keyboardView.post {
                            if (isFloating && !isDraggingFloating && keyboardView.isAttachedToWindow) {
                                applyFloatingMargins(cx, cy)
                                invalidateKeyBounds(keyboardView)
                            }
                        }
                    }
                }
            }
        }

        windowManager.onWindowAttachedListener = { applyBottomInsetMode(it) }

        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            above(keyboardView)
            centerHorizontally()
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })

        updateKeyboardSize()

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        advancedPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        floatingKeyboardPref.registerOnChangeListener(onFloatingChangedListener)
    }

    private var navBarBottomInsetPx = 0

    private fun bottomAreaInsetPx() = keyboardBottomPaddingPx + navBarBottomInsetPx

    private fun applyBottomInsetMode(window: InputWindow? = windowManager.attachedWindow) {
        if (windowManager.view.layoutParams !is LayoutParams ||
            bottomPaddingSpace.layoutParams !is LayoutParams
        ) {
            return
        }
        if (isFloating) {
            floatingHandleBar.visibility = VISIBLE
            bottomPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                bottomToBottom = unset
                bottomToTop = unset
                above(floatingHandleBar)
                height = floatingKeyboardHeightPx
            }
            if (window?.extendBehindNavBar == true) {
                window.onBottomInsetChanged(0)
            }
            return
        }
        floatingHandleBar.visibility = GONE
        val edgeToEdge = window?.extendBehindNavBar == true
        val bottomBand = bottomAreaInsetPx()
        if (edgeToEdge) {
            bottomPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                bottomToTop = unset
                bottomOfParent()
                height = keyboardHeightPx + bottomBand
            }
            window.onBottomInsetChanged(bottomBand)
        } else {
            bottomPaddingSpace.visibility = VISIBLE
            bottomPaddingSpace.updateLayoutParams<LayoutParams> {
                height = keyboardBottomPaddingPx
                bottomMargin = navBarBottomInsetPx
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                bottomToBottom = unset
                bottomToTop = unset
                above(bottomPaddingSpace)
                height = keyboardHeightPx
            }
        }
    }

    private fun currentSidePaddingPx(floating: Boolean = isFloating): Int =
        if (floating) {
            KeyboardLayoutMetrics.computeFloatingSidePaddingPx(keyboardSidePaddingPx, dp(5), dp(7))
        } else {
            keyboardSidePaddingPx
        }

    private fun currentBarHeightPx(): Int =
        dp(KeyboardLayoutMetrics.barHeightDp(isLandscape))

    private fun currentTopPaddingPx(floating: Boolean = isFloating): Int {
        if (isLandscape) return 0
        val sidePadding = currentSidePaddingPx(floating)
        val hostWidthPx = if (floating) floatingWidthPx else availableWidthPx
        val density = resources.displayMetrics.density
        val containerWidthDp = (hostWidthPx - 2 * sidePadding).coerceAtLeast(0) / density
        val prefs = org.fcitx.fcitx5.android.data.theme.ThemeManager.prefs
        val effectiveMetrics = KeyboardLayoutMetrics.resolveKeyMetricsDp(
            containerWidthDp = containerWidthDp,
            landscape = false,
            rowCount = 4,
            prefHMarginDp = prefs.keyHorizontalMargin.getValue().toFloat(),
            prefVMarginDp = prefs.keyVerticalMargin.getValue().toFloat(),
            prefRadiusDp = prefs.keyRadius.getValue().toFloat()
        )
        val topDp = KeyboardLayoutMetrics.topPaddingDp(false, effectiveMetrics.vMarginDp)
        return Math.round(topDp * density)
    }

    private fun updateKeyboardSize() {
        applyBottomInsetMode()
        val floating = isFloating
        val sidePadding = currentSidePaddingPx(floating)
        val barHeightPx = currentBarHeightPx()
        kawaiiBar.view.layoutParams?.let { lp ->
            if (lp.height != barHeightPx) {
                lp.height = barHeightPx
                kawaiiBar.view.layoutParams = lp
            }
        }
        voiceBar.root.layoutParams?.let { lp ->
            if (lp.height != barHeightPx) {
                lp.height = barHeightPx
                voiceBar.root.layoutParams = lp
            }
        }
        val topPaddingPx = currentTopPaddingPx(floating)
        topPaddingSpace.layoutParams?.let { lp ->
            if (lp.height != topPaddingPx) {
                lp.height = topPaddingPx
                topPaddingSpace.layoutParams = lp
            }
        }
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)

        if (keyboardView.layoutParams is LayoutParams && preedit.ui.root.layoutParams is LayoutParams) {
            if (floating) {
                val targetW = floatingWidthPx
                val (nx, ny) = currentClampedFloatingPosition(targetW)
                keyboardView.updateLayoutParams<LayoutParams> {
                    width = targetW
                    endToEnd = unset
                    startOfParent()
                    bottomOfParent()
                    leftMargin = nx
                    bottomMargin = ny
                }
                preedit.ui.root.updateLayoutParams<LayoutParams> {
                    width = matchConstraints
                    startToStartOf(keyboardView)
                    endToEndOf(keyboardView)
                    above(keyboardView)
                }
            } else {
                keyboardView.updateLayoutParams<LayoutParams> {
                    width = matchParent
                    startOfParent()
                    endOfParent()
                    bottomOfParent()
                    leftMargin = 0
                    bottomMargin = 0
                }
                preedit.ui.root.updateLayoutParams<LayoutParams> {
                    width = matchParent
                    centerHorizontally()
                    above(keyboardView)
                }
            }
        }
        applyFloatingChrome()
        keyboardView.post { invalidateKeyBounds(keyboardView) }
    }

    private fun applyFloatingChrome() {
        val floating = isFloating
        if (floating) {
            keyboardView.outlineProvider = floatingOutlineProvider
            keyboardView.clipToOutline = true
            keyboardView.elevation = dp(10f)
            keyboardView.foreground = floatingRimDrawable
        } else {
            keyboardView.outlineProvider = ViewOutlineProvider.BACKGROUND
            keyboardView.clipToOutline = false
            keyboardView.elevation = 0f
            keyboardView.foreground = null
        }
        Glass.updateCornerRadius(keyboardView, if (floating) FLOATING_CORNER_DP else 0f)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildFloatingHandleBar(): FrameLayout {
        val gripView = View(context).apply {
            background = GradientDrawable().apply {
                setColor(ColorUtils.setAlphaComponent(theme.keyTextColor, 0x66))
                cornerRadius = dp(2f)
            }
        }
        val dockButton = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(40),
                dp(FLOATING_HANDLE_HEIGHT_DP),
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                marginEnd = dp(12)
            }
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(
                ContextCompat.getDrawable(context, R.drawable.ic_gb_dock_keyboard)?.mutate()?.apply {
                    setTint(ColorUtils.setAlphaComponent(theme.keyTextColor, 0x99))
                }
            )
            contentDescription = context.getString(R.string.dock_keyboard)
            setOnClickListener {
                InputFeedbacks.hapticFeedback(it)
                floatingKeyboardPref.setValue(false)
            }
        }
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var lastTapUpTime = 0L

        return FrameLayout(context).apply {
            id = View.generateViewId()
            visibility = GONE
            addView(
                gripView,
                FrameLayout.LayoutParams(dp(42), dp(4), Gravity.CENTER)
            )
            addView(dockButton)
            setOnTouchListener { v, ev ->
                val lp = keyboardView.layoutParams as? LayoutParams ?: return@setOnTouchListener false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        startX = lp.leftMargin
                        startY = lp.bottomMargin
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downRawX
                        val dy = downRawY - ev.rawY
                        if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            dragging = true
                            isDraggingFloating = true
                            popup.dismissAll()
                        }
                        if (dragging) {
                            val (nx, ny) = clampFloatingPosition((startX + dx).toInt(), (startY + dy).toInt())
                            applyFloatingMargins(nx, ny)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            isDraggingFloating = false
                            saveFloatingPosition(lp.leftMargin, lp.bottomMargin)
                            invalidateKeyBounds(keyboardView)
                        } else {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastTapUpTime <= DOUBLE_TAP_TIMEOUT_MS) {
                                lastTapUpTime = 0L
                                InputFeedbacks.hapticFeedback(v)
                                resetFloatingPosition()
                            } else {
                                lastTapUpTime = now
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            isDraggingFloating = false
                            saveFloatingPosition(lp.leftMargin, lp.bottomMargin)
                            invalidateKeyBounds(keyboardView)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun estimatedFloatingCardHeightPx(cardW: Int = floatingWidthPx): Int =
        if (keyboardView.height > 0 && keyboardView.width == cardW) {
            keyboardView.height
        } else {
            floatingKeyboardHeightPx + currentBarHeightPx() + currentTopPaddingPx(floating = true) + dp(FLOATING_HANDLE_HEIGHT_DP)
        }

    private fun clampFloatingPosition(x: Int, y: Int, cardW: Int = floatingWidthPx): Pair<Int, Int> {
        val actualH = estimatedFloatingCardHeightPx(cardW)
        val pad = dp(4)
        val maxX = (availableWidthPx - cardW - pad).coerceAtLeast(pad)
        val minY = maxOf(navSafeBottomInsetPx, pad)
        val maxY = (availableHeightPx - actualH - statusBarTopInsetPx - dp(8)).coerceAtLeast(minY)
        return x.coerceIn(pad, maxX) to y.coerceIn(minY, maxY)
    }

    private fun currentClampedFloatingPosition(cardW: Int): Pair<Int, Int> {
        val rawX = floatingXPref.getValue()
        val rawY = floatingYPref.getValue()
        val defaultX = ((availableWidthPx - cardW) / 2).coerceAtLeast(dp(4))
        val defaultY = maxOf(navSafeBottomInsetPx + dp(12), dp(24))
        val targetX = if (rawX < 0) defaultX else rawX
        val targetY = if (rawY < 0) defaultY else rawY
        return clampFloatingPosition(targetX, targetY, cardW)
    }

    private fun applyFloatingMargins(x: Int, y: Int) {
        val lp = keyboardView.layoutParams as? LayoutParams ?: return
        if (lp.leftMargin != x || lp.bottomMargin != y) {
            lp.leftMargin = x
            lp.bottomMargin = y
            keyboardView.layoutParams = lp
            Glass.invalidateBlur(keyboardView)
        }
    }

    private fun saveFloatingPosition(x: Int, y: Int) {
        floatingXPref.setValue(x)
        floatingYPref.setValue(y)
    }

    private fun resetFloatingPosition() {
        val cardW = floatingWidthPx
        val defaultX = ((availableWidthPx - cardW) / 2).coerceAtLeast(dp(4))
        val defaultY = maxOf(navSafeBottomInsetPx + dp(12), dp(24))
        val (nx, ny) = clampFloatingPosition(defaultX, defaultY, cardW)
        applyFloatingMargins(nx, ny)
        saveFloatingPosition(nx, ny)
        invalidateKeyBounds(keyboardView)
    }

    private fun invalidateKeyBounds(view: View) {
        if (view is KeyView) {
            view.invalidateBounds()
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                invalidateKeyBounds(view.getChildAt(i))
            }
        }
    }

    private val touchLoc = IntArray(2)

    fun collectTouchableRegions(region: Region) {
        if (keyboardView.isShown && keyboardView.width > 0 && keyboardView.height > 0) {
            keyboardView.getLocationInWindow(touchLoc)
            region.op(
                touchLoc[0],
                touchLoc[1],
                touchLoc[0] + keyboardView.width,
                touchLoc[1] + keyboardView.height,
                Region.Op.UNION
            )
        }
        val preeditRoot = preedit.ui.root
        if (preeditRoot.isShown && preeditRoot.width > 0 && preeditRoot.height > 0) {
            preeditRoot.getLocationInWindow(touchLoc)
            region.op(
                touchLoc[0],
                touchLoc[1],
                touchLoc[0] + preeditRoot.width,
                touchLoc[1] + preeditRoot.height,
                Region.Op.UNION
            )
        }
        popup.collectTouchableRegions(region)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != lastParentW || h != lastParentH)) {
            lastParentW = w
            lastParentH = h
            if (isFloating) {
                post { if (isAttachedToWindow) updateKeyboardSize() }
            }
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        navBarBottomInsetPx = getNavBarBottomInset(insets)
        val compat = WindowInsetsCompat.toWindowInsetsCompat(insets)
        val sysBars = compat.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val mandatory = compat.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        statusBarTopInsetPx = sysBars.top
        navSafeBottomInsetPx = maxOf(sysBars.bottom, mandatory.bottom)
        if (isFloating) {
            updateKeyboardSize()
        } else {
            applyBottomInsetMode()
        }
        return insets
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        floatingKeyboardPref.unregisterOnChangeListener(onFloatingChangedListener)
        advancedPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
