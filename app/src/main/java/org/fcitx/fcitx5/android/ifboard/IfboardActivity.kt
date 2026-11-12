/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard settings page: WebDAV dictionary sync, grammar model download / update, and update check.
 */
package org.fcitx.fcitx5.android.ifboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IfboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Ifboard_Page)
        super.onCreate(savedInstanceState)
        title = "同步与更新"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // edge-to-edge: keep content below the status bar and the action bar
        val content = findViewById<android.view.View>(android.R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val tv = android.util.TypedValue()
            val ab = if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true))
                android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics) else 0
            v.setPadding(bars.left, bars.top + ab, bars.right, 0)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, IfboardFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    companion object {
        /** shows a dialog when GitHub has a newer Ifboard; quiet when up to date unless [verbose] */
        fun checkUpdate(activity: AppCompatActivity, verbose: Boolean) {
            activity.lifecycleScope.launch {
                runCatching { IfboardSync.checkUpdate() }
                    .onSuccess { rel ->
                        if (rel == null) {
                            if (verbose) Toast.makeText(activity, "已是最新版本（${BuildConfig.VERSION_NAME}）", Toast.LENGTH_SHORT).show()
                            return@onSuccess
                        }
                        AlertDialog.Builder(activity)
                            .setTitle("发现新版本 ${rel.version}")
                            .setMessage(rel.notes.ifBlank { "有新版本可以更新。" })
                            .setPositiveButton("下载") { _, _ ->
                                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apkUrl ?: rel.url)))
                            }
                            .setNegativeButton("以后再说", null)
                            .show()
                    }
                    .onFailure { if (verbose) Toast.makeText(activity, it.message ?: "检查更新失败", Toast.LENGTH_SHORT).show() }
            }
        }

        fun autoCheck(activity: AppCompatActivity) {
            val sp = PreferenceManager.getDefaultSharedPreferences(activity)
            if (sp.getBoolean("ifb_auto_check_update", true)) checkUpdate(activity, verbose = false)
        }
    }
}

class IfboardFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            val dav = PreferenceCategory(ctx).apply { title = "WebDAV 词库同步"; isIconSpaceReserved = false }
            addPreference(dav)
            fun text(key: String, t: String, default: String = "", password: Boolean = false) =
                EditTextPreference(ctx).apply {
                    this.key = key; title = t; isIconSpaceReserved = false
                    setDefaultValue(default)
                    dialogTitle = t
                    if (password) {
                        summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                            if (it.text.isNullOrEmpty()) "未设置" else "••••••••"
                        }
                        setOnBindEditTextListener {
                            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        }
                    } else {
                        summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                            if (it.text.isNullOrEmpty()) "未设置" else it.text
                        }
                    }
                }
            dav.addPreference(text("ifb_webdav_url", "服务器地址"))
            dav.addPreference(text("ifb_webdav_user", "账号"))
            dav.addPreference(text("ifb_webdav_password", "密码", password = true))
            dav.addPreference(text("ifb_webdav_dir", "文件夹", "Ifboard"))
            dav.addPreference(Preference(ctx).apply {
                key = "ifb_webdav_sync_now"; title = "立即同步"; isIconSpaceReserved = false
                summary = lastSyncText(ctx)
                setOnPreferenceClickListener {
                    summary = "正在同步…"
                    lifecycleScope.launch {
                        runCatching {
                            IfboardSync.webdavSync(
                                ctx,
                                sp.getString("ifb_webdav_url", "").orEmpty(),
                                sp.getString("ifb_webdav_user", "").orEmpty(),
                                sp.getString("ifb_webdav_password", "").orEmpty(),
                                sp.getString("ifb_webdav_dir", "Ifboard").orEmpty()
                            )
                        }.onSuccess { r ->
                            sp.edit().putLong("ifb_webdav_last", System.currentTimeMillis()).apply()
                            Toast.makeText(ctx, "已同步：上传 ${r.uploaded} 个，合并 ${r.downloaded} 个（${r.devices.size} 台其他设备）", Toast.LENGTH_LONG).show()
                        }.onFailure {
                            Toast.makeText(ctx, it.message ?: "同步失败", Toast.LENGTH_LONG).show()
                        }
                        summary = lastSyncText(ctx)
                    }
                    true
                }
            })

            val gram = PreferenceCategory(ctx).apply { title = "语法模型"; isIconSpaceReserved = false }
            addPreference(gram)
            gramStatus = Preference(ctx).apply {
                key = "ifb_gram_status"; title = "万象语法模型"; isIconSpaceReserved = false
                setOnPreferenceClickListener { onGramStatusClick(); true }
            }
            gram.addPreference(gramStatus!!)
            gram.addPreference(Preference(ctx).apply {
                key = "ifb_gram_update"; title = "检查语法模型更新"; isIconSpaceReserved = false
                summary = "从万象官方发布页检查是否有新版本"
                setOnPreferenceClickListener { checkGramUpdate(); true }
            })
            gramDelete = Preference(ctx).apply {
                key = "ifb_gram_delete"; title = "删除语法模型"; isIconSpaceReserved = false
                summary = "释放约 400 MB 空间，候选排序会回到不用语法模型的状态"
                setOnPreferenceClickListener {
                    AlertDialog.Builder(ctx)
                        .setTitle("删除语法模型？")
                        .setMessage("删除后需要重新下载才能使用。")
                        .setPositiveButton("删除") { _, _ -> GrammarModel.delete(ctx); renderGram(GrammarModel.state.value) }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            }
            gram.addPreference(gramDelete!!)

            val upd = PreferenceCategory(ctx).apply { title = "更新"; isIconSpaceReserved = false }
            addPreference(upd)
            upd.addPreference(Preference(ctx).apply {
                key = "ifb_check_update"; title = "检查更新"; isIconSpaceReserved = false
                summary = "当前版本 ${BuildConfig.VERSION_NAME}"
                setOnPreferenceClickListener {
                    IfboardActivity.checkUpdate(requireActivity() as AppCompatActivity, verbose = true); true
                }
            })
            upd.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = "ifb_auto_check_update"; title = "自动检查更新"; isIconSpaceReserved = false
                setDefaultValue(true)
            })
            upd.addPreference(Preference(ctx).apply {
                title = "GitHub 仓库"; isIconSpaceReserved = false
                summary = "github.com/${IfboardSync.REPO}"
                setOnPreferenceClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${IfboardSync.REPO}"))); true
                }
            })
        }
    }

    private var gramStatus: Preference? = null
    private var gramDelete: Preference? = null

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                GrammarModel.state.collect { renderGram(it) }
            }
        }
    }

    private fun renderGram(s: GrammarModel.State) {
        val ctx = context ?: return
        val installed = GrammarModel.installed(ctx)
        gramStatus?.summary = when (s) {
            is GrammarModel.State.Checking -> "正在连接官方发布页…"
            is GrammarModel.State.Downloading ->
                "正在下载 ${GrammarModel.mb(s.done)} / ${GrammarModel.mb(s.total)}（${s.done * 100 / s.total.coerceAtLeast(1)}%），点按可取消"
            is GrammarModel.State.Verifying -> "正在校验…"
            is GrammarModel.State.Failed -> "下载失败：${s.message}。点按重试（会接着上次的进度）"
            else -> if (installed) {
                val v = GrammarModel.installedVersion(ctx)?.take(10)
                "已安装（${GrammarModel.mb(GrammarModel.file(ctx).length())}${if (v != null) "，官方 $v 版" else ""}）"
            } else "未下载。点按下载（约 400 MB，建议连接 Wi-Fi）"
        }
        gramDelete?.isEnabled = installed && !GrammarModel.busy
    }

    private fun onGramStatusClick() {
        val ctx = requireContext()
        when {
            GrammarModel.busy -> AlertDialog.Builder(ctx)
                .setTitle("取消下载？")
                .setMessage("已下载的部分会保留，下次接着下载。")
                .setPositiveButton("取消下载") { _, _ -> GrammarModel.cancel() }
                .setNegativeButton("继续下载", null)
                .show()
            !GrammarModel.installed(ctx) -> GrammarModel.start(ctx)
            else -> checkGramUpdate()
        }
    }

    private fun checkGramUpdate() {
        val ctx = requireContext()
        if (GrammarModel.busy) return
        Toast.makeText(ctx, "正在检查…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching { GrammarModel.fetchRemote() }
                .onSuccess { r ->
                    if (!GrammarModel.isNewer(ctx, r)) {
                        Toast.makeText(ctx, "语法模型已是最新（官方 ${r.updated.take(10)} 版）", Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    val installed = GrammarModel.installed(ctx)
                    AlertDialog.Builder(ctx)
                        .setTitle(if (installed) "语法模型有新版本" else "下载语法模型")
                        .setMessage(
                            "官方 ${r.updated.take(10)} 版，${GrammarModel.mb(r.size)}。" +
                                    (if (installed) "\n\n下载完成前继续使用旧模型，完成后自动替换。" else "") +
                                    "\n\n建议连接 Wi-Fi 后下载。"
                        )
                        .setPositiveButton(if (installed) "更新" else "下载") { _, _ -> GrammarModel.start(ctx) }
                        .setNegativeButton("以后再说", null)
                        .show()
                }
                .onFailure { Toast.makeText(ctx, it.message ?: "检查失败", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun lastSyncText(ctx: Context): String {
        val t = PreferenceManager.getDefaultSharedPreferences(ctx).getLong("ifb_webdav_last", 0)
        return if (t == 0L) "还没有同步过"
        else "上次同步：" + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(t))
    }
}
