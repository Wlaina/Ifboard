/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ifboard: 主动发送文件（类似 LocalSend）的界面部分——
 *  · 系统分享菜单「发送到电脑」（FileSendActivity → FileSendService 前台发送，通知栏显示进度）
 *  · 通知栏快捷开关「接收文件」（ReceiveTileService：马上连电脑，收下电脑排队要发的文件）
 *  · 收发通知（XferNotifier）
 * 传输本身在 [ClipSync] 里，走剪贴板同步的同一条加密连接。
 */
package org.fcitx.fcitx5.android.ifboard.clipsync

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

object XferNotifier {
    private const val CHANNEL = "ifb_xfer"
    const val SEND_ID = 47_321

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "文件收发", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "手机与电脑之间主动发送文件的进度与结果"
                setSound(null, null)
            }
        )
    }

    fun builder(ctx: Context, title: String, text: String): NotificationCompat.Builder {
        ensureChannel(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOnlyAlertOnce(true)
    }

    private fun post(ctx: Context, id: Int, n: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
            .onFailure { Timber.w(it, "xfer notify failed") } // 没有通知权限时静默
    }

    fun progress(ctx: Context, id: Int, title: String, text: String, pct: Int) = post(
        ctx, id, builder(ctx, title, text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, pct.coerceIn(0, 100), false)
            .build()
    )

    fun failed(ctx: Context, id: Int, title: String, text: String) = post(
        ctx, id, builder(ctx, title, text)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
    )

    /** 收到文件：点通知直接打开 */
    fun received(ctx: Context, id: Int, peer: String, name: String, uri: Uri, mime: String) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            ctx, id, Intent.createChooser(view, "打开 $name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        post(
            ctx, id, builder(ctx, "收到 $peer 的文件", "$name（下载/Ifboard，点此打开）")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
        ContextCompat.getMainExecutor(ctx).execute {
            Toast.makeText(ctx, "收到 $peer 的文件：$name", Toast.LENGTH_SHORT).show()
        }
    }
}

/** 系统分享菜单里的「发送到电脑」：取出分享的文件交给前台服务发送，自身不显示界面 */
class FileSendActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { handle(intent) }.onFailure {
            Timber.w(it, "share to pc failed")
            Toast.makeText(this, "发送失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun handle(intent: Intent) {
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
        }
        if (uris.isEmpty()) {
            // 只分享了文字：放进电脑剪贴板
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "没有可发送的内容", Toast.LENGTH_SHORT).show()
                return
            }
            if (!ClipSync.isEnabled()) {
                Toast.makeText(this, "请先在键盘菜单里开启「剪贴板同步」", Toast.LENGTH_SHORT).show()
                return
            }
            ClipSync.onLocalText(text)
            Toast.makeText(this, "文字已发送到电脑剪贴板", Toast.LENGTH_SHORT).show()
            return
        }
        val svc = Intent(this, FileSendService::class.java).apply {
            putParcelableArrayListExtra(FileSendService.EXTRA_URIS, ArrayList(uris))
            // 把读取权限一并交给服务（分享来的链接权限默认只属于本界面）
            clipData = ClipData.newRawUri("files", uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ContextCompat.startForegroundService(this, svc)
        Toast.makeText(this, "正在发送 ${uris.size} 个文件到电脑…", Toast.LENGTH_SHORT).show()
    }
}

/** 前台发送：切到别的应用或锁屏也不会中断 */
class FileSendService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uris = intent?.let { IntentCompat.getParcelableArrayListExtra(it, EXTRA_URIS, Uri::class.java) }.orEmpty()
        val n = XferNotifier.builder(this, "正在发送到电脑", "正在连接电脑…")
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()
        runCatching {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, XferNotifier.SEND_ID, n, type)
        }.onFailure { Timber.w(it, "startForeground for file send failed") }
        if (uris.isEmpty()) {
            if (running == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        running++
        scope.launch {
            val nm = NotificationManagerCompat.from(this@FileSendService)
            val result = ClipSync.sendFiles(uris) { i, total, name, pct ->
                val notif = XferNotifier.builder(this@FileSendService, "正在发送到电脑（$i/$total）", "$name  $pct%")
                    .setOngoing(true)
                    .setProgress(100, pct, false)
                    .build()
                runCatching { nm.notify(XferNotifier.SEND_ID, notif) }
            }
            running--
            Toast.makeText(this@FileSendService, result.message, Toast.LENGTH_LONG).show()
            if (running == 0) {
                ServiceCompat.stopForeground(this@FileSendService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                val done = XferNotifier.builder(
                    this@FileSendService, if (result.failed == 0) "发送完成" else "部分文件未发送", result.message
                )
                    .setSmallIcon(if (result.failed == 0) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
                    .setAutoCancel(true)
                    .build()
                runCatching { nm.notify(XferNotifier.SEND_ID + 1, done) }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URIS = "uris"
    }
}

/** 通知栏快捷开关「接收文件」：电脑右键「发送到 → Ifboard 手机」后，点它马上收 */
@RequiresApi(Build.VERSION_CODES.N)
class ReceiveTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "接收文件"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        ClipSync.receiveNow()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }
}
