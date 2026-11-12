/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.CallSuper
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import timber.log.Timber
import kotlin.math.absoluteValue
import kotlin.math.hypot
import kotlin.math.roundToInt

abstract class BaseKeyboard(
    context: Context,
    protected val theme: Theme,
    private val keyLayout: List<List<KeyDef>>
) : ConstraintLayout(context) {

    var keyActionListener: KeyActionListener? = null

    private val prefs = AppPrefs.getInstance()

    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress
    private val expandKeypressArea by prefs.keyboard.expandKeypressArea
    private val swipeSymbolDirection by prefs.keyboard.swipeSymbolDirection

    private val spaceSwipeMoveCursor = prefs.keyboard.spaceSwipeMoveCursor
    private val spaceKeys = mutableListOf<KeyView>()
    private val spaceSwipeChangeListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        spaceKeys.forEach {
            it.swipeEnabled = v
        }
    }

    private val vivoKeypressWorkaround by prefs.advanced.vivoKeypressWorkaround

    private val hapticOnRepeat by prefs.keyboard.hapticOnRepeat

    var popupActionListener: PopupActionListener? = null

    private val selectionSwipeThreshold = dp(10f)
    private val inputSwipeThreshold = dp(36f)

    // a rather large threshold effectively disables swipe of the direction
    private val disabledSwipeThreshold = dp(800f)

    private val bounds = Rect()
    private val keyRows: List<ConstraintLayout>

    /**
     * HashMap of [PointerId (Int)][MotionEvent.getPointerId] to [KeyView]
     */
    private val touchTarget = hashMapOf<Int, View>()

    // Gboard style slide input (?123 -> symbol): find keys by screen position
    private val pressActions = LinkedHashMap<KeyView, KeyAction>()
    private val tmpLoc = IntArray(2)

    // glide typing: letter keys, filled while the key views are created in init (must be declared before it)
    private val letterKeys = LinkedHashMap<KeyView, Char>()

    fun findKeyAt(rawX: Float, rawY: Float): Pair<KeyView, KeyAction>? {
        for ((v, a) in pressActions) {
            if (!v.isShown) continue
            v.getLocationOnScreen(tmpLoc)
            if (rawX >= tmpLoc[0] && rawX < tmpLoc[0] + v.width && rawY >= tmpLoc[1] && rawY < tmpLoc[1] + v.height)
                return v to a
        }
        return null
    }

    fun showSlidePreview(v: KeyView) {
        val text = (v as? TextKeyView)?.mainText?.text?.toString() ?: return
        v.updateBounds()
        onPopupAction(PopupAction.PreviewAction(System.identityHashCode(v), text, v.bounds))
    }

    fun dismissSlidePreview(v: KeyView) {
        onPopupAction(PopupAction.DismissAction(System.identityHashCode(v)))
    }

    fun dispatchSlideAction(action: KeyAction) = onAction(action)

    init {
        isMotionEventSplittingEnabled = true
        keyRows = keyLayout.map { row ->
            val keyViews = row.map(::createKeyView)
            constraintLayout Row@{
                var totalWidth = 0f
                keyViews.forEachIndexed { index, view ->
                    add(view, lParams {
                        centerVertically()
                        if (index == 0) {
                            leftOfParent()
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            leftToRightOf(keyViews[index - 1])
                        }
                        if (index == keyViews.size - 1) {
                            rightOfParent()
                            // for RTL
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            rightToLeftOf(keyViews[index + 1])
                        }
                        val def = row[index]
                        matchConstraintPercentWidth = def.appearance.percentWidth
                    })
                    row[index].appearance.percentWidth.let {
                        // 0f means fill remaining space, thus does not need expanding
                        totalWidth += if (it != 0f) it else 1f
                    }
                }
                if (expandKeypressArea && totalWidth < 1f) {
                    val free = (1f - totalWidth) / 2f
                    keyViews.first().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginLeft = free / (row.first().appearance.percentWidth + free)
                    }
                    keyViews.last().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginRight = free / (row.last().appearance.percentWidth + free)
                    }
                }
            }
        }
        keyRows.forEachIndexed { index, row ->
            add(row, lParams {
                if (index == 0) topOfParent()
                else below(keyRows[index - 1])
                if (index == keyRows.size - 1) bottomOfParent()
                else above(keyRows[index + 1])
                centerHorizontally()
            })
        }
        spaceSwipeMoveCursor.registerOnChangeListener(spaceSwipeChangeListener)
    }

    private fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, theme, def.appearance)
        }.apply {
            if (def is AlphabetKey && def.character.length == 1 && def.character[0].lowercaseChar() in 'a'..'z') {
                letterKeys[this] = def.character[0].lowercaseChar()
            }
            soundEffect = when (def) {
                is SpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is MiniSpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is BackspaceKey -> InputFeedbacks.SoundEffect.Delete
                is ReturnKey -> InputFeedbacks.SoundEffect.Return
                else -> InputFeedbacks.SoundEffect.Standard
            }
            if (def is SpaceKey) {
                spaceKeys.add(this)
                swipeEnabled = spaceSwipeMoveCursor.getValue()
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> when (val count = event.countX) {
                            0 -> false
                            else -> {
                                val sym =
                                    if (count > 0) FcitxKeyMapping.FcitxKey_Right else FcitxKeyMapping.FcitxKey_Left
                                val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                                repeat(count.absoluteValue) {
                                    onAction(action)
                                    if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                }
                                true
                            }
                        }
                        else -> false
                    }
                }
            } else if (def is BackspaceKey) {
                swipeEnabled = true
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> {
                            val count = event.countX
                            if (count != 0) {
                                onAction(KeyAction.MoveSelectionAction(count))
                                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                true
                            } else false
                        }
                        GestureType.Up -> {
                            onAction(KeyAction.DeleteSelectionAction(event.totalX))
                            false
                        }
                        else -> false
                    }
                }
            }
            def.behaviors.forEach {
                when (it) {
                    is KeyDef.Behavior.Press -> {
                        pressActions[this] = it.action
                        setOnClickListener { _ ->
                            onAction(it.action)
                        }
                    }
                    is KeyDef.Behavior.LongPress -> {
                        setOnLongClickListener { _ ->
                            onAction(it.action)
                            true
                        }
                    }
                    is KeyDef.Behavior.Repeat -> {
                        repeatEnabled = true
                        onRepeatListener = { view ->
                            onAction(it.action)
                            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                        }
                    }
                    is KeyDef.Behavior.Swipe -> {
                        swipeEnabled = true
                        swipeThresholdX = disabledSwipeThreshold
                        swipeThresholdY = inputSwipeThreshold
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            when (event.type) {
                                GestureType.Up -> {
                                    if (!event.consumed && swipeSymbolDirection.checkY(event.totalY)) {
                                        onAction(it.action)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Behavior.DoubleTap -> {
                        doubleTapEnabled = true
                        onDoubleTapListener = { _ ->
                            onAction(it.action)
                        }
                    }
                }
            }
            def.popup?.forEach {
                when (it) {
                    // TODO: gesture processing middleware
                    is KeyDef.Popup.Menu -> {
                        setOnLongClickListener { view ->
                            view as KeyView
                            onPopupAction(PopupAction.ShowMenuAction(view.id, it, view.bounds))
                            // do not consume this LongClick gesture
                            false
                        }
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        swipeEnabled = true
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(view.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(view.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Keyboard -> {
                        setOnLongClickListener { view ->
                            view as KeyView
                            onPopupAction(PopupAction.ShowKeyboardAction(view.id, it, view.bounds))
                            // do not consume this LongClick gesture
                            false
                        }
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        swipeEnabled = true
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(view.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(view.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.AltPreview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, it.content, view.bounds)
                                    )
                                    GestureType.Move -> {
                                        val triggered = swipeSymbolDirection.checkY(event.totalY)
                                        val text = if (triggered) it.alternative else it.content
                                        onPopupAction(
                                            PopupAction.PreviewUpdateAction(view.id, text)
                                        )
                                    }
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Preview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, it.content, view.bounds)
                                    )
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                    else -> {}
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val (x, y) = intArrayOf(0, 0).also { getLocationInWindow(it) }
        bounds.set(x, y, x + width, y + height)
        glideGeometry = null
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) glideGeometry = null
    }

    // ---------------------------------------------------------------- glide typing

    /** which word list glide typing decodes with; null disables glide typing on this keyboard */
    protected open val glideMode: GlideTyping.Mode? get() = null

    /** called with the finished gesture (points in this view's coordinates) */
    protected open fun onGlideGesture(xy: FloatArray, n: Int, geometry: GlideTyping.Geometry) {}

    private enum class GlideState { Idle, Tracking, Gliding, Rejected }

    private val glideTyping = prefs.keyboard.glideTyping
    private val glideTrail by prefs.keyboard.glideTypingTrail
    private var glideState = GlideState.Idle
    private var glidePointerId = -1
    private var glideDownTime = 0L
    private var glideStartKey: KeyView? = null
    private var glideLeftStart = false
    private var glideXY = FloatArray(1024)
    private var glideT = LongArray(512)
    private var glideN = 0
    private var glidePathLen = 0f
    private var glideGeometry: GlideTyping.Geometry? = null
    private val glideRect = Rect()
    private val glideSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var trailVisible = false
    private val trailWidth = dp(6.5f)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // same accent as the long-press focus circle: clearly visible on light and dark themes
        color = theme.genericActiveBackgroundColor or 0xFF000000.toInt()
    }
    private val trailAlpha = 190
    private val trailPath = android.graphics.Path()
    private var trailL = FloatArray(512)
    private var trailR = FloatArray(512)

    private fun keyRectOf(v: View, out: Rect) {
        out.set(0, 0, v.width, v.height)
        offsetDescendantRectToMyCoords(v, out)
    }

    private fun letterKeyAt(x: Float, y: Float): KeyView? {
        for (v in letterKeys.keys) {
            if (!v.isShown) continue
            keyRectOf(v, glideRect)
            if (glideRect.contains(x.toInt(), y.toInt())) return v
        }
        return null
    }

    private fun glideGeometry(): GlideTyping.Geometry? {
        glideGeometry?.let { return it }
        if (letterKeys.isEmpty()) return null
        val cx = FloatArray(26)
        val cy = FloatArray(26)
        val present = BooleanArray(26)
        val ws = ArrayList<Int>()
        val hs = ArrayList<Int>()
        letterKeys.forEach { (v, c) ->
            if (v.width == 0) return@forEach
            keyRectOf(v, glideRect)
            val i = c - 'a'
            cx[i] = glideRect.exactCenterX()
            cy[i] = glideRect.exactCenterY()
            present[i] = true
            ws.add(glideRect.width())
            hs.add(glideRect.height())
        }
        if (ws.isEmpty()) return null
        ws.sort(); hs.sort()
        return GlideTyping.Geometry(cx, cy, present, ws[ws.size / 2].toFloat(), hs[hs.size / 2].toFloat())
            .also { glideGeometry = it }
    }

    private fun glideAdd(x: Float, y: Float, t: Long) {
        if (glideN > 0) {
            val px = glideXY[2 * glideN - 2]
            val py = glideXY[2 * glideN - 1]
            val d = hypot(x - px, y - py)
            if (d < 1f) return
            glidePathLen += d
        }
        if (2 * glideN + 2 > glideXY.size) glideXY = glideXY.copyOf(glideXY.size * 2)
        if (glideN + 1 > glideT.size) glideT = glideT.copyOf(glideT.size * 2)
        glideXY[2 * glideN] = x
        glideXY[2 * glideN + 1] = y
        glideT[glideN] = t
        glideN++
    }

    private fun glideAddMove(ev: MotionEvent, i: Int) {
        for (h in 0 until ev.historySize) {
            glideAdd(ev.getHistoricalX(i, h), ev.getHistoricalY(i, h), ev.getHistoricalEventTime(h))
        }
        glideAdd(ev.getX(i), ev.getY(i), ev.eventTime)
    }

    private fun glideCheckStart(ev: MotionEvent) {
        val start = glideStartKey ?: return
        val x = glideXY[2 * glideN - 2]
        val y = glideXY[2 * glideN - 1]
        if (!glideLeftStart) {
            // finger stayed on the key long enough for a long press: leave it to the key
            if (ev.eventTime - glideDownTime >= CustomGestureView.longPressDelay) {
                glideState = GlideState.Rejected
                return
            }
            keyRectOf(start, glideRect)
            glideRect.inset(-glideSlop, -glideSlop)
            if (glideRect.contains(x.toInt(), y.toInt())) return
            glideLeftStart = true
        }
        val g = glideGeometry() ?: return
        val dx = (x - glideXY[0]).absoluteValue
        val over = letterKeyAt(x, y)
        // a mostly vertical flick stays a swipe-for-symbol gesture
        if ((over != null && over !== start && dx > 0.5f * g.keyW) || glidePathLen > 3f * g.keyW) {
            beginGlide(ev)
        }
    }

    private fun beginGlide(ev: MotionEvent) {
        glideState = GlideState.Gliding
        // cancel whatever the key under the finger was doing (preview bubble, long press, swipe)
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        trailVisible = glideTrail
        invalidate()
    }

    private fun finishGlide(commit: Boolean) {
        glideState = GlideState.Idle
        if (commit && glideN >= 2) {
            glideGeometry()?.let { onGlideGesture(glideXY.copyOf(2 * glideN), glideN, it) }
        }
        invalidate()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val mode = glideMode
        if (mode == null || (glideState != GlideState.Gliding && !glideTyping.getValue())) {
            return super.dispatchTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                glideN = 0
                glidePathLen = 0f
                glideLeftStart = false
                trailVisible = false
                val key = letterKeyAt(ev.x, ev.y)
                glideStartKey = key
                glidePointerId = ev.getPointerId(0)
                glideDownTime = ev.eventTime
                glideState =
                    // the word list may still be loading: the gesture is decoded once it is ready
                    if (key != null) GlideState.Tracking else GlideState.Rejected
                glideAdd(ev.x, ev.y, ev.eventTime)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (glideState == GlideState.Gliding) return true
                glideState = GlideState.Rejected
            }
            MotionEvent.ACTION_MOVE -> {
                val i = ev.findPointerIndex(glidePointerId)
                when (glideState) {
                    GlideState.Tracking -> if (i >= 0) {
                        glideAddMove(ev, i)
                        glideCheckStart(ev)
                        if (glideState == GlideState.Gliding) return true
                    }
                    GlideState.Gliding -> {
                        if (i >= 0) glideAddMove(ev, i)
                        if (trailVisible) invalidate()
                        return true
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (glideState == GlideState.Gliding) {
                if (ev.getPointerId(ev.actionIndex) == glidePointerId) {
                    glideAdd(ev.getX(ev.actionIndex), ev.getY(ev.actionIndex), ev.eventTime)
                    finishGlide(true)
                }
                return true
            }
            MotionEvent.ACTION_UP -> if (glideState == GlideState.Gliding) {
                glideAdd(ev.x, ev.y, ev.eventTime)
                finishGlide(true)
                return true
            } else {
                glideState = GlideState.Idle
            }
            MotionEvent.ACTION_CANCEL -> if (glideState == GlideState.Gliding) {
                finishGlide(false)
                return true
            } else {
                glideState = GlideState.Idle
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!trailVisible || glideN < 2) return
        val now = SystemClock.uptimeMillis()
        // first point that is still visible
        var first = glideN
        for (i in 0 until glideN) if (now - glideT[i] < TrailMillis) { first = i; break }
        val count = glideN - first
        if (count < 2) {
            if (glideState != GlideState.Gliding) trailVisible = false
            else postInvalidateOnAnimation()
            return
        }
        if (trailL.size < 2 * count) {
            trailL = FloatArray(4 * count); trailR = FloatArray(4 * count)
        }
        val xy = glideXY
        // a tapered ribbon: offset every point along its normal by half of its width (older = thinner)
        for (k in 0 until count) {
            val i = first + k
            val a = maxOf(i - 1, first)
            val b = minOf(i + 1, glideN - 1)
            var nx = -(xy[2 * b + 1] - xy[2 * a + 1])
            var ny = xy[2 * b] - xy[2 * a]
            val len = hypot(nx, ny)
            if (len > 0f) { nx /= len; ny /= len } else { nx = 0f; ny = 0f }
            val f = 1f - (now - glideT[i]).toFloat() / TrailMillis
            val half = trailWidth * 0.5f * f.coerceIn(0f, 1f)
            trailL[2 * k] = xy[2 * i] + nx * half; trailL[2 * k + 1] = xy[2 * i + 1] + ny * half
            trailR[2 * k] = xy[2 * i] - nx * half; trailR[2 * k + 1] = xy[2 * i + 1] - ny * half
        }
        trailPath.rewind()
        trailPath.moveTo(trailL[0], trailL[1])
        for (k in 1 until count) trailPath.lineTo(trailL[2 * k], trailL[2 * k + 1])
        for (k in count - 1 downTo 0) trailPath.lineTo(trailR[2 * k], trailR[2 * k + 1])
        trailPath.close()
        val hx = xy[2 * glideN - 2]
        val hy = xy[2 * glideN - 1]
        val headF = (1f - (now - glideT[glideN - 1]).toFloat() / TrailMillis).coerceIn(0f, 1f)
        // draw everything opaque into one layer, then apply the alpha once: no darker joints
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), trailAlpha)
        canvas.drawPath(trailPath, trailPaint)
        canvas.drawCircle(hx, hy, trailWidth * 0.5f * headF, trailPaint)
        canvas.restoreToCount(save)
        postInvalidateOnAnimation()
    }

    companion object {
        private const val TrailMillis = 320L
    }

    private fun findTargetChild(x: Float, y: Float): View? {
        val y0 = y.roundToInt()
        // assume all rows have equal height
        val row = keyRows.getOrNull(y0 * keyRows.size / bounds.height()) ?: return null
        val x1 = x.roundToInt() + bounds.left
        val y1 = y0 + bounds.top
        return row.children.find {
            if (it !is KeyView) false else it.bounds.contains(x1, y1)
        }
    }

    private fun transformMotionEventToChild(
        child: View,
        event: MotionEvent,
        action: Int,
        pointerIndex: Int
    ): MotionEvent {
        if (child !is KeyView) {
            Timber.w("child view is not KeyView when transforming MotionEvent $event")
            return event
        }
        val childX = event.getX(pointerIndex) + bounds.left - child.bounds.left
        val childY = event.getY(pointerIndex) + bounds.top - child.bounds.top
        return MotionEvent.obtain(
            event.downTime, event.eventTime, action,
            childX, childY, event.getPressure(pointerIndex), event.getSize(pointerIndex),
            event.metaState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags
        )
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // intercept ACTION_DOWN and all following events will go to parent's onTouchEvent
        return if (vivoKeypressWorkaround && ev.actionMasked == MotionEvent.ACTION_DOWN) true
        else super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (vivoKeypressWorkaround) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val target = findTargetChild(event.x, event.y) ?: return false
                    touchTarget[event.getPointerId(0)] = target
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_DOWN, 0)
                    )
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = event.actionIndex
                    val target = findTargetChild(event.getX(i), event.getY(i)) ?: return false
                    touchTarget[event.getPointerId(i)] = target
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_DOWN, i)
                    )
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val target = touchTarget[event.getPointerId(i)] ?: continue
                        target.dispatchTouchEvent(
                            transformMotionEventToChild(target, event, MotionEvent.ACTION_MOVE, i)
                        )
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTarget[event.getPointerId(i)] ?: return false
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_UP, i)
                    )
                    touchTarget.remove(pid)
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTarget[event.getPointerId(i)] ?: return false
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_UP, i)
                    )
                    touchTarget.remove(pid)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTarget[pid] ?: return false
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_CANCEL, i)
                    )
                    touchTarget.remove(pid)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    @CallSuper
    protected open fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source = KeyActionListener.Source.Keyboard
    ) {
        keyActionListener?.onKeyAction(action, source)
    }

    @CallSuper
    protected open fun onPopupAction(action: PopupAction) {
        popupActionListener?.onPopupAction(action)
    }

    private fun onPopupChangeFocus(viewId: Int, x: Float, y: Float): Boolean {
        val changeFocusAction = PopupAction.ChangeFocusAction(viewId, x, y)
        popupActionListener?.onPopupAction(changeFocusAction)
        return changeFocusAction.outResult
    }

    private fun onPopupTrigger(viewId: Int): Boolean {
        val triggerAction = PopupAction.TriggerAction(viewId)
        // ask popup keyboard whether there's a pending KeyAction
        onPopupAction(triggerAction)
        val action = triggerAction.outAction ?: return false
        onAction(action, KeyActionListener.Source.Popup)
        onPopupAction(PopupAction.DismissAction(viewId))
        return true
    }

    open fun onAttach() {
        // do nothing by default
    }

    open fun onReturnDrawableUpdate(@DrawableRes returnDrawable: Int) {
        // do nothing by default
    }

    open fun onPunctuationUpdate(mapping: Map<String, String>) {
        // do nothing by default
    }

    open fun onInputMethodUpdate(ime: InputMethodEntry) {
        // do nothing by default
    }

    open fun onDetach() {
        // do nothing by default
    }

}