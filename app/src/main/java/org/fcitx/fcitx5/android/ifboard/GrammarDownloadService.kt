/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: 万象语法模型后台下载前台保活服务（防止切到后台被系统断网冻结）
 */
package org.fcitx.fcitx5.android.ifboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

class GrammarDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectorJob: Job? = null
    private var lastNotifAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            GrammarModel.cancel()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        val initialNotif = buildProgressNotification("正在连接下载源…", 0, 0, indeterminate = true)
        runCatching {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIF_PROGRESS_ID, initialNotif, type)
        }.onFailure {
            Timber.w(it, "startForeground for grammar download failed")
        }

        if (collectorJob == null) {
            collectorJob = scope.launch {
                val nm = getSystemService(NotificationManager::class.java)
                GrammarModel.state.collect { st ->
                    when (st) {
                        is GrammarModel.State.Checking -> {
                            nm?.notify(
                                NOTIF_PROGRESS_ID,
                                buildProgressNotification("正在连接下载源…", 0, 0, indeterminate = true)
                            )
                        }
                        is GrammarModel.State.Downloading -> {
                            val now = System.currentTimeMillis()
                            if (now - lastNotifAt >= 800L || st.done >= st.total) {
                                lastNotifAt = now
                                val pct = if (st.total > 0) ((st.done * 100) / st.total).toInt() else 0
                                val text = "${GrammarModel.mb(st.done)} / ${GrammarModel.mb(st.total)} ($pct%)"
                                val maxVal = 100
                                nm?.notify(
                                    NOTIF_PROGRESS_ID,
                                    buildProgressNotification(text, pct.coerceIn(0, 100), maxVal, indeterminate = false)
                                )
                            }
                        }
                        is GrammarModel.State.Verifying -> {
                            nm?.notify(
                                NOTIF_PROGRESS_ID,
                                buildProgressNotification("正在校验模型完整性…", 0, 0, indeterminate = true)
                            )
                        }
                        is GrammarModel.State.Done -> {
                            postResultNotification("万象语法模型已就绪", "整句输入已自动启用最新语法模型", success = true)
                            stopForegroundAndSelf()
                        }
                        is GrammarModel.State.Failed -> {
                            postResultNotification("语法模型下载中断", "${st.message}（可点击重试继续断点下载）", success = false)
                            stopForegroundAndSelf()
                        }
                        is GrammarModel.State.Idle -> {
                            if (!GrammarModel.busy) stopForegroundAndSelf()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun buildProgressNotification(
        text: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean
    ): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, IfboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GrammarDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载万象语法模型")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, indeterminate)
            .addAction(0, "取消", cancelIntent)
            .build()
    }

    private fun postResultNotification(title: String, text: String, success: Boolean) {
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, IfboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_RESULT_ID, n)
        }
    }

    private fun stopForegroundAndSelf() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        collectorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ifb_gram_dl"
        private const val NOTIF_PROGRESS_ID = 0x47524D
        private const val NOTIF_RESULT_ID = 0x47524E
        private const val ACTION_CANCEL = "org.fcitx.fcitx5.android.ifboard.ACTION_CANCEL_GRAMMAR"

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                CHANNEL_ID,
                "语法模型下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持万象语法模型在后台切换或锁屏时稳定下载"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }

        fun start(ctx: Context) {
            ensureChannel(ctx)
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, GrammarDownloadService::class.java))
            }.onFailure { Timber.w(it, "Unable to start GrammarDownloadService") }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, GrammarDownloadService::class.java)) }
        }
    }
}
