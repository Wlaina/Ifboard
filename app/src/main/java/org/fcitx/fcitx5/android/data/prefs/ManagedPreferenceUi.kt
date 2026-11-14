/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.TypoCorrector
import org.fcitx.fcitx5.android.ui.common.showWarm
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.ui.main.settings.DialogSeekBarPreference
import org.fcitx.fcitx5.android.ui.main.settings.EditTextIntPreference
import org.fcitx.fcitx5.android.ui.main.settings.TwinSeekBarPreference
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.includes
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

abstract class ManagedPreferenceUi<T : Preference>(
    val key: String,
    private val enableUiOn: (() -> Boolean)? = null
) {

    abstract fun createUi(context: Context): T

    fun isEnabled() = enableUiOn?.invoke() ?: true

    /** Ifboard: what typo correction learned about the user's touch offsets, with a reset */
    class TouchModel(
        @StringRes
        val title: Int,
        key: String
    ) : ManagedPreferenceUi<Preference>(key) {

        private fun summaryOf(context: Context): String {
            val m = TypoCorrector.snapshot(context)
            if (m.samples == 0) return context.getString(R.string.touch_model_summary_empty)
            // average over the keys that have learned something: unused keys would only dilute it
            var sum = 0f
            var keys = 0
            for (i in 0 until 26) {
                val d = hypot(m.offX[i], m.offY[i])
                if (d > 1e-3f) { sum += d; keys++ }
            }
            val avg = if (keys > 0) sum / keys else 0f
            return context.getString(R.string.touch_model_summary, m.samples, (avg * 100).roundToInt())
        }

        private fun details(context: Context): String {
            val m = TypoCorrector.snapshot(context)
            val keys = (0 until 26).map { it to hypot(m.offX[it], m.offY[it]) }
                .filter { it.second >= 0.03f }
                .sortedByDescending { it.second }
                .take(8)
            if (keys.isEmpty()) return context.getString(R.string.touch_model_centered)
            return buildString {
                append(context.getString(R.string.touch_model_detail_header))
                for ((k, _) in keys) {
                    append('\n').append(('a' + k).uppercaseChar()).append("    ")
                    val parts = ArrayList<String>(2)
                    val x = (m.offX[k] * 100).roundToInt()
                    val y = (m.offY[k] * 100).roundToInt()
                    if (abs(x) >= 3) parts.add(
                        context.getString(if (x > 0) R.string.touch_model_right else R.string.touch_model_left, abs(x))
                    )
                    if (abs(y) >= 3) parts.add(
                        context.getString(if (y > 0) R.string.touch_model_down else R.string.touch_model_up, abs(y))
                    )
                    append(parts.joinToString("  "))
                }
            }
        }

        override fun createUi(context: Context) = Preference(context).apply {
            key = this@TouchModel.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            isPersistent = false
            setTitle(this@TouchModel.title)
            summary = summaryOf(context)
            setOnPreferenceClickListener { pref ->
                val learned = TypoCorrector.snapshot(context).samples > 0
                val builder = AlertDialog.Builder(context)
                    .setTitle(this@TouchModel.title)
                    .setMessage(if (learned) details(context) else summaryOf(context))
                    .setPositiveButton(android.R.string.ok, null)
                if (learned) {
                    builder.setNeutralButton(R.string.touch_model_reset) { _, _ ->
                        TypoCorrector.resetTouchModel(context)
                        pref.summary = summaryOf(context)
                        Toast.makeText(context, R.string.touch_model_reset_done, Toast.LENGTH_SHORT).show()
                    }
                }
                // same Warm Paper look as the other settings dialogs (and readable in dark mode)
                builder.create().showWarm()
                true
            }
        }
    }

    class Switch(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Boolean,
        @StringRes
        val summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<MySwitchPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = MySwitchPreference(context).apply {
            key = this@Switch.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setDefaultValue(defaultValue)
            if (this@Switch.summary != null)
                setSummary(this@Switch.summary)
            setTitle(this@Switch.title)
        }
    }

    class StringList<T : Any>(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: T,
        val codec: ManagedPreference.StringLikeCodec<T>,
        val entryValues: List<T>,
        @StringRes
        val entryLabels: List<Int>,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<ListPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = ListPreference(context).apply {
            key = this@StringList.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            entryValues = this@StringList.entryValues.map { codec.encode(it) }.toTypedArray()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setDefaultValue(codec.encode(defaultValue))
            setTitle(this@StringList.title)
            entries = this@StringList.entryLabels.map { context.getString(it) }.toTypedArray()
            setDialogTitle(this@StringList.title)
        }
    }

    class VoiceInputList(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: String,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<ListPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = ListPreference(context).apply {
            key = this@VoiceInputList.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setDefaultValue(defaultValue)
            setTitle(this@VoiceInputList.title)
            setDialogTitle(this@VoiceInputList.title)
            val voiceInputMethods = InputMethodUtil.listVoiceInputMethods()
            entryValues = arrayOf("", *voiceInputMethods.map { it.first.id }.toTypedArray())
            entries = arrayOf(
                context.getString(R.string.system_default),
                *voiceInputMethods.map { it.first.loadLabel(context.packageManager) }.toTypedArray()
            )
            // shows "(Not Available)" if selected id is not present
            summaryProvider = Preference.SummaryProvider<ListPreference> { preference ->
                if (preference.entryValues.includes(preference.value)) {
                    preference.entry
                } else {
                    context.getString(R.string._not_available_)
                }
            }
        }
    }

    class EditTextInt(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<EditTextPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = EditTextIntPreference(context).apply {
            key = this@EditTextInt.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = EditTextIntPreference.SimpleSummaryProvider
            setDefaultValue(this@EditTextInt.defaultValue)
            setTitle(this@EditTextInt.title)
            setDialogTitle(this@EditTextInt.title)
            min = this@EditTextInt.min
            max = this@EditTextInt.max
            unit = this@EditTextInt.unit
        }
    }

    class SeekBarInt(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        val step: Int = 1,
        @StringRes
        val defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<DialogSeekBarPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = DialogSeekBarPreference(context).apply {
            key = this@SeekBarInt.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
            this@SeekBarInt.defaultLabel?.let { defaultLabel = context.getString(it) }
            setDefaultValue(this@SeekBarInt.defaultValue)
            setTitle(this@SeekBarInt.title)
            setDialogTitle(this@SeekBarInt.title)
            min = this@SeekBarInt.min
            max = this@SeekBarInt.max
            unit = this@SeekBarInt.unit
            step = this@SeekBarInt.step
        }
    }

    class TwinSeekBarInt(
        @StringRes
        val title: Int,
        @StringRes
        val label: Int,
        key: String,
        val defaultValue: Int,
        @StringRes
        val secondaryLabel: Int,
        val secondaryKey: String,
        val secondaryDefaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        val step: Int = 1,
        @StringRes
        val defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<TwinSeekBarPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = TwinSeekBarPreference(context).apply {
            setTitle(this@TwinSeekBarInt.title)
            setDialogTitle(this@TwinSeekBarInt.title)
            label = context.getString(this@TwinSeekBarInt.label)
            key = this@TwinSeekBarInt.key
            secondaryLabel = context.getString(this@TwinSeekBarInt.secondaryLabel)
            secondaryKey = this@TwinSeekBarInt.secondaryKey
            this@TwinSeekBarInt.defaultLabel?.let { defaultLabel = context.getString(it) }
            setDefaultValue(this@TwinSeekBarInt.defaultValue to this@TwinSeekBarInt.secondaryDefaultValue)
            min = this@TwinSeekBarInt.min
            max = this@TwinSeekBarInt.max
            unit = this@TwinSeekBarInt.unit
            step = this@TwinSeekBarInt.step
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = TwinSeekBarPreference.SimpleSummaryProvider
        }
    }
}