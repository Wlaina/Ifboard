/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard 设置页（暖纸 · 液态玻璃）：WebDAV（默认 Koofr + Koofr/坚果云切换）、语法模型与版本检查
 */
package org.fcitx.fcitx5.android.ifboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.input.keyboard.TypoCorrector
import org.fcitx.fcitx5.android.ui.common.MonetBackgroundView
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.WarmPaperUi
import org.fcitx.fcitx5.android.ui.common.showWarm
import splitties.views.topPadding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class IfboardActivity : AppCompatActivity() {

    private var monetBg: MonetBackgroundView? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> monetBg?.pauseTemporarily()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Ifboard_Page)
        super.onCreate(savedInstanceState)
        WarmPaperUi.enableEdgeToEdge(this)

        val root = FrameLayout(this)
        val monet = MonetBackgroundView(this).apply { id = R.id.monet_bg }
        monetBg = monet
        val container = FragmentContainerView(this).apply { id = android.R.id.list_container }
        val toolbar = Toolbar(this).apply {
            id = R.id.toolbar
            elevation = 0f
            setTitleTextAppearance(this@IfboardActivity, R.style.TextAppearance_Ifboard_Title)
        }

        root.addView(monet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(container, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(toolbar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            container.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            toolbar.setPadding(navBars.left, statusBars.top, navBars.right, 0)
            windowInsets
        }

        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "同步与更新"
        WarmPaperUi.styleGlassToolbar(toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(container.id, IfboardFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private fun formatReleaseNotes(raw: String): String {
            val cleaned = raw.lines().mapNotNull { line ->
                val s = line.trimEnd()
                val t = s.trim()
                when {
                    t.startsWith("### ") -> "【${t.removePrefix("### ").trim()}】"
                    t.startsWith("## ") -> "【${t.removePrefix("## ").trim()}】"
                    t.startsWith("# ") -> "【${t.removePrefix("# ").trim()}】"
                    t == "---" || t == "***" -> null
                    else -> s
                        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
                        .replace(Regex("""`([^`]+)`"""), "$1")
                        .let { if (it.startsWith("- ")) "• " + it.removePrefix("- ") else it }
                }
            }.joinToString("\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
            return if (cleaned.length > 900) cleaned.take(900).trimEnd() + "\n..." else cleaned
        }

        /** 检查 GitHub Releases 新版本 */
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
                            .setMessage(formatReleaseNotes(rel.notes).ifBlank { "有新版本可以更新。" })
                            .setPositiveButton("下载") { _, _ ->
                                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apkUrl ?: rel.url)))
                            }
                            .setNegativeButton("以后再说", null)
                            .create()
                            .showWarm()
                    }
                    .onFailure {
                        if (verbose) Toast.makeText(activity, it.message ?: "检查更新失败", Toast.LENGTH_SHORT).show()
                    }
            }
        }

        fun autoCheck(activity: AppCompatActivity) {
            val sp = PreferenceManager.getDefaultSharedPreferences(activity)
            if (sp.getBoolean("ifb_auto_check_update", true)) checkUpdate(activity, verbose = false)
        }
    }
}

class IfboardFragment : PaddingPreferenceFragment() {

    private var urlPref: EditTextPreference? = null
    private var providerPref: WebDavProviderPreference? = null
    private var testConnPref: Preference? = null
    private var syncNowPref: Preference? = null
    private var touchPref: Preference? = null
    private var smsOtpPref: SwitchPreferenceCompat? = null
    private var gramStatus: Preference? = null
    private var gramDelete: Preference? = null
    private var voiceStatus: Preference? = null
    private var voiceDelete: Preference? = null
    private var syncing = false
    private var testing = false

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshSmsSummary()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        // 默认服务器设为 Koofr
        if (sp.getString("ifb_webdav_url", null).isNullOrBlank()) {
            sp.edit().putString("ifb_webdav_url", IfboardSync.DEFAULT_WEBDAV_URL).apply()
        }

        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            val dav = PreferenceCategory(ctx).apply { title = "WebDAV 词库与习惯同步"; isIconSpaceReserved = false }
            addPreference(dav)

            val prov = WebDavProviderPreference(ctx).apply {
                key = "ifb_webdav_provider"
                title = "WebDAV 服务商"
                isIconSpaceReserved = false
                currentUrl = sp.getString("ifb_webdav_url", IfboardSync.DEFAULT_WEBDAV_URL).orEmpty()
                onSelectUrl = { chosenUrl ->
                    sp.edit().putString("ifb_webdav_url", chosenUrl).apply()
                    urlPref?.text = chosenUrl
                }
            }
            providerPref = prov
            dav.addPreference(prov)

            fun text(key: String, t: String, default: String = "", password: Boolean = false) =
                EditTextPreference(ctx).apply {
                    this.key = key
                    title = t
                    isIconSpaceReserved = false
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

            val urlEdit = text("ifb_webdav_url", "服务器地址", IfboardSync.DEFAULT_WEBDAV_URL).apply {
                setOnPreferenceChangeListener { _, newValue ->
                    providerPref?.updateSelectedUrl(newValue?.toString().orEmpty())
                    true
                }
            }
            urlPref = urlEdit
            dav.addPreference(urlEdit)
            dav.addPreference(text("ifb_webdav_user", "账号"))
            dav.addPreference(text("ifb_webdav_password", "密码", password = true))
            dav.addPreference(text("ifb_webdav_dir", "文件夹", "Ifboard"))

            val testConn = Preference(ctx).apply {
                key = "ifb_webdav_test"
                title = "测试连接"
                summary = "验证 WebDAV 凭证与云端触摸习惯状态"
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    if (testing) return@setOnPreferenceClickListener true
                    testing = true
                    summary = "正在测试连接…"
                    lifecycleScope.launch {
                        try {
                            runCatching {
                                IfboardSync.testConnection(
                                    sp.getString("ifb_webdav_url", IfboardSync.DEFAULT_WEBDAV_URL).orEmpty(),
                                    sp.getString("ifb_webdav_user", "").orEmpty(),
                                    sp.getString("ifb_webdav_password", "").orEmpty(),
                                    sp.getString("ifb_webdav_dir", "Ifboard").orEmpty()
                                )
                            }.onSuccess { r ->
                                val touchDesc = if (r.hasCloudTouchModel) {
                                    "云端触摸习惯 ${r.cloudTouchSamples} 条样本"
                                } else {
                                    "云端暂无触摸习惯"
                                }
                                summary = "连接成功 · ${r.deviceDirs.size} 台设备，$touchDesc"
                                Toast.makeText(ctx, summary, Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                summary = "测试失败：${it.message ?: "请检查网络或账号"}"
                                Toast.makeText(ctx, summary, Toast.LENGTH_LONG).show()
                            }
                        } finally {
                            testing = false
                        }
                    }
                    true
                }
            }
            testConnPref = testConn
            dav.addPreference(testConn)

            val syncNow = Preference(ctx).apply {
                key = "ifb_webdav_sync_now"
                title = "立即同步"
                isIconSpaceReserved = false
                summary = syncSummary(ctx, null)
                setOnPreferenceClickListener {
                    if (syncing) return@setOnPreferenceClickListener true
                    syncing = true
                    summary = syncSummary(ctx, "正在同步词库与触摸习惯…")
                    lifecycleScope.launch {
                        try {
                            runCatching {
                                IfboardSync.webdavSync(
                                    ctx,
                                    sp.getString("ifb_webdav_url", IfboardSync.DEFAULT_WEBDAV_URL).orEmpty(),
                                    sp.getString("ifb_webdav_user", "").orEmpty(),
                                    sp.getString("ifb_webdav_password", "").orEmpty(),
                                    sp.getString("ifb_webdav_dir", "Ifboard").orEmpty()
                                )
                            }.onSuccess { r ->
                                sp.edit().putLong("ifb_webdav_last", System.currentTimeMillis()).apply()
                                refreshTouchSummary()
                                val touchPart = if (r.touchSynced) "，触摸习惯已同步（${r.touchSamples} 条）" else ""
                                Toast.makeText(
                                    ctx,
                                    "已同步：上传 ${r.uploaded} 个，合并 ${r.downloaded} 个（${r.devices.size} 台其他设备）$touchPart",
                                    Toast.LENGTH_LONG
                                ).show()
                            }.onFailure {
                                Toast.makeText(ctx, it.message ?: "同步失败", Toast.LENGTH_LONG).show()
                            }
                        } finally {
                            syncing = false
                            summary = syncSummary(ctx, null)
                        }
                    }
                    true
                }
            }
            syncNowPref = syncNow
            dav.addPreference(syncNow)

            dav.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = "ifb_auto_sync_daily"
                title = "每日自动同步"
                summary = "开启后每 24 小时在后台自动同步 Rime 词库与触摸习惯"
                isIconSpaceReserved = false
                setDefaultValue(true)
            })

            val smart = PreferenceCategory(ctx).apply { title = "输入方案与智能增强"; isIconSpaceReserved = false }
            addPreference(smart)

            smart.addPreference(Preference(ctx).apply {
                key = "ifb_rime_deploy"
                title = "重新部署 Rime 词库"
                summary = "立即重载输入方案配置、语法模型与同步词库"
                isIconSpaceReserved = false
                setOnPreferenceClickListener { pref ->
                    pref.summary = "正在重新部署…"
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { FcitxDaemon.restartFcitx() }
                        }
                        pref.summary = "立即重载输入方案配置、语法模型与同步词库"
                        Toast.makeText(ctx, "Rime 已重新部署", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            })

            val touchItem = Preference(ctx).apply {
                key = "ifb_touch_model_ctrl"
                title = "触摸习惯模型（邻键纠错）"
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    showTouchModelDialog(ctx)
                    true
                }
            }
            touchPref = touchItem
            smart.addPreference(touchItem)
            refreshTouchSummary()

            val smsSwitch = SwitchPreferenceCompat(ctx).apply {
                key = "ifb_sms_otp_ctrl"
                title = "短信验证码自动复制"
                isIconSpaceReserved = false
                isPersistent = false
                isChecked = SmsCodeHelper.isEnabled(ctx)
                setOnPreferenceChangeListener { _, newValue ->
                    val on = newValue == true
                    SmsCodeHelper.setEnabled(ctx, on)
                    if (on && !SmsCodeHelper.hasSmsPermission(ctx)) {
                        smsPermissionLauncher.launch(
                            arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
                        )
                    }
                    refreshSmsSummary()
                    true
                }
            }
            smsOtpPref = smsSwitch
            smart.addPreference(smsSwitch)
            refreshSmsSummary()

            val gram = PreferenceCategory(ctx).apply { title = "语法模型"; isIconSpaceReserved = false }
            addPreference(gram)
            gramStatus = Preference(ctx).apply {
                key = "ifb_gram_status"
                title = "万象语法模型"
                isIconSpaceReserved = false
                setOnPreferenceClickListener { onGramStatusClick(); true }
            }
            gram.addPreference(gramStatus!!)
            gram.addPreference(Preference(ctx).apply {
                key = "ifb_gram_update"
                title = "检查语法模型更新"
                isIconSpaceReserved = false
                summary = "从万象官方检查更新，支持后台保活与自动断点续传（GitHub 连不上时改用国内镜像）"
                setOnPreferenceClickListener { checkGramUpdate(); true }
            })
            gramDelete = Preference(ctx).apply {
                key = "ifb_gram_delete"
                title = "删除语法模型"
                isIconSpaceReserved = false
                summary = "释放约 400 MB 空间，候选排序会回到不用语法模型的状态"
                setOnPreferenceClickListener {
                    AlertDialog.Builder(ctx)
                        .setTitle("删除语法模型？")
                        .setMessage("删除后需要重新下载才能使用。")
                        .setPositiveButton("删除") { _, _ ->
                            GrammarModel.delete(ctx)
                            renderGram(GrammarModel.state.value)
                        }
                        .setNegativeButton("取消", null)
                        .create()
                        .showWarm(destructive = true)
                    true
                }
            }
            gram.addPreference(gramDelete!!)

            val voice = PreferenceCategory(ctx).apply { title = "离线语音"; isIconSpaceReserved = false }
            addPreference(voice)
            voiceStatus = Preference(ctx).apply {
                key = "ifb_offline_voice_status"
                title = "中英离线语音模型"
                isIconSpaceReserved = false
                setOnPreferenceClickListener { onVoiceStatusClick(); true }
            }
            voice.addPreference(voiceStatus!!)
            voice.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = org.fcitx.fcitx5.android.ifboard.voice.OfflineVoiceModel.PREF_ENABLED
                title = "语音输入优先用离线识别"
                summary = "已下载模型时，中文 / 英文语音在手机本地识别：不联网、声音不离开手机，边说边出字"
                isIconSpaceReserved = false
                setDefaultValue(true)
            })
            voiceDelete = Preference(ctx).apply {
                key = "ifb_offline_voice_delete"
                title = "删除离线语音模型"
                isIconSpaceReserved = false
                summary = "释放空间，语音输入改回系统识别服务"
                setOnPreferenceClickListener {
                    AlertDialog.Builder(ctx)
                        .setTitle("删除离线语音模型？")
                        .setMessage("删除后需要重新下载才能离线识别。")
                        .setPositiveButton("删除") { _, _ ->
                            org.fcitx.fcitx5.android.ifboard.voice.OfflineVoiceModel.delete(ctx)
                            renderVoice(org.fcitx.fcitx5.android.ifboard.voice.OfflineVoiceModel.state.value)
                        }
                        .setNegativeButton("取消", null)
                        .create()
                        .showWarm(destructive = true)
                    true
                }
            }
            voice.addPreference(voiceDelete!!)

            val upd = PreferenceCategory(ctx).apply { title = "更新"; isIconSpaceReserved = false }
            addPreference(upd)
            upd.addPreference(Preference(ctx).apply {
                key = "ifb_check_update"
                title = "检查更新"
                isIconSpaceReserved = false
                summary = "当前版本 ${BuildConfig.VERSION_NAME}（仅匹配 Android 安装包）"
                setOnPreferenceClickListener {
                    IfboardActivity.checkUpdate(requireActivity() as AppCompatActivity, verbose = true)
                    true
                }
            })
            upd.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = "ifb_auto_check_update"
                title = "自动检查更新"
                isIconSpaceReserved = false
                setDefaultValue(true)
            })
            upd.addPreference(Preference(ctx).apply {
                title = "GitHub 仓库"
                isIconSpaceReserved = false
                summary = "github.com/${IfboardSync.REPO}"
                setOnPreferenceClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${IfboardSync.REPO}")))
                    true
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTouchSummary()
        refreshSmsSummary()
    }

    private fun refreshTouchSummary() {
        val ctx = context ?: return
        val m = TypoCorrector.snapshot(ctx)
        if (m.samples == 0) {
            touchPref?.summary = "尚未积累数据，日常打字后自动学习并可随 WebDAV 云同步"
            return
        }
        var sum = 0f
        var keys = 0
        for (i in 0 until 26) {
            val d = hypot(m.offX[i], m.offY[i])
            if (d > 1e-3f) {
                sum += d
                keys++
            }
        }
        val avg = if (keys > 0) sum / keys else 0f
        touchPref?.summary = "已学习 ${m.samples} 次（平均偏移 ${(avg * 100).roundToInt()}% 键宽），可点击查看或重置"
    }

    private fun showTouchModelDialog(ctx: Context) {
        val m = TypoCorrector.snapshot(ctx)
        val msg = if (m.samples == 0) {
            getString(R.string.touch_model_summary_empty)
        } else {
            val keys = (0 until 26).map { it to hypot(m.offX[it], m.offY[it]) }
                .filter { it.second >= 0.03f }
                .sortedByDescending { it.second }
                .take(8)
            if (keys.isEmpty()) {
                getString(R.string.touch_model_centered)
            } else {
                buildString {
                    append(getString(R.string.touch_model_detail_header))
                    for ((k, _) in keys) {
                        append('\n').append(('a' + k).uppercaseChar()).append("    ")
                        val x = (m.offX[k] * 100).roundToInt()
                        val y = (m.offY[k] * 100).roundToInt()
                        val parts = ArrayList<String>(2)
                        if (abs(x) >= 3) parts.add(getString(if (x > 0) R.string.touch_model_right else R.string.touch_model_left, abs(x)))
                        if (abs(y) >= 3) parts.add(getString(if (y > 0) R.string.touch_model_down else R.string.touch_model_up, abs(y)))
                        append(parts.joinToString("  "))
                    }
                }
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.touch_model_title)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .apply {
                if (m.samples > 0) {
                    setNeutralButton(R.string.touch_model_reset) { _, _ ->
                        TypoCorrector.resetTouchModel(ctx)
                        refreshTouchSummary()
                        Toast.makeText(ctx, R.string.touch_model_reset_done, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .create()
            .showWarm()
    }

    private fun refreshSmsSummary() {
        val ctx = context ?: return
        val enabled = SmsCodeHelper.isEnabled(ctx)
        smsOtpPref?.isChecked = enabled
        smsOtpPref?.summary = when {
            !enabled -> "已关闭；开启后可自动提取短信验证码并显示在工具栏"
            SmsCodeHelper.hasSmsPermission(ctx) -> "已就绪 · 收到验证码短信将自动复制并在工具栏一键粘贴"
            else -> "已开启，等待授予「读取/接收短信」权限（请重新开关一次或前往系统权限管理授权）"
        }
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                GrammarModel.state.collect { renderGram(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                org.fcitx.fcitx5.android.ifboard.voice.OfflineVoiceModel.state.collect { renderVoice(it) }
            }
        }
    }

    private fun renderGram(s: GrammarModel.State) {
        val ctx = context ?: return
        val installed = GrammarModel.installed(ctx)
        val statusLine = when (s) {
            is GrammarModel.State.Checking -> "正在连接官方发布页…"
            is GrammarModel.State.Downloading ->
                "正在下载 ${GrammarModel.mb(s.done)} / ${GrammarModel.mb(s.total)}（${s.done * 100 / s.total.coerceAtLeast(1)}%），点按可取消"
            is GrammarModel.State.Verifying -> "正在校验…"
            is GrammarModel.State.Failed -> "下载失败：${s.message}。点按重试（会接着上次的进度）"
            else -> if (installed) {
                val v = GrammarModel.installedVersion(ctx)?.take(10)
                "已安装（${GrammarModel.mb(GrammarModel.file(ctx).length())}${if (v != null) "，官方 $v 版" else ""}）"
            } else {
                "未下载。点按下载（约 400 MB，建议连接 Wi-Fi）"
            }
        }
        gramStatus?.summary = statusLine
        gramDelete?.isEnabled = installed && !GrammarModel.busy
    }

    private fun renderVoice(s: org.fcitx.fcitx5.android.ifboard.voice.OfflineVoiceModel.State) {
        val ctx = context ?: return
        val m = org.fcitx.fcitx5.android.ifboard.voice.OfflineVoiceModel
        val installed = m.installed(ctx)
        voiceStatus?.summary = when (s) {
            is org.fcitx.fcitx5.android.ifboard.voice.OfflineVoiceModel.State.Downloading ->
                "正在下载 ${m.mb(s.done)} / ${m.mb(s.total)}（${s.done * 100 / s.total.coerceAtLeast(1)}%），点按可取消"
            is org.fcitx.fcitx5.android.ifboard.voice.OfflineVoiceModel.State.Unpacking -> "正在校验并解压…"
            is org.fcitx.fcitx5.android.ifboard.voice.OfflineVoiceModel.State.Failed -> "下载失败：${s.message}。点按重试（会接着上次的进度）"
            else -> if (installed) "已安装（${m.mb(m.sizeOnDisk(ctx))}，k2-fsa/sherpa-onnx 中英混合流式模型）"
            else "未下载。点按下载（约 ${m.mb(m.SIZE)}，来自 sherpa-onnx 官方 GitHub，建议连接 Wi-Fi）"
        }
        voiceDelete?.isEnabled = installed && !m.busy
    }

    private fun onVoiceStatusClick() {
        val ctx = requireContext()
        val m = org.fcitx.fcitx5.android.ifboard.voice.OfflineVoiceModel
        when {
            m.busy -> AlertDialog.Builder(ctx)
                .setTitle("取消下载？")
                .setMessage("已下载的部分会保留，下次接着下载。")
                .setPositiveButton("取消下载") { _, _ -> m.cancel() }
                .setNegativeButton("继续下载", null)
                .create()
                .showWarm(destructive = true)
            m.installed(ctx) -> Toast.makeText(ctx, "离线语音模型已安装", Toast.LENGTH_SHORT).show()
            else -> AlertDialog.Builder(ctx)
                .setTitle("下载离线语音模型")
                .setMessage(
                    "k2-fsa/sherpa-onnx 官方发布的中英混合流式识别模型（带标点），约 ${m.mb(m.SIZE)}，" +
                        "下载后会校验 SHA-256 再解压。\n\n识别完全在手机上进行，声音不会上传。建议连接 Wi-Fi 后下载。"
                )
                .setPositiveButton("下载") { _, _ -> m.start(ctx) }
                .setNegativeButton("以后再说", null)
                .create()
                .showWarm()
        }
    }

    private fun onGramStatusClick() {
        val ctx = requireContext()
        when {
            GrammarModel.busy -> AlertDialog.Builder(ctx)
                .setTitle("取消下载？")
                .setMessage("已下载的部分会保留，下次接着下载。")
                .setPositiveButton("取消下载") { _, _ -> GrammarModel.cancel() }
                .setNegativeButton("继续下载", null)
                .create()
                .showWarm(destructive = true)
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
                        renderGram(GrammarModel.state.value)
                        Toast.makeText(ctx, "语法模型已是最新（官方 ${r.updated.take(10)} 版）", Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    val installed = GrammarModel.installed(ctx)
                    AlertDialog.Builder(ctx)
                        .setTitle(if (installed) "语法模型有新版本" else "下载语法模型")
                        .setMessage(
                            "官方 ${r.updated.take(10)} 版，${GrammarModel.mb(r.size)}，来源：${r.source}。" +
                                (if (installed) "\n\n下载完成前继续使用旧模型，完成后自动替换。" else "") +
                                "\n\n建议连接 Wi-Fi 后下载。"
                        )
                        .setPositiveButton(if (installed) "更新" else "下载") { _, _ -> GrammarModel.start(ctx) }
                        .setNegativeButton("以后再说", null)
                        .create()
                        .showWarm()
                }
                .onFailure { Toast.makeText(ctx, it.message ?: "检查失败", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun syncSummary(ctx: Context, activeText: String?): String =
        activeText ?: lastSyncText(ctx)

    private fun lastSyncText(ctx: Context): String {
        val t = PreferenceManager.getDefaultSharedPreferences(ctx).getLong("ifb_webdav_last", 0)
        return if (t == 0L) "还没有同步过"
        else "上次同步：" + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(t))
    }
}

/** 分段胶囊：在 Koofr 与坚果云之间一键切换 WebDAV 地址 */
private class WebDavProviderPreference(context: Context) : Preference(context) {

    var currentUrl: String = IfboardSync.DEFAULT_WEBDAV_URL
    var onSelectUrl: ((String) -> Unit)? = null

    init {
        layoutResource = R.layout.ifb_pref_webdav_provider
        isSelectable = false
    }

    fun updateSelectedUrl(url: String) {
        currentUrl = url
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val ctx = holder.itemView.context
        val p = WarmPaperUi.palette(ctx)

        val segment = holder.findViewById(R.id.provider_segment) ?: return
        val koofrBtn = holder.findViewById(R.id.btn_provider_koofr) as? TextView ?: return
        val jianguoBtn = holder.findViewById(R.id.btn_provider_jianguoyun) as? TextView ?: return

        segment.background = WarmPaperUi.pillDrawable(ctx, p.brandSoft, p.borderStrong, 0.8f)

        val lower = currentUrl.lowercase(Locale.ROOT)
        val isKoofr = lower.contains("koofr")
        val isJianguo = lower.contains("jianguoyun")

        styleSegment(koofrBtn, isKoofr, p)
        styleSegment(jianguoBtn, isJianguo, p)

        koofrBtn.setOnClickListener {
            currentUrl = IfboardSync.URL_KOOFR
            onSelectUrl?.invoke(IfboardSync.URL_KOOFR)
            notifyChanged()
        }
        jianguoBtn.setOnClickListener {
            currentUrl = IfboardSync.URL_JIANGUOYUN
            onSelectUrl?.invoke(IfboardSync.URL_JIANGUOYUN)
            notifyChanged()
        }
    }

    private fun styleSegment(btn: TextView, selected: Boolean, p: WarmPaperUi.Palette) {
        val ctx = btn.context
        if (selected) {
            btn.background = WarmPaperUi.pillDrawable(ctx, p.surface, p.borderStrong, 0.8f)
            btn.setTextColor(p.brandInk)
        } else {
            btn.setBackgroundColor(Color.TRANSPARENT)
            btn.setTextColor(p.textSecondary)
        }
    }
}
